package com.example.tv.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 单解码双层直出播放器舞台（OpenGL ES 2.0）：
 * - 只需 **1 个 ExoPlayer** + **1 个 Surface**，零双路解码开销
 * - 竖屏流且开启「动态背景」时：
 *   1. 底层（ZOOM / cover）：同一路视频清晰放大铺满全屏（默认 0 模糊、0 压暗，绝对清晰可辨；开启磨砂时使用 ≤5px 轻磨砂）
 *   2. 顶层（FIT / contain）：同一路视频严格居中完整清晰呈现
 * - 横屏流或关闭「动态背景」时：仅绘制纯黑底 + 顶层 FIT 画面
 */
class AmbientVideoStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "AmbientVideoStage"

        private val VERTEX_COORDS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_CLEAR = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // 轻磨砂片元着色器（半径 ≤5px，仅在用户开启磨砂时用于背景层，支持自动降级）
        private const val FRAGMENT_SHADER_BLUR = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uBlurOffset;
            void main() {
                vec2 uv = clamp(vTexCoord, vec2(0.005), vec2(0.995));
                vec4 sum = texture2D(sTexture, uv) * 0.20;
                sum += texture2D(sTexture, clamp(uv + vec2(-uBlurOffset.x, 0.0), vec2(0.005), vec2(0.995))) * 0.12;
                sum += texture2D(sTexture, clamp(uv + vec2( uBlurOffset.x, 0.0), vec2(0.005), vec2(0.995))) * 0.12;
                sum += texture2D(sTexture, clamp(uv + vec2(0.0, -uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.12;
                sum += texture2D(sTexture, clamp(uv + vec2(0.0,  uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.12;
                sum += texture2D(sTexture, clamp(uv + vec2(-uBlurOffset.x, -uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.08;
                sum += texture2D(sTexture, clamp(uv + vec2( uBlurOffset.x, -uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.08;
                sum += texture2D(sTexture, clamp(uv + vec2(-uBlurOffset.x,  uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.08;
                sum += texture2D(sTexture, clamp(uv + vec2( uBlurOffset.x,  uBlurOffset.y), vec2(0.005), vec2(0.995))) * 0.08;
                gl_FragColor = sum;
            }
        """
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameLock = Any()

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(VERTEX_COORDS)
            position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(TEX_COORDS)
            position(0)
        }

    private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile
    private var videoSurface: Surface? = null
    @Volatile
    private var attachedPlayer: ExoPlayer? = null

    @Volatile
    private var frameAvailable = false
    @Volatile
    private var hasRenderedFrame = false

    @Volatile
    private var viewWidth = 1
    @Volatile
    private var viewHeight = 1
    @Volatile
    private var videoWidth = 0
    @Volatile
    private var videoHeight = 0

    @Volatile
    var dynamicBgEnabled: Boolean = true
        private set

    @Volatile
    var blurBgEnabled: Boolean = false
        private set

    var onFirstFrameRendered: (() -> Unit)? = null

    private var clearProgram = 0
    private var blurProgram = 0

    // clearProgram locations
    private var clearPosLoc = -1
    private var clearTexLoc = -1
    private var clearScaleLoc = -1
    private var clearTexMatrixLoc = -1
    private var clearSamplerLoc = -1

    // blurProgram locations
    private var blurPosLoc = -1
    private var blurTexLoc = -1
    private var blurScaleLoc = -1
    private var blurTexMatrixLoc = -1
    private var blurSamplerLoc = -1
    private var blurOffsetLoc = -1

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBackgroundConfig(enabled: Boolean, blur: Boolean) {
        dynamicBgEnabled = enabled
        blurBgEnabled = blur
        requestRender()
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            requestRender()
        }
    }

    fun attachPlayer(player: ExoPlayer?) {
        attachedPlayer = player
        val s = videoSurface
        if (player != null && s != null && s.isValid) {
            player.setVideoSurface(s)
        }
        requestRender()
    }

    fun detachPlayer(player: ExoPlayer?) {
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
        val s = videoSurface
        if (player != null) {
            try {
                if (s != null) player.clearVideoSurface(s) else player.clearVideoSurface()
            } catch (_: Exception) {}
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val p = attachedPlayer
        val s = videoSurface
        if (p != null) {
            try {
                if (s != null) p.clearVideoSurface(s) else p.clearVideoSurface()
            } catch (_: Exception) {}
        }
        releaseGlSurface()
        super.surfaceDestroyed(holder)
    }

    private fun releaseGlSurface() {
        synchronized(frameLock) {
            frameAvailable = false
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
            } catch (_: Exception) {}
            surfaceTexture = null
            try {
                videoSurface?.release()
            } catch (_: Exception) {}
            videoSurface = null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        clearProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_CLEAR)
        if (clearProgram != 0) {
            clearPosLoc = GLES20.glGetAttribLocation(clearProgram, "aPosition")
            clearTexLoc = GLES20.glGetAttribLocation(clearProgram, "aTexCoord")
            clearScaleLoc = GLES20.glGetUniformLocation(clearProgram, "uScale")
            clearTexMatrixLoc = GLES20.glGetUniformLocation(clearProgram, "uTexMatrix")
            clearSamplerLoc = GLES20.glGetUniformLocation(clearProgram, "sTexture")
        }

        blurProgram = try {
            buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_BLUR)
        } catch (t: Throwable) {
            Log.w(TAG, "轻磨砂着色器不可用，自动降级清晰 ZOOM", t)
            0
        }
        if (blurProgram != 0) {
            blurPosLoc = GLES20.glGetAttribLocation(blurProgram, "aPosition")
            blurTexLoc = GLES20.glGetAttribLocation(blurProgram, "aTexCoord")
            blurScaleLoc = GLES20.glGetUniformLocation(blurProgram, "uScale")
            blurTexMatrixLoc = GLES20.glGetUniformLocation(blurProgram, "uTexMatrix")
            blurSamplerLoc = GLES20.glGetUniformLocation(blurProgram, "sTexture")
            blurOffsetLoc = GLES20.glGetUniformLocation(blurProgram, "uBlurOffset")
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        releaseGlSurface()
        createGlSurfaceAndBindPlayer()
    }

    private fun createGlSurfaceAndBindPlayer() {
        if (oesTexId == 0) {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            oesTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val st = SurfaceTexture(oesTexId)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) {
                frameAvailable = true
            }
            requestRender()
        }
        val surf = Surface(st)
        synchronized(frameLock) {
            surfaceTexture = st
            videoSurface = surf
            hasRenderedFrame = false
        }

        mainHandler.post {
            attachedPlayer?.let { p ->
                if (surf.isValid) {
                    p.setVideoSurface(surf)
                }
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        val needsSurface = synchronized(frameLock) {
            surfaceTexture == null || videoSurface?.isValid != true
        }
        if (needsSurface) {
            releaseGlSurface()
            createGlSurfaceAndBindPlayer()
        }
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        var gotFirstFrame = false
        synchronized(frameLock) {
            val st = surfaceTexture ?: return
            if (frameAvailable) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix)
                    frameAvailable = false
                    if (!hasRenderedFrame) {
                        hasRenderedFrame = true
                        gotFirstFrame = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateTexImage failed", e)
                }
            }
        }

        if (gotFirstFrame) {
            onFirstFrameRendered?.let { cb -> mainHandler.post(cb) }
        }

        if (!hasRenderedFrame || clearProgram == 0) return

        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val viewAspect = vw / vh
        val vidW = if (videoWidth > 0) videoWidth.toFloat() else vw
        val vidH = if (videoHeight > 0) videoHeight.toFloat() else vh
        val videoAspect = vidW / vidH

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

        // 1. 若开启动态背景且内容未占满全屏（如竖屏直播），先绘制铺满底层的动态背景（无压暗）
        val hasBars = kotlin.math.abs(videoAspect - viewAspect) > 0.02f
        if (dynamicBgEnabled && hasBars) {
            val (bgScaleX, bgScaleY) = if (videoAspect < viewAspect) {
                1.0f to (viewAspect / videoAspect)
            } else {
                (videoAspect / viewAspect) to 1.0f
            }

            if (blurBgEnabled && blurProgram != 0) {
                drawPass(
                    program = blurProgram,
                    posLoc = blurPosLoc,
                    texLoc = blurTexLoc,
                    scaleLoc = blurScaleLoc,
                    texMatrixLoc = blurTexMatrixLoc,
                    samplerLoc = blurSamplerLoc,
                    scaleX = bgScaleX * 1.05f,
                    scaleY = bgScaleY * 1.05f,
                    blurOffsetLoc = blurOffsetLoc
                )
            } else {
                drawPass(
                    program = clearProgram,
                    posLoc = clearPosLoc,
                    texLoc = clearTexLoc,
                    scaleLoc = clearScaleLoc,
                    texMatrixLoc = clearTexMatrixLoc,
                    samplerLoc = clearSamplerLoc,
                    scaleX = bgScaleX,
                    scaleY = bgScaleY,
                    blurOffsetLoc = -1
                )
            }
        }

        // 2. 再绘制顶层清晰主画面（严格 FIT 等比例居中，绝不模糊、绝不拉伸）
        val (fgScaleX, fgScaleY) = if (videoAspect < viewAspect) {
            (videoAspect / viewAspect) to 1.0f
        } else {
            1.0f to (viewAspect / videoAspect)
        }
        drawPass(
            program = clearProgram,
            posLoc = clearPosLoc,
            texLoc = clearTexLoc,
            scaleLoc = clearScaleLoc,
            texMatrixLoc = clearTexMatrixLoc,
            samplerLoc = clearSamplerLoc,
            scaleX = fgScaleX,
            scaleY = fgScaleY,
            blurOffsetLoc = -1
        )
    }

    private fun drawPass(
        program: Int,
        posLoc: Int,
        texLoc: Int,
        scaleLoc: Int,
        texMatrixLoc: Int,
        samplerLoc: Int,
        scaleX: Float,
        scaleY: Float,
        blurOffsetLoc: Int
    ) {
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glUniform2f(scaleLoc, scaleX, scaleY)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1i(samplerLoc, 0)

        if (blurOffsetLoc >= 0) {
            // 约 4~5px 轻磨砂偏移，保持动态与轮廓清晰可辨
            GLES20.glUniform2f(blurOffsetLoc, 0.004f, 0.004f)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "GL link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "GL compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
