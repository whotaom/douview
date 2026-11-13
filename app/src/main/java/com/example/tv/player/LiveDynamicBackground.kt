package com.example.tv.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 竖屏直播动态背景策略（主渲染 SurfaceView 优先 + 异步 PixelCopy 采样 + 黑帧检测自退避）：
 * - 主画面永远优先走原生 SurfaceView 硬件直出，彻底规避 RK3528 / H96 等盒子 TextureView 黑屏隐患
 * - 未出首帧：由外层 poster/头像轻虚化占位（约 12dp 等效，alpha 0.90，无头像纯黑底）
 * - 出首帧后：通过 Android PixelCopy (API 24+) 从 SurfaceView 异步拷取实况帧 → 双缓冲 + 像素亮度验证 + 轻磨砂 → Crop 铺满左右
 * - 自愈与休眠：非竖屏 / 未播放 / 未出首帧 / 息屏音频 / 进后台 / 无有效 Surface / PixelCopy 连续失败或全黑帧 → 立即停采并安全退回头像占位，绝不覆盖黑图
 * - 切台：resetKey 变化立刻清空旧帧并重置退避状态，绝不串台残留
 */
enum class PlayerSurfaceMode {
    SURFACE_VIEW,
    TEXTURE_VIEW
}

data class LiveDynamicBackgroundState(
    val enabled: Boolean = true,
    val isPlaying: Boolean = false,
    val isPortraitStream: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val isScreenOffAudioMode: Boolean = false,
    val isLifecycleBackground: Boolean = false,
    val surfaceIsSupported: Boolean = false,
    val samplerBackedOff: Boolean = false,
    val resetKey: String = ""
) {
    val shouldSample: Boolean
        get() = enabled &&
            isPlaying &&
            isPortraitStream &&
            hasRenderedFirstFrame &&
            !isScreenOffAudioMode &&
            !isLifecycleBackground &&
            surfaceIsSupported &&
            !samplerBackedOff
}

object LiveFrameAnalyzer {
    /**
     * 快速 8x8 网格亮度与对比度探针（< 0.02ms）：
     * 过滤 TextureView / PixelCopy 产出的纯黑帧、透明空帧，避免将全黑帧贴到背景层或误判已出画
     */
    fun hasVisibleContent(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        var minLuma = 255
        var maxLuma = 0
        var opaqueSamples = 0
        val cols = 8
        val rows = 8

        for (ry in 0 until rows) {
            val y = ((ry + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            for (rx in 0 until cols) {
                val x = ((rx + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                val px = bitmap.getPixel(x, y)
                val a = (px ushr 24) and 0xFF
                if (a < 16) continue
                opaqueSamples++
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) ushr 8
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
            }
        }

        if (opaqueSamples == 0) return false
        return maxLuma >= 12 || (maxLuma - minLuma) >= 6
    }
}

object LiveDynamicBackgroundConfig {
    const val TARGET_HEIGHT_DEFAULT = 480
    const val TARGET_HEIGHT_LOW_RAM = 360
    const val SAMPLE_INTERVAL_MS = 85L
    const val LIVE_BLUR_PASSES = 3
    const val POSTER_BLUR_PASSES = 5
    const val LIVE_SCALE = 1.08f
    const val LIVE_ALPHA = 0.92f
    const val POSTER_ALPHA = 0.90f
    const val MAX_CONSECUTIVE_FAILURES = 16

    /**
     * 顶级 TV 播放策略：主画面永远使用硬件兼容度最高的 SurfaceView，
     * 通过 PixelCopy 异步采样背景，不再以 TextureView 冒险承载主视频解码。
     */
    fun resolveSurfaceType(
        preferDynamicBg: Boolean,
        textureViewBlacklisted: Boolean
    ): PlayerSurfaceMode = PlayerSurfaceMode.SURFACE_VIEW

    fun resolveTargetHeight(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true || Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L
        return if (isLowRam) TARGET_HEIGHT_LOW_RAM else TARGET_HEIGHT_DEFAULT
    }

    fun computeSampleDimensions(
        surfaceWidth: Int,
        surfaceHeight: Int,
        targetHeight: Int
    ): Pair<Int, Int> {
        val safeW = surfaceWidth.coerceAtLeast(1)
        val safeH = surfaceHeight.coerceAtLeast(1)
        val outH = targetHeight.coerceIn(120, TARGET_HEIGHT_DEFAULT)
        val ratio = safeW.toFloat() / safeH.toFloat()
        val outW = (outH * ratio).toInt().coerceIn(72, outH)
        val evenW = if (outW % 2 == 0) outW else outW + 1
        val evenH = if (outH % 2 == 0) outH else outH + 1
        return evenW to evenH
    }

    /**
     * 轻量可分离盒式模糊（仅用于背景 Bitmap，绝不作用在 PlayerView 上）
     */
    fun applyBoxBlur(
        src: Bitmap,
        passes: Int,
        reusePix: IntArray? = null,
        reuseTmp: IntArray? = null,
        outBitmap: Bitmap? = null
    ): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 2 || h <= 2 || passes <= 0) return src
        val count = w * h
        val pix = if (reusePix != null && reusePix.size >= count) reusePix else IntArray(count)
        val tmp = if (reuseTmp != null && reuseTmp.size >= count) reuseTmp else IntArray(count)

        src.getPixels(pix, 0, w, 0, 0, w, h)

        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val p0 = pix[row + (x - 1).coerceAtLeast(0)]
                    val p1 = pix[row + x]
                    val p2 = pix[row + (x + 1).coerceAtMost(w - 1)]
                    val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF) + ((p2 shr 16) and 0xFF)) / 3
                    val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF) + ((p2 shr 8) and 0xFF)) / 3
                    val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF)) / 3
                    tmp[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            for (y in 0 until h) {
                val y0 = (y - 1).coerceAtLeast(0) * w
                val y1 = y * w
                val y2 = (y + 1).coerceAtMost(h - 1) * w
                for (x in 0 until w) {
                    val p0 = tmp[y0 + x]
                    val p1 = tmp[y1 + x]
                    val p2 = tmp[y2 + x]
                    val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF) + ((p2 shr 16) and 0xFF)) / 3
                    val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF) + ((p2 shr 8) and 0xFF)) / 3
                    val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF)) / 3
                    pix[y1 + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val target = if (outBitmap != null && !outBitmap.isRecycled && outBitmap.width == w && outBitmap.height == h && outBitmap.isMutable) {
            outBitmap
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        target.setPixels(pix, 0, w, 0, 0, w, h)
        return target
    }
}

/**
 * 固定双缓冲实况背景采样控制器（SurfaceView + PixelCopy 优先，兼顾 TextureView）：
 * - 零逐帧 Bitmap/IntArray 分配
 * - 采样前 eraseColor(BLACK) 防绿边，采样后经过亮度探针拦截纯黑/透明空帧
 * - PixelCopy 异步回调与 Dispatchers.Default 模糊全程不阻塞主线程
 * - 连续失败/全黑达阈值自动安全退避，退回头像占位背景而不影响主画面播放
 */
class LiveDynamicBackgroundController(
    private val scope: CoroutineScope,
    private val targetHeight: Int,
    private val onFrameUpdated: (Bitmap?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null

    private var state = LiveDynamicBackgroundState()
    private var playerViewRef: PlayerView? = null
    private var sampleJob: Job? = null

    private var bufferBmps: Array<Bitmap>? = null
    private var bufferW = 0
    private var bufferH = 0
    private var activeIndex = 0
    private var scratchPix: IntArray? = null
    private var scratchTmp: IntArray? = null

    private val inFlightCopy = AtomicBoolean(false)
    private var consecutiveFailures = 0

    @Volatile
    private var currentDisplayedBitmap: Bitmap? = null
    @Volatile
    private var writingBitmap: Bitmap? = null

    fun attachPlayerView(playerView: PlayerView?) {
        playerViewRef = playerView
        val sv = playerView?.videoSurfaceView
        val supported = sv is SurfaceView || sv is TextureView
        updateState { copy(surfaceIsSupported = supported) }
    }

    fun onChannelChanged(resetKey: String) {
        if (state.resetKey != resetKey) {
            consecutiveFailures = 0
            clearDisplayedFrame()
            updateState {
                copy(
                    resetKey = resetKey,
                    hasRenderedFirstFrame = false,
                    samplerBackedOff = false
                )
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        updateState { copy(isPlaying = playing) }
    }

    fun setPortraitStream(isPortrait: Boolean) {
        updateState { copy(isPortraitStream = isPortrait) }
    }

    fun setHasRenderedFirstFrame(rendered: Boolean) {
        updateState { copy(hasRenderedFirstFrame = rendered) }
    }

    fun setScreenOffAudioMode(screenOffAudio: Boolean) {
        updateState { copy(isScreenOffAudioMode = screenOffAudio) }
    }

    fun setLifecycleBackground(inBackground: Boolean) {
        updateState { copy(isLifecycleBackground = inBackground) }
    }

    fun setSurfaceSupported(supported: Boolean) {
        updateState { copy(surfaceIsSupported = supported) }
    }

    fun setEnabled(enabled: Boolean) {
        updateState { copy(enabled = enabled) }
    }

    val currentState: LiveDynamicBackgroundState
        get() = state

    private inline fun updateState(transform: LiveDynamicBackgroundState.() -> LiveDynamicBackgroundState) {
        val next = state.transform()
        if (next == state) return
        val wasSampling = state.shouldSample
        state = next

        if (next.shouldSample) {
            if (!wasSampling || sampleJob?.isActive != true) {
                startSampling()
            }
        } else {
            stopSampling(clearFrame = true)
        }
    }

    private fun ensureCopyHandler(): Handler {
        val existing = pixelCopyHandler
        if (existing != null && pixelCopyThread?.isAlive == true) return existing
        val thread = HandlerThread("LiveBgPixelCopy").apply { start() }
        pixelCopyThread = thread
        return Handler(thread.looper).also { pixelCopyHandler = it }
    }

    private fun clearDisplayedFrame() {
        currentDisplayedBitmap = null
        onFrameUpdated(null)
    }

    private fun startSampling() {
        sampleJob?.cancel()
        val capturedResetKey = state.resetKey
        sampleJob = scope.launch {
            while (isActive && state.shouldSample && state.resetKey == capturedResetKey) {
                val videoSurface = playerViewRef?.videoSurfaceView
                if (videoSurface == null) {
                    updateState { copy(surfaceIsSupported = false) }
                    break
                }

                val surfaceW = videoSurface.width
                val surfaceH = videoSurface.height

                if (surfaceW > 0 && surfaceH > 0) {
                    val (w, h) = LiveDynamicBackgroundConfig.computeSampleDimensions(
                        surfaceW,
                        surfaceH,
                        targetHeight
                    )
                    ensureBuffers(w, h)
                    val bufs = bufferBmps
                    if (bufs != null && inFlightCopy.compareAndSet(false, true)) {
                        val writeIdx = 1 - activeIndex
                        val writeBmp = bufs[writeIdx]
                        var copiedOk = false
                        try {
                            if (!writeBmp.isRecycled) {
                                writeBmp.eraseColor(Color.BLACK)
                                writingBitmap = writeBmp
                                copiedOk = when (videoSurface) {
                                    is SurfaceView -> copyFromSurfaceView(videoSurface, writeBmp)
                                    is TextureView -> copyFromTextureView(videoSurface, writeBmp)
                                    else -> false
                                }
                            }
                        } finally {
                            writingBitmap = null
                            inFlightCopy.set(false)
                        }

                        if (!isActive || !state.shouldSample || state.resetKey != capturedResetKey || writeBmp.isRecycled) {
                            val stillOwned = bufferBmps?.any { it === writeBmp } == true
                            if (!stillOwned && writeBmp !== currentDisplayedBitmap && !writeBmp.isRecycled) {
                                writeBmp.recycle()
                            }
                        } else if (copiedOk && LiveFrameAnalyzer.hasVisibleContent(writeBmp)) {
                            consecutiveFailures = 0
                            val pix = scratchPix
                            val tmp = scratchTmp
                            writingBitmap = writeBmp
                            try {
                                withContext(Dispatchers.Default) {
                                    if (!writeBmp.isRecycled) {
                                        LiveDynamicBackgroundConfig.applyBoxBlur(
                                            src = writeBmp,
                                            passes = LiveDynamicBackgroundConfig.LIVE_BLUR_PASSES,
                                            reusePix = pix,
                                            reuseTmp = tmp,
                                            outBitmap = writeBmp
                                        )
                                    }
                                }
                            } finally {
                                writingBitmap = null
                            }
                            val stillOwned = bufferBmps?.any { it === writeBmp } == true
                            if (isActive && stillOwned && state.shouldSample && state.resetKey == capturedResetKey && !writeBmp.isRecycled) {
                                activeIndex = writeIdx
                                currentDisplayedBitmap = writeBmp
                                onFrameUpdated(writeBmp)
                            } else if (!stillOwned && writeBmp !== currentDisplayedBitmap && !writeBmp.isRecycled) {
                                writeBmp.recycle()
                            }
                        } else {
                            // 仅当 Surface 已有效但连续采得全黑或失败帧时累计退避计数，绝不将黑帧绘制到背景
                            val surfaceReady = when (videoSurface) {
                                is SurfaceView -> videoSurface.holder?.surface?.isValid == true
                                is TextureView -> videoSurface.isAvailable
                                else -> false
                            }
                            if (surfaceReady) {
                                consecutiveFailures++
                                if (consecutiveFailures >= LiveDynamicBackgroundConfig.MAX_CONSECUTIVE_FAILURES) {
                                    updateState { copy(samplerBackedOff = true) }
                                    break
                                }
                            }
                        }
                    }
                }
                delay(LiveDynamicBackgroundConfig.SAMPLE_INTERVAL_MS)
            }
        }
    }

    private suspend fun copyFromSurfaceView(surfaceView: SurfaceView, dest: Bitmap): Boolean {
        val surface = surfaceView.holder?.surface
        if (surface == null || !surface.isValid || dest.isRecycled) return false
        val handler = ensureCopyHandler()
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    surfaceView,
                    dest,
                    { result ->
                        if (cont.isActive) {
                            cont.resume(result == PixelCopy.SUCCESS)
                        }
                    },
                    handler
                )
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun copyFromTextureView(textureView: TextureView, dest: Bitmap): Boolean {
        if (!textureView.isAvailable || dest.isRecycled) return false
        return try {
            textureView.getBitmap(dest) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureBuffers(w: Int, h: Int) {
        val existing = bufferBmps
        if (existing != null && bufferW == w && bufferH == h && existing.all { !it.isRecycled }) {
            return
        }
        recycleBuffersSafely()
        bufferW = w
        bufferH = h
        activeIndex = 0
        bufferBmps = arrayOf(
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        )
        scratchPix = IntArray(w * h)
        scratchTmp = IntArray(w * h)
    }

    private fun stopSampling(clearFrame: Boolean) {
        sampleJob?.cancel()
        sampleJob = null
        if (clearFrame) {
            clearDisplayedFrame()
        }
        recycleBuffersSafely()
    }

    private fun recycleBuffersSafely() {
        val bufs = bufferBmps
        bufferBmps = null
        bufferW = 0
        bufferH = 0
        scratchPix = null
        scratchTmp = null
        val displayed = currentDisplayedBitmap
        val writing = writingBitmap
        bufs?.forEach { bmp ->
            if (bmp === writing) {
                return@forEach
            }
            if (!bmp.isRecycled && bmp !== displayed) {
                bmp.recycle()
            } else if (!bmp.isRecycled) {
                mainHandler.postDelayed({
                    if (!bmp.isRecycled && bmp !== currentDisplayedBitmap && bmp !== writingBitmap) {
                        bmp.recycle()
                    }
                }, 300L)
            }
        }
    }

    fun release() {
        stopSampling(clearFrame = true)
        playerViewRef = null
        try {
            pixelCopyThread?.quitSafely()
        } catch (_: Throwable) {}
        pixelCopyThread = null
        pixelCopyHandler = null
    }
}
