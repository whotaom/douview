package com.example.tv.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TV 端纯原生动态背景助手（PixelCopy + 48×84 极小缩略图 + 轻量模糊）
 * - 保持 PlayerView 使用 SurfaceView 硬件直出，不改 TextureView、不引入 WebView/Base64
 * - 严格门控：仅竖屏流 + 正在播放 + 已出首帧 + 页面在前台时以 ≈5fps 采样
 * - 横屏 / 暂停 / 切台 / 退后台 / 错误态立即停止并清空，彻底避免串台残影与后台空转
 */
class DynBgHelper(
    private val playerView: PlayerView,
    private val dynBgView: ImageView,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_WIDTH = 48
        const val SAMPLE_HEIGHT = 84
        const val SAMPLE_INTERVAL_MS = 200L
        private const val BLUR_RADIUS = 3
        private const val SATURATION = 0.80f
        private const val BRIGHTNESS_SCALE = 0.58f

        fun isPortraitVideo(
            width: Int,
            height: Int,
            pixelWidthHeightRatio: Float = 1f
        ): Boolean {
            if (width <= 0 || height <= 0) return false
            val sar = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
            val displayWidth = width * sar
            return height > displayWidth * 1.02f
        }

        fun shouldRunDynamicBg(
            isPortrait: Boolean,
            isPlaying: Boolean,
            hasFirstFrame: Boolean,
            isForeground: Boolean
        ): Boolean = isPortrait && isPlaying && hasFirstFrame && isForeground

        /**
         * 对 48×84 缩略图做 2-pass BoxBlur + 轻微压暗与降饱和（直接像素运算，零 Canvas/ColorMatrix 分配）。
         * 保持深色像素不过度提亮，确保前置视频与 OSD 文案对比度清晰。
         */
        fun processSampleBitmap(src: Bitmap, blurRadius: Int = BLUR_RADIUS): Bitmap {
            val w = src.width
            val h = src.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (w <= 0 || h <= 0) return out

            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            if (blurRadius >= 1) {
                val temp = IntArray(w * h)
                for (y in 0 until h) {
                    val rowOffset = y * w
                    for (x in 0 until w) {
                        var a = 0
                        var r = 0
                        var g = 0
                        var b = 0
                        var count = 0
                        for (dx in -blurRadius..blurRadius) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val c = pixels[rowOffset + nx]
                            a += c ushr 24
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                        temp[rowOffset + x] =
                            ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    }
                }

                for (x in 0 until w) {
                    for (y in 0 until h) {
                        var a = 0
                        var r = 0
                        var g = 0
                        var b = 0
                        var count = 0
                        for (dy in -blurRadius..blurRadius) {
                            val ny = (y + dy).coerceIn(0, h - 1)
                            val c = temp[ny * w + x]
                            a += c ushr 24
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                        val br = r / count
                        val bg = g / count
                        val bb = b / count
                        val ba = a / count
                        val luma = 0.299f * br + 0.587f * bg + 0.114f * bb
                        val nr = ((luma + (br - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                        val ng = ((luma + (bg - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                        val nb = ((luma + (bb - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                        pixels[y * w + x] = (ba shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            } else {
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val ba = c ushr 24
                    val br = (c shr 16) and 0xFF
                    val bg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    val luma = 0.299f * br + 0.587f * bg + 0.114f * bb
                    val nr = ((luma + (br - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                    val ng = ((luma + (bg - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                    val nb = ((luma + (bb - luma) * SATURATION) * BRIGHTNESS_SCALE).toInt().coerceIn(0, 255)
                    pixels[i] = (ba shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }

            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var samplingJob: Job? = null
    private val generation = AtomicInteger(0)
    private val copyInFlight = AtomicBoolean(false)
    @Volatile
    private var released = false

    private var isPortraitStream: Boolean = false
    private var hasFirstFrame: Boolean = false
    private var isPlaying: Boolean = false
    private var isForeground: Boolean = true

    fun setPortraitStream(portrait: Boolean) {
        if (isPortraitStream == portrait) return
        isPortraitStream = portrait
        syncState()
    }

    fun setFirstFrameRendered(rendered: Boolean) {
        if (hasFirstFrame == rendered) return
        hasFirstFrame = rendered
        syncState()
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        syncState()
    }

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        syncState()
    }

    fun resetForChannelSwitch() {
        isPortraitStream = false
        hasFirstFrame = false
        isPlaying = false
        stopAndClear()
    }

    fun stopAndClear() {
        generation.incrementAndGet()
        samplingJob?.cancel()
        samplingJob = null
        copyInFlight.set(false)

        val clearAction = {
            dynBgView.animate().cancel()
            dynBgView.setImageDrawable(null)
            dynBgView.alpha = 0f
            dynBgView.visibility = View.GONE
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearAction()
        } else {
            mainHandler.post(clearAction)
        }
    }

    fun release() {
        released = true
        stopAndClear()
        workerHandler = null
        workerThread?.quitSafely()
        workerThread = null
    }

    private fun syncState() {
        if (released) return
        if (shouldRunDynamicBg(isPortraitStream, isPlaying, hasFirstFrame, isForeground)) {
            startSamplingIfNeeded()
        } else {
            stopAndClear()
        }
    }

    private fun startSamplingIfNeeded() {
        if (released || samplingJob?.isActive == true) return
        val myGen = generation.incrementAndGet()
        samplingJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && !released && generation.get() == myGen) {
                if (!shouldRunDynamicBg(isPortraitStream, isPlaying, hasFirstFrame, isForeground)) {
                    break
                }
                requestSample(myGen)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun ensureWorkerHandler(): Handler? {
        if (released) return null
        val existing = workerHandler
        if (existing != null && workerThread?.isAlive == true) return existing
        val thread = HandlerThread("DynBgPixelCopy").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler
        return handler
    }

    private fun requestSample(expectedGen: Int) {
        if (released || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (generation.get() != expectedGen) return

        val surfaceView = playerView.videoSurfaceView as? SurfaceView ?: return
        if (!surfaceView.isAttachedToWindow || surfaceView.width <= 0 || surfaceView.height <= 0) return
        val surface = surfaceView.holder?.surface ?: return
        if (!surface.isValid) return

        val handler = ensureWorkerHandler() ?: return
        if (!copyInFlight.compareAndSet(false, true)) return

        val tiny = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                surfaceView,
                tiny,
                { result ->
                    copyInFlight.set(false)
                    if (result == PixelCopy.SUCCESS &&
                        !released &&
                        generation.get() == expectedGen &&
                        shouldRunDynamicBg(isPortraitStream, isPlaying, hasFirstFrame, isForeground)
                    ) {
                        val processed = try {
                            processSampleBitmap(tiny)
                        } catch (_: Throwable) {
                            null
                        } finally {
                            tiny.recycle()
                        }
                        if (processed != null) {
                            mainHandler.post {
                                if (!released &&
                                    generation.get() == expectedGen &&
                                    shouldRunDynamicBg(isPortraitStream, isPlaying, hasFirstFrame, isForeground)
                                ) {
                                    dynBgView.setImageBitmap(processed)
                                    if (dynBgView.visibility != View.VISIBLE) {
                                        dynBgView.alpha = 0f
                                        dynBgView.visibility = View.VISIBLE
                                        dynBgView.animate().alpha(1f).setDuration(220L).start()
                                    }
                                }
                            }
                        }
                    } else {
                        tiny.recycle()
                    }
                },
                handler
            )
        } catch (_: Throwable) {
            copyInFlight.set(false)
            tiny.recycle()
        }
    }
}
