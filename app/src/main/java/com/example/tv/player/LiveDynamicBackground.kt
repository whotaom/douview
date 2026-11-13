package com.example.tv.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 抽离的竖屏直播动态背景策略（与 douv 抽离版一致，并修齐隐性问题）：
 * - 未出首帧：由外层 poster/头像轻虚化占位（约 12dp 等效，alpha 0.90）
 * - 出首帧后：从单个 PlayerView 的 TextureView 采样实况帧 → 双缓冲 + 轻磨砂（约 6dp 等效）→ Crop 铺满左右
 * - 休眠条件：非竖屏 / 未播放 / 未出首帧 / 息屏音频 / 进后台 / 非 TextureView → 立即停采并清空
 * - 切台：resetKey 变化立刻清空旧帧，绝不串台残留
 */
enum class PlayerSurfaceMode {
    TEXTURE_VIEW,
    SURFACE_VIEW
}

data class LiveDynamicBackgroundState(
    val enabled: Boolean = true,
    val isPlaying: Boolean = false,
    val isPortraitStream: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val isScreenOffAudioMode: Boolean = false,
    val isLifecycleBackground: Boolean = false,
    val surfaceIsTextureView: Boolean = false,
    val resetKey: String = ""
) {
    val shouldSample: Boolean
        get() = enabled &&
            isPlaying &&
            isPortraitStream &&
            hasRenderedFirstFrame &&
            !isScreenOffAudioMode &&
            !isLifecycleBackground &&
            surfaceIsTextureView
}

object LiveDynamicBackgroundConfig {
    const val TARGET_HEIGHT_DEFAULT = 480
    const val TARGET_HEIGHT_LOW_RAM = 360
    const val SAMPLE_INTERVAL_MS = 75L
    const val LIVE_BLUR_PASSES = 3
    const val POSTER_BLUR_PASSES = 5
    const val LIVE_SCALE = 1.08f
    const val LIVE_ALPHA = 0.92f
    const val POSTER_ALPHA = 0.90f

    fun resolveSurfaceType(
        preferDynamicBg: Boolean,
        textureViewBlacklisted: Boolean
    ): PlayerSurfaceMode {
        return if (preferDynamicBg && !textureViewBlacklisted) {
            PlayerSurfaceMode.TEXTURE_VIEW
        } else {
            PlayerSurfaceMode.SURFACE_VIEW
        }
    }

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
 * 固定双缓冲 TextureView 实况采样控制器：
 * - 零逐帧 Bitmap/IntArray 分配
 * - getBitmap 前 eraseColor(BLACK) 防绿边
 * - 仅在后台线程执行轻磨砂，主线程零阻塞
 * - 停止或销毁时先清空展示引用，再安全回收非当前展示的缓冲 Bitmap
 */
class LiveDynamicBackgroundController(
    private val scope: CoroutineScope,
    private val targetHeight: Int,
    private val onFrameUpdated: (Bitmap?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = LiveDynamicBackgroundState()
    private var playerViewRef: PlayerView? = null
    private var sampleJob: Job? = null

    private var bufferBmps: Array<Bitmap>? = null
    private var bufferW = 0
    private var bufferH = 0
    private var activeIndex = 0
    private var scratchPix: IntArray? = null
    private var scratchTmp: IntArray? = null

    @Volatile
    private var currentDisplayedBitmap: Bitmap? = null
    @Volatile
    private var writingBitmap: Bitmap? = null

    fun attachPlayerView(playerView: PlayerView?) {
        playerViewRef = playerView
        val isTexture = playerView?.videoSurfaceView is TextureView
        updateState { copy(surfaceIsTextureView = isTexture) }
    }

    fun onChannelChanged(resetKey: String) {
        if (state.resetKey != resetKey) {
            clearDisplayedFrame()
            updateState {
                copy(
                    resetKey = resetKey,
                    hasRenderedFirstFrame = false
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

    fun setSurfaceIsTextureView(isTexture: Boolean) {
        updateState { copy(surfaceIsTextureView = isTexture) }
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

    private fun clearDisplayedFrame() {
        currentDisplayedBitmap = null
        onFrameUpdated(null)
    }

    private fun startSampling() {
        sampleJob?.cancel()
        val capturedResetKey = state.resetKey
        sampleJob = scope.launch {
            while (isActive && state.shouldSample && state.resetKey == capturedResetKey) {
                val tv = playerViewRef?.videoSurfaceView as? TextureView
                if (tv == null) {
                    updateState { copy(surfaceIsTextureView = false) }
                    break
                }

                if (tv.isAvailable && tv.width > 0 && tv.height > 0) {
                    val (w, h) = LiveDynamicBackgroundConfig.computeSampleDimensions(
                        tv.width,
                        tv.height,
                        targetHeight
                    )
                    ensureBuffers(w, h)
                    val bufs = bufferBmps
                    if (bufs != null) {
                        val writeIdx = 1 - activeIndex
                        val writeBmp = bufs[writeIdx]
                        if (!writeBmp.isRecycled) {
                            writeBmp.eraseColor(Color.BLACK)
                            val grabbed = tv.getBitmap(writeBmp)
                            if (grabbed != null && state.shouldSample && state.resetKey == capturedResetKey) {
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
                            }
                        }
                    }
                }
                delay(LiveDynamicBackgroundConfig.SAMPLE_INTERVAL_MS)
            }
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
                // 正在后台线程处理中，由 finally 块在结束后安全回收
                return@forEach
            }
            if (!bmp.isRecycled && bmp !== displayed) {
                bmp.recycle()
            } else if (!bmp.isRecycled) {
                // 延迟回收曾展示过的最后一帧，确保 ImageView 渲染管线已完全脱离引用
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
    }
}
