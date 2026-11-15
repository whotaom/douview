package com.example.tv.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs

/**
 * TV 端竖屏直播纯代码生成舞台氛围背景视图（零 PixelCopy / 零 TextureView / 零外部图片资源）
 *
 * 设计要点（针对 RK3528 等硬件直出盒子优化）：
 * 1. 仅在确认当前流为竖屏（有效宽 < 高）时显示在透明背景 PlayerView 之下；
 *    中心 9:16 区域由 SurfaceView 硬件直出打孔承载画面，左右两侧黑边区域自然呈现柔光舞台氛围。
 * 2. 切换频道时仅在内存中按 320x180 极小分辨率生成一次程序化渐变纹理，绘制时硬件双线性插值放大，
 *    零每帧对象分配、零 RenderEffect 兼容性风险，完美适配 API 24-36 与 32 位盒子。
 * 3. 退后台或切换横屏流时立即停播微动画并释放绘制开销。
 */
class PortraitAmbientBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class AmbientPalette(
        val baseColor: Int,
        val leftGlowColor: Int,
        val rightGlowColor: Int,
        val accentOrbColor: Int
    )

    companion object {
        private const val TEX_WIDTH = 320
        private const val TEX_HEIGHT = 180

        private val CURATED_PALETTES = listOf(
            // 剧场暖金与深靛蓝（高对比暗调舞台感）
            AmbientPalette(
                baseColor = Color.parseColor("#07080D"),
                leftGlowColor = Color.parseColor("#66C88428"),
                rightGlowColor = Color.parseColor("#52265E78"),
                accentOrbColor = Color.parseColor("#38F2B857")
            ),
            // 霓虹青与深紫夜场
            AmbientPalette(
                baseColor = Color.parseColor("#07090E"),
                leftGlowColor = Color.parseColor("#5419909A"),
                rightGlowColor = Color.parseColor("#586A2E7A"),
                accentOrbColor = Color.parseColor("#3425F4EE")
            ),
            // 翡翠森系柔光
            AmbientPalette(
                baseColor = Color.parseColor("#06090B"),
                leftGlowColor = Color.parseColor("#5C187C58"),
                rightGlowColor = Color.parseColor("#4D235C6E"),
                accentOrbColor = Color.parseColor("#3225E69B")
            ),
            // 玫红霓虹演播室
            AmbientPalette(
                baseColor = Color.parseColor("#09070C"),
                leftGlowColor = Color.parseColor("#58A62448"),
                rightGlowColor = Color.parseColor("#4E2C4F88"),
                accentOrbColor = Color.parseColor("#34FE2C55")
            ),
            // 琥珀微光音乐厅
            AmbientPalette(
                baseColor = Color.parseColor("#08080A"),
                leftGlowColor = Color.parseColor("#60986D24"),
                rightGlowColor = Color.parseColor("#523C5A34"),
                accentOrbColor = Color.parseColor("#30E8C266")
            )
        )

        fun paletteForChannel(seed: String): AmbientPalette {
            if (seed.isBlank()) return CURATED_PALETTES[0]
            val idx = abs(seed.hashCode()) % CURATED_PALETTES.size
            return CURATED_PALETTES[idx]
        }

        fun isPortraitVideo(
            width: Int,
            height: Int,
            pixelWidthHeightRatio: Float = 1f
        ): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = if (pixelWidthHeightRatio > 0f && pixelWidthHeightRatio.isFinite()) {
                pixelWidthHeightRatio
            } else {
                1f
            }
            val effectiveWidth = width * ratio
            return height > effectiveWidth * 1.04f
        }

        internal fun renderProceduralBitmap(seed: String): Bitmap {
            val palette = paletteForChannel(seed)
            val bmp = Bitmap.createBitmap(TEX_WIDTH, TEX_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val w = TEX_WIDTH.toFloat()
            val h = TEX_HEIGHT.toFloat()

            // 1. 深邃剧场底色
            canvas.drawColor(palette.baseColor)

            // 2. 左侧聚光柔光晕（映射竖屏视频左翼留白区）
            val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.16f,
                    h * 0.42f,
                    w * 0.42f,
                    intArrayOf(palette.leftGlowColor, multiplyAlpha(palette.leftGlowColor, 0.35f), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, leftPaint)

            // 3. 右侧对冲氛围光晕（映射竖屏视频右翼留白区）
            val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.85f,
                    h * 0.58f,
                    w * 0.44f,
                    intArrayOf(palette.rightGlowColor, multiplyAlpha(palette.rightGlowColor, 0.35f), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, rightPaint)

            // 4. 顶部舞台微光光束 + 柔和虚化光斑
            val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    w * 0.08f,
                    0f,
                    w * 0.92f,
                    h,
                    intArrayOf(
                        Color.TRANSPARENT,
                        multiplyAlpha(palette.accentOrbColor, 0.42f),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, beamPaint)

            drawSoftOrb(canvas, w * 0.12f, h * 0.22f, w * 0.14f, palette.accentOrbColor)
            drawSoftOrb(canvas, w * 0.89f, h * 0.28f, w * 0.12f, palette.leftGlowColor)
            drawSoftOrb(canvas, w * 0.82f, h * 0.78f, w * 0.16f, palette.accentOrbColor)

            // 5. 中心舞台压暗暗角，让居中 9:16 SurfaceView 边缘自然融进暗背景中，且避免外围过亮抢眼
            val centerFeatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    w,
                    0f,
                    intArrayOf(
                        Color.parseColor("#5505060A"),
                        Color.TRANSPARENT,
                        Color.parseColor("#9906070B"),
                        Color.parseColor("#9906070B"),
                        Color.TRANSPARENT,
                        Color.parseColor("#5505060A")
                    ),
                    floatArrayOf(0f, 0.22f, 0.34f, 0.66f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, centerFeatherPaint)

            // 6. 上下微暗边，确保顶部提示胶囊与底部字幕对比度始终清晰
            val verticalVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    h,
                    intArrayOf(
                        Color.parseColor("#6605060A"),
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.parseColor("#7705060A")
                    ),
                    floatArrayOf(0f, 0.18f, 0.80f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, verticalVignettePaint)

            return bmp
        }

        private fun drawSoftOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx,
                    cy,
                    radius,
                    intArrayOf(color, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, radius, orbPaint)
        }

        private fun multiplyAlpha(color: Int, factor: Float): Int {
            val newAlpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
            return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()
    private var backdropBitmap: Bitmap? = null
    private var currentSeed: String = ""
    private var breathAnimator: ValueAnimator? = null
    private var breathFactor: Float = 1.0f

    init {
        visibility = GONE
    }

    fun setChannelSeed(seed: String) {
        if (seed == currentSeed && backdropBitmap != null) return
        currentSeed = seed
        val old = backdropBitmap
        backdropBitmap = renderProceduralBitmap(seed)
        if (old != null && old !== backdropBitmap && !old.isRecycled) {
            old.recycle()
        }
        invalidate()
    }

    fun setBackdropActive(active: Boolean, channelSeed: String = currentSeed) {
        if (active) {
            setChannelSeed(channelSeed)
            if (visibility != VISIBLE) {
                alpha = 0f
                visibility = VISIBLE
                animate().cancel()
                animate().alpha(1f).setDuration(240L).start()
            }
            startBreathing()
        } else {
            stopBreathing()
            animate().cancel()
            visibility = GONE
        }
    }

    fun onHostStarted() {
        if (visibility == VISIBLE) {
            startBreathing()
        }
    }

    fun onHostStopped() {
        stopBreathing()
    }

    private fun startBreathing() {
        if (breathAnimator?.isRunning == true) return
        breathAnimator = ValueAnimator.ofFloat(0.88f, 1.0f).apply {
            duration = 5500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                breathFactor = anim.animatedValue as Float
                bitmapPaint.alpha = (breathFactor * 255).toInt().coerceIn(200, 255)
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathing() {
        breathAnimator?.cancel()
        breathAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = backdropBitmap ?: return
        if (bmp.isRecycled) return
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        animate().cancel()
        backdropBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        backdropBitmap = null
        super.onDetachedFromWindow()
    }
}
