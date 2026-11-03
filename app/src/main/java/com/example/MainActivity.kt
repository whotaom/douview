package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.LivePreferences
import com.example.ui.ChangeUrlDialog
import com.example.ui.ClearCacheConfirmDialog
import com.example.ui.ControlBottomSheet
import com.example.ui.UpdateDialog
import com.example.ui.theme.*
import com.example.util.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var preferences: LivePreferences
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var customViewContainer: FrameLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private var defaultMobileUserAgent: String = ""

    // 实时直播流横竖比标记与视频位置探测 (用于画中画无缝贴合裁剪)
    private var isStreamPortrait: Boolean = true
    private var videoSourceRect: Rect? = null
    // 画中画模式状态
    private val pipModeState = mutableStateOf(false)
    // 电视遥控器菜单键回调
    private var toggleBottomSheetAction: (() -> Unit)? = null

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeState.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            webView?.onResume()
            val js = """
                (function() {
                    if (window.__enterPipIsolation) window.__enterPipIsolation();
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.paused) v.play().catch(function(){});
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        } else {
            val js = """
                (function() {
                    if (window.__exitPipIsolation) window.__exitPipIsolation();
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.paused) v.play().catch(function(){});
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
            webView?.post {
                webView?.setInitialScale(0)
                webView?.requestLayout()
                webView?.invalidate()
            }
            webView?.postDelayed({
                webView?.evaluateJavascript(
                    "(function() { window.scrollTo(0, 0); window.dispatchEvent(new Event('resize')); })();",
                    null
                )
                updatePipParams()
            }, 300)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipModeSafely()
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val ratio = if (isStreamPortrait) Rational(9, 16) else Rational(16, 9)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                videoSourceRect?.let { r ->
                    if (r.width() > 10 && r.height() > 10) {
                        builder.setSourceRectHint(r)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(true)
                    builder.setSeamlessResizeEnabled(true)
                }
                setPictureInPictureParams(builder.build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun enterPipModeSafely(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 军工级无缝画中画：在进入前立即触发网页端纯视频隔离，确保捕捉帧和视口仅包含直播视频
            webView?.evaluateJavascript(
                "(function() { if (window.__enterPipIsolation) window.__enterPipIsolation(); })();",
                null
            )
            return try {
                val ratio = if (isStreamPortrait) Rational(9, 16) else Rational(16, 9)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                videoSourceRect?.let { r ->
                    if (r.width() > 10 && r.height() > 10) {
                        builder.setSourceRectHint(r)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(true)
                    builder.setSeamlessResizeEnabled(true)
                }
                val entered = enterPictureInPictureMode(builder.build())
                if (!entered) {
                    webView?.evaluateJavascript(
                        "(function() { if (window.__exitPipIsolation) window.__exitPipIsolation(); })();",
                        null
                    )
                }
                entered
            } catch (e: Exception) {
                e.printStackTrace()
                webView?.evaluateJavascript(
                    "(function() { if (window.__exitPipIsolation) window.__exitPipIsolation(); })();",
                    null
                )
                false
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 手机状态栏可见色保障 (暗色背景下强制白色状态栏文字与图标，杜绝隐形)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        preferences = LivePreferences(this)
        defaultMobileUserAgent = WebSettings.getDefaultUserAgent(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        requestAppAudioFocus()
        updateKeepScreenOn(preferences.keepScreenOn)
        checkAndPerformAutoCacheCleanup()

        fileChooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data?.data
                    val uris = if (uri != null) arrayOf(uri) else null
                    fileUploadCallback?.onReceiveValue(uris)
                } else {
                    fileUploadCallback?.onReceiveValue(null)
                }
                fileUploadCallback = null
            }

        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }

    /**
     * 自动定期巡检与缓存清理守护：
     * 每24小时自动巡检并清理过期临时网络分片，在IO线程平滑执行，保持应用轻盈疾速
     */
    private fun checkAndPerformAutoCacheCleanup() {
        if (preferences.shouldAutoClearCache()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    cacheDir?.listFiles()?.forEach { file ->
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    codeCacheDir?.listFiles()?.forEach { file ->
                        try {
                            file.deleteRecursively()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    preferences.recordCacheCleared()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                launch(Dispatchers.Main) {
                    try {
                        webView?.clearCache(true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleBottomSheetAction?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun requestAppAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { /* maintain audio */ }
                    .build()
                audioFocusRequest = request
                audioManager?.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MainAppScreen() {
        val isInPip by pipModeState
        var currentUrl by remember { mutableStateOf(preferences.targetUrl) }
        var pageTitle by remember { mutableStateOf("") }
        var loadProgress by remember { mutableStateOf(0) }
        var isLoading by remember { mutableStateOf(true) }
        var isPageError by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }

        var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOn) }
        var isDesktopUa by remember { mutableStateOf(preferences.isDesktopUa) }
        var forceLandscape by remember { mutableStateOf(preferences.forceLandscape) }
        var fullscreenMode by remember { mutableStateOf(preferences.fullscreenOrientationMode) }
        var autoClearCacheEnabled by remember { mutableStateOf(preferences.autoClearCacheEnabled) }

        var isCustomViewActive by remember { mutableStateOf(false) }

        var showBottomSheet by remember { mutableStateOf(false) }
        var showUrlDialog by remember { mutableStateOf(false) }
        var showClearCacheDialog by remember { mutableStateOf(false) }
        var showUpdateDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            toggleBottomSheetAction = { showBottomSheet = !showBottomSheet }
        }

        // Back button handler
        BackHandler {
            when {
                customView != null -> {
                    hideCustomViewInternal()
                    isCustomViewActive = false
                }
                showBottomSheet -> showBottomSheet = false
                showUpdateDialog -> showUpdateDialog = false
                showUrlDialog -> showUrlDialog = false
                showClearCacheDialog -> showClearCacheDialog = false
                webView?.canGoBack() == true -> {
                    webView?.goBack()
                }
                else -> {
                    finish()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
        ) {
            // Main WebView Container
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val root = FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#09090C"))
                    }

                    customViewContainer = FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        visibility = View.GONE
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }

                    val wv = WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.parseColor("#09090C"))
                        setupWebSettings(this, preferences.isDesktopUa)

                        // 注入原生与前端通信桥梁，用于实时监测视频宽高比、换线自愈与画中画联动接洽
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onVideoMetricsReported(left: Float, top: Float, right: Float, bottom: Float, vw: Int, vh: Int) {
                                runOnUiThread {
                                    val wv = webView ?: return@runOnUiThread
                                    if (pipModeState.value) return@runOnUiThread
                                    val loc = IntArray(2)
                                    wv.getLocationInWindow(loc)
                                    val density = resources.displayMetrics.density

                                    val l = loc[0] + (left * density).toInt()
                                    val t = loc[1] + (top * density).toInt()
                                    val r = loc[0] + (right * density).toInt()
                                    val b = loc[1] + (bottom * density).toInt()

                                    val winW = window.decorView.width
                                    val winH = window.decorView.height

                                    if (r > l && b > t && winW > 0 && winH > 0) {
                                        val clampedRect = Rect(
                                            l.coerceIn(0, winW),
                                            t.coerceIn(0, winH),
                                            r.coerceIn(0, winW),
                                            b.coerceIn(0, winH)
                                        )
                                        if (clampedRect.width() > 10 && clampedRect.height() > 10) {
                                            videoSourceRect = clampedRect
                                            if (vh > 0 && vw > 0) {
                                                isStreamPortrait = (vh > vw)
                                            }
                                            updatePipParams()
                                        }
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onVideoBoundsDetected(left: Float, top: Float, right: Float, bottom: Float) {
                                onVideoMetricsReported(left, top, right, bottom, 0, 0)
                            }

                            @JavascriptInterface
                            fun onVideoRatioDetected(portrait: Boolean) {
                                isStreamPortrait = portrait
                                runOnUiThread {
                                    updatePipParams()
                                }
                            }

                            @JavascriptInterface
                            fun onStreamLineChanged() {
                                // 换线时主动解静音与恢复流畅播放
                                post {
                                    healAudioAndStreamSilent()
                                }
                            }

                            @JavascriptInterface
                            fun requestPictureInPicture() {
                                post {
                                    enterPipModeSafely()
                                }
                            }

                            @JavascriptInterface
                            fun isPipSupported(): Boolean {
                                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            }
                        }, "AndroidLiveBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                isPageError = false
                                if (url != null) currentUrl = url
                                injectLiveStabilizerScript(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                pageTitle = view?.title ?: ""
                                // 页面加载完成后注入军工级流媒体声画保活与首帧自播解静音补丁
                                injectLiveStabilizerScript(view)
                                postDelayed({ healAudioAndStreamSilent() }, 200)
                                postDelayed({ healAudioAndStreamSilent() }, 800)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    isPageError = true
                                    errorMessage = error?.description?.toString() ?: "网络连接异常"
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                val host = Uri.parse(error?.url ?: "").host ?: ""
                                if (host.contains("fuxiaoya.com") || host.contains("whomao.com") || host.contains("douyin.com") || host.contains("bytevod.com")) {
                                    handler?.proceed()
                                } else {
                                    handler?.proceed()
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                if (newProgress >= 100) isLoading = false
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }

                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (customView != null) {
                                    onHideCustomView()
                                    return
                                }
                                customView = view
                                customViewCallback = callback
                                isCustomViewActive = true

                                // 智能全屏横竖屏决策：竖屏直播竖屏全屏，横屏直播横屏全屏
                                applyFullscreenOrientation(fullscreenMode)
                                setImmersiveMode(true)

                                visibility = View.GONE
                                customViewContainer.removeAllViews()
                                customViewContainer.addView(
                                    view,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                                customViewContainer.visibility = View.VISIBLE
                            }

                            override fun onHideCustomView() {
                                hideCustomViewInternal()
                                isCustomViewActive = false
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                fileUploadCallback?.onReceiveValue(null)
                                fileUploadCallback = filePathCallback
                                return try {
                                    val intent = fileChooserParams?.createIntent()
                                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                        }
                                    fileChooserLauncher.launch(intent)
                                    true
                                } catch (e: Exception) {
                                    fileUploadCallback?.onReceiveValue(null)
                                    fileUploadCallback = null
                                    false
                                }
                            }
                        }

                        loadUrl(preferences.targetUrl)
                    }

                    webView = wv
                    root.addView(wv)
                    root.addView(customViewContainer)
                    root
                }
            )

            // 极速防黑屏优雅过渡层 (在直播控制台加载完成前提供视觉过渡，杜绝黑屏)
            AnimatedVisibility(
                visible = isLoading && loadProgress < 90 && !isCustomViewActive && !isInPip,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(350))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF090A10),
                                    Color(0xFF050508)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(
                                            DouyinCyan.copy(alpha = 0.25f),
                                            DouyinRed.copy(alpha = 0.08f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .border(1.2.dp, DouyinCyan.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = DouyinCyan,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Text(
                            text = "抖眸流控 · 正在秒级接入直播流",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = "AI 智能信道连接中 · 音画同步加速",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DouyinCyan.copy(alpha = 0.12f),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(DouyinCyan.copy(alpha = 0.25f))
                            )
                        ) {
                            Text(
                                text = "极速硬件加速解码已就绪",
                                color = DouyinCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = DouyinCyan,
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            }

            // Top Linear Progress Bar
            if (isLoading && loadProgress in 1..99 && !isCustomViewActive && !isInPip) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                    color = DouyinRed,
                    trackColor = Color.Transparent
                )
            }

            // Error Overlay
            if (isPageError && !isCustomViewActive && !isInPip) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(DouyinRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = DouyinRed,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = "无法连接至中控服务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = if (errorMessage.isNotBlank()) errorMessage else "网络请求超时或目标服务不可达，请检查网络或切换服务器地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showUrlDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("更换地址", color = DouyinCyan)
                            }

                            Button(
                                onClick = {
                                    isPageError = false
                                    webView?.reload()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed)
                            ) {
                                Text("重新加载")
                            }
                        }
                    }
                }
            }

            // Top Status Bar Protective Scrim
            if (!isCustomViewActive && !isInPip) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(DarkBg.copy(alpha = 0.45f))
                        .statusBarsPadding()
                )
            }

            // Floating Control Pill (Normal Mode)
            if (!isCustomViewActive && !isInPip) {
                Surface(
                    onClick = { showBottomSheet = true },
                    shape = RoundedCornerShape(24.dp),
                    color = DarkSurface.copy(alpha = 0.88f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 10.dp, end = 12.dp)
                        .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(24.dp))
                        .focusable()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isPageError) DouyinRed else DouyinGreen)
                        )
                        Text(
                            text = "中控台",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "控制中心",
                            tint = DouyinCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Fullscreen Floating Control Bar (Overlay in Video Fullscreen)
            if (isCustomViewActive && !isInPip) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .border(0.8.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 一键旋转/横竖屏切换
                        IconButton(
                            onClick = {
                                val currentOrientation = requestedOrientation
                                requestedOrientation = if (currentOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                                    currentOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "旋转屏幕",
                                tint = DouyinCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 一键画中画浮窗
                        IconButton(
                            onClick = { enterPipModeSafely() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "画中画浮窗",
                                tint = DouyinCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 一键音画自愈
                        IconButton(
                            onClick = { healAudioAndStream() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "恢复声音",
                                tint = DouyinGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 退出全屏
                        IconButton(
                            onClick = {
                                hideCustomViewInternal()
                                isCustomViewActive = false
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "退出全屏",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom Sheet & Dialogs
        if (showBottomSheet) {
            ControlBottomSheet(
                currentUrl = currentUrl,
                pageTitle = pageTitle,
                keepScreenOn = keepScreenOn,
                isDesktopUa = isDesktopUa,
                forceLandscape = forceLandscape,
                fullscreenOrientationMode = fullscreenMode,
                onDismissRequest = { showBottomSheet = false },
                onReload = {
                    showBottomSheet = false
                    webView?.reload()
                },
                onKeepScreenOnToggle = { enabled ->
                    keepScreenOn = enabled
                    preferences.keepScreenOn = enabled
                    updateKeepScreenOn(enabled)
                },
                onDesktopUaToggle = { enabled ->
                    isDesktopUa = enabled
                    preferences.isDesktopUa = enabled
                    webView?.let { wv ->
                        setupWebSettings(wv, enabled)
                        wv.reload()
                    }
                },
                onOrientationToggle = {
                    val newLandscape = !forceLandscape
                    forceLandscape = newLandscape
                    preferences.forceLandscape = newLandscape
                    requestedOrientation = if (newLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                },
                onOrientationModeSelect = { mode ->
                    fullscreenMode = mode
                    preferences.fullscreenOrientationMode = mode
                },
                onHealAudioStream = {
                    healAudioAndStream()
                },
                onLaunchNativePlayer = {
                    showBottomSheet = false
                    showUrlDialog = true
                },
                onEnterPip = {
                    showBottomSheet = false
                    webView?.postDelayed({
                        enterPipModeSafely()
                    }, 120)
                },
                onOpenUrlDialog = {
                    showBottomSheet = false
                    showUrlDialog = true
                },
                onOpenQuickRoomDialog = {
                    showBottomSheet = false
                    showUrlDialog = true
                },
                onClearCache = {
                    showBottomSheet = false
                    showClearCacheDialog = true
                },
                onCheckUpdate = {
                    showBottomSheet = false
                    showUpdateDialog = true
                },
                onSwitchLine = { newUrl ->
                    preferences.targetUrl = newUrl
                    currentUrl = newUrl
                    showBottomSheet = false
                    webView?.loadUrl(newUrl)
                },
                autoClearCacheEnabled = autoClearCacheEnabled,
                onAutoClearCacheToggle = { enabled ->
                    autoClearCacheEnabled = enabled
                    preferences.autoClearCacheEnabled = enabled
                }
            )
        }

        if (showUpdateDialog) {
            UpdateDialog(
                onDismiss = { showUpdateDialog = false },
                onDownloadApk = { downloadUrl ->
                    showUpdateDialog = false
                    UpdateManager.startDownloadApk(this@MainActivity, downloadUrl)
                }
            )
        }

        if (showUrlDialog) {
            ChangeUrlDialog(
                currentUrl = preferences.targetUrl,
                onDismiss = { showUrlDialog = false },
                onConfirmUrl = { newUrl ->
                    preferences.targetUrl = newUrl
                    currentUrl = newUrl
                    showUrlDialog = false
                    webView?.loadUrl(newUrl)
                }
            )
        }

        if (showClearCacheDialog) {
            ClearCacheConfirmDialog(
                onDismiss = { showClearCacheDialog = false },
                onConfirm = {
                    showClearCacheDialog = false
                    webView?.apply {
                        clearCache(true)
                        clearFormData()
                        clearHistory()
                    }
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    Toast.makeText(this@MainActivity, "缓存与本地存储已清理", Toast.LENGTH_SHORT).show()
                    webView?.reload()
                }
            )
        }
    }

    private fun applyFullscreenOrientation(mode: Int) {
        when (mode) {
            1 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            2 -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            else -> {
                webView?.evaluateJavascript(
                    """
                    (function() {
                        var v = document.getElementById('video') || document.querySelector('video');
                        if (v && v.videoWidth > 0 && v.videoHeight > 0) {
                            return (v.videoHeight >= v.videoWidth) ? 'portrait' : 'landscape';
                        }
                        return 'unknown';
                    })();
                    """.trimIndent()
                ) { result ->
                    val cleanResult = result?.replace("\"", "") ?: "unknown"
                    requestedOrientation = if (cleanResult == "portrait" || isStreamPortrait) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else if (cleanResult == "landscape") {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            }
        }
    }

    /**
     * 军工级流媒体稳定性、网页端画中画接洽与首次加载自启自愈脚本
     */
    private fun injectLiveStabilizerScript(wv: WebView?) {
        val script = """
            (function() {
                // 1. 网页端原生与前端画中画接洽融合（保障画中画只展示纯净直播视频，杜绝网页背景干扰）
                try {
                    document.pictureInPictureEnabled = true;
                } catch(e) {}

                window.__enterPipIsolation = function() {
                    var style = document.getElementById('__pip_pure_video_style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = '__pip_pure_video_style';
                        style.innerHTML = `
                            html.android-pip-mode,
                            body.android-pip-mode {
                                background: #000000 !important;
                                overflow: hidden !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                width: 100vw !important;
                                height: 100vh !important;
                            }
                            body.android-pip-mode .banner,
                            body.android-pip-mode .controls,
                            body.android-pip-mode .playlist,
                            body.android-pip-mode .toasts,
                            body.android-pip-mode .mask,
                            body.android-pip-mode .unmute,
                            body.android-pip-mode dialog,
                            body.android-pip-mode header,
                            body.android-pip-mode aside,
                            body.android-pip-mode noscript,
                            body.android-pip-mode .stage::before {
                                display: none !important;
                                opacity: 0 !important;
                                visibility: hidden !important;
                                pointer-events: none !important;
                            }
                            body.android-pip-mode .workspace,
                            body.android-pip-mode .player-col,
                            body.android-pip-mode .stage,
                            body.android-pip-mode #stage {
                                position: fixed !important;
                                top: 0 !important;
                                left: 0 !important;
                                right: 0 !important;
                                bottom: 0 !important;
                                width: 100vw !important;
                                height: 100vh !important;
                                max-width: 100vw !important;
                                max-height: 100vh !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                border: none !important;
                                border-radius: 0 !important;
                                box-shadow: none !important;
                                background: #000000 !important;
                                overflow: hidden !important;
                                aspect-ratio: auto !important;
                                z-index: 99999 !important;
                            }
                            body.android-pip-mode video,
                            body.android-pip-mode #video {
                                display: block !important;
                                visibility: visible !important;
                                opacity: 1 !important;
                                position: absolute !important;
                                top: 0 !important;
                                left: 0 !important;
                                width: 100% !important;
                                height: 100% !important;
                                max-width: 100% !important;
                                max-height: 100% !important;
                                margin: 0 !important;
                                padding: 0 !important;
                                object-fit: contain !important;
                                background: #000000 !important;
                                z-index: 100000 !important;
                            }
                        `;
                        document.head.appendChild(style);
                    }
                    document.documentElement.classList.add('android-pip-mode');
                    document.body.classList.add('android-pip-mode');
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused) v.play().catch(function(){});
                    }
                };

                window.__exitPipIsolation = function() {
                    document.documentElement.classList.remove('android-pip-mode');
                    document.body.classList.remove('android-pip-mode');
                    var style = document.getElementById('__pip_pure_video_style');
                    if (style && style.parentNode) {
                        style.parentNode.removeChild(style);
                    }

                    // 彻底重置页面缩放、视口与宽度，杜绝退出画中画后网页内容超出 APP 视窗
                    try {
                        document.documentElement.style.width = '100vw';
                        document.documentElement.style.maxWidth = '100vw';
                        document.documentElement.style.overflowX = 'hidden';
                        document.body.style.width = '100vw';
                        document.body.style.maxWidth = '100vw';
                        document.body.style.overflowX = 'hidden';

                        var meta = document.querySelector('meta[name="viewport"]');
                        if (meta) {
                            var oldContent = meta.getAttribute('content');
                            meta.setAttribute('content', 'width=device-width,initial-scale=1.0,maximum-scale=1.0,minimum-scale=1.0,user-scalable=no,viewport-fit=cover');
                            setTimeout(function() {
                                meta.setAttribute('content', oldContent || 'width=device-width,initial-scale=1,viewport-fit=cover');
                                document.documentElement.style.removeProperty('width');
                                document.documentElement.style.removeProperty('max-width');
                                document.documentElement.style.removeProperty('overflow-x');
                                document.body.style.removeProperty('width');
                                document.body.style.removeProperty('max-width');
                                document.body.style.removeProperty('overflow-x');
                            }, 150);
                        }
                        window.scrollTo(0, 0);
                        document.documentElement.style.zoom = '1';
                        document.body.style.zoom = '1';
                        window.dispatchEvent(new Event('resize'));
                    } catch(err) {}

                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.paused) v.play().catch(function(){});
                };

                if (window.HTMLVideoElement && HTMLVideoElement.prototype && !HTMLVideoElement.prototype.__douyin_pip_hooked) {
                    HTMLVideoElement.prototype.__douyin_pip_hooked = true;
                    var origRequestPip = HTMLVideoElement.prototype.requestPictureInPicture;
                    HTMLVideoElement.prototype.requestPictureInPicture = function() {
                        if (window.AndroidLiveBridge && window.AndroidLiveBridge.requestPictureInPicture) {
                            window.AndroidLiveBridge.requestPictureInPicture();
                            return Promise.resolve();
                        }
                        if (origRequestPip) return origRequestPip.apply(this, arguments);
                        return Promise.reject("Native PiP bridge triggered");
                    };
                }

                // 全局事件拦截：页面内任意画中画按钮点击及快捷键无缝接洽原生系统画中画
                if (!window.__douyin_pip_listener_added) {
                    window.__douyin_pip_listener_added = true;
                    document.addEventListener('click', function(e) {
                        var target = e.target;
                        while (target && target !== document.body) {
                            var id = target.id || '';
                            var title = (target.getAttribute && target.getAttribute('title')) || '';
                            var aria = (target.getAttribute && target.getAttribute('aria-label')) || '';
                            var cls = target.className || '';
                            var str = (id + ' ' + title + ' ' + aria + ' ' + cls).toLowerCase();
                            if (str.indexOf('pip') !== -1 || str.indexOf('画中画') !== -1 || str.indexOf('picture-in-picture') !== -1) {
                                if (window.AndroidLiveBridge && window.AndroidLiveBridge.requestPictureInPicture) {
                                    window.AndroidLiveBridge.requestPictureInPicture();
                                    e.preventDefault();
                                    e.stopPropagation();
                                    return;
                                }
                            }
                            target = target.parentElement;
                        }
                    }, true);

                    window.addEventListener('keydown', function(e) {
                        if (e.key === 'p' || e.key === 'P') {
                            if (['INPUT', 'SELECT', 'TEXTAREA'].indexOf(document.activeElement?.tagName) === -1) {
                                if (window.AndroidLiveBridge && window.AndroidLiveBridge.requestPictureInPicture) {
                                    window.AndroidLiveBridge.requestPictureInPicture();
                                    e.preventDefault();
                                    e.stopPropagation();
                                }
                            }
                        }
                    }, true);
                }

                function notifyDimensions() {
                    if (document.body && document.body.classList.contains('android-pip-mode')) {
                        return;
                    }
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.videoWidth > 0 && v.videoHeight > 0) {
                        var vw = v.videoWidth;
                        var vh = v.videoHeight;
                        var isPortrait = (vh > vw);
                        if (window.AndroidLiveBridge && window.AndroidLiveBridge.onVideoRatioDetected) {
                            window.AndroidLiveBridge.onVideoRatioDetected(isPortrait);
                        }
                        var stage = document.getElementById('stage') || v;
                        var rect = stage.getBoundingClientRect();
                        if (window.AndroidLiveBridge && window.AndroidLiveBridge.onVideoMetricsReported) {
                            window.AndroidLiveBridge.onVideoMetricsReported(
                                rect.left,
                                rect.top,
                                rect.right,
                                rect.bottom,
                                vw,
                                vh
                            );
                        }
                    }
                }

                // 2. 首次载入极速首播与声画自愈，彻底杜绝一卡一卡与需切频道才播的假死问题
                function triggerImmediateAutoplayAndUnmute() {
                    var videos = document.querySelectorAll('video');
                    videos.forEach(function(v) {
                        v.setAttribute('playsinline', 'true');
                        v.setAttribute('webkit-playsinline', 'true');
                        v.setAttribute('x5-video-player-type', 'h5');
                        v.preload = 'auto';
                        if (v.muted) {
                            v.muted = false;
                        }
                        v.volume = 1.0;

                        if (v.paused) {
                            var p = v.play();
                            if (p && p.catch) {
                                p.catch(function() {
                                    // 若策略拦截带声首播，立即先静音解码拉起画面，并在150ms内无缝解静音
                                    v.muted = true;
                                    v.play().then(function() {
                                        setTimeout(function() {
                                            v.muted = false;
                                            v.volume = 1.0;
                                        }, 150);
                                    }).catch(function() {});
                                });
                            }
                        }
                    });

                    var unmuteBtn = document.getElementById('unmuteBtn');
                    if (unmuteBtn && !unmuteBtn.hidden) {
                        unmuteBtn.click();
                    }

                    var playBtn = document.querySelector('.xgplayer-start, .vjs-big-play-button, .play-btn');
                    if (playBtn && playBtn.offsetParent !== null) {
                        playBtn.click();
                    }
                }

                // 立即执行并多频段唤醒
                triggerImmediateAutoplayAndUnmute();
                setTimeout(triggerImmediateAutoplayAndUnmute, 100);
                setTimeout(triggerImmediateAutoplayAndUnmute, 300);
                setTimeout(triggerImmediateAutoplayAndUnmute, 800);
                setTimeout(triggerImmediateAutoplayAndUnmute, 1500);

                if (!window.__douyin_live_stabilizer_injected) {
                    window.__douyin_live_stabilizer_injected = true;

                    document.addEventListener('play', function(e) {
                        if (e.target && e.target.tagName === 'VIDEO') {
                            setTimeout(triggerImmediateAutoplayAndUnmute, 50);
                            setTimeout(notifyDimensions, 100);
                        }
                    }, true);

                    document.addEventListener('loadedmetadata', function(e) {
                        if (e.target && e.target.tagName === 'VIDEO') {
                            notifyDimensions();
                            setTimeout(triggerImmediateAutoplayAndUnmute, 100);
                        }
                    }, true);

                    document.addEventListener('volumechange', function(e) {
                        var v = document.getElementById('video') || document.querySelector('video');
                        if (v && v.muted) {
                            setTimeout(triggerImmediateAutoplayAndUnmute, 150);
                        }
                    }, true);

                    function onLineChange() {
                        var v = document.getElementById('video') || document.querySelector('video');
                        if (v) {
                            try {
                                if (v.src && v.src.startsWith('blob:')) {
                                    URL.revokeObjectURL(v.src);
                                }
                            } catch(err) {}
                        }
                        if (window.AndroidLiveBridge && window.AndroidLiveBridge.onStreamLineChanged) {
                            window.AndroidLiveBridge.onStreamLineChanged();
                        }
                        setTimeout(triggerImmediateAutoplayAndUnmute, 200);
                        setTimeout(triggerImmediateAutoplayAndUnmute, 600);
                        setTimeout(triggerImmediateAutoplayAndUnmute, 1200);
                    }

                    var protoSel = document.getElementById('protoSel');
                    var qualSel = document.getElementById('qualSel');
                    if (protoSel) protoSel.addEventListener('change', onLineChange);
                    if (qualSel) qualSel.addEventListener('change', onLineChange);

                    setInterval(function() {
                        var v = document.getElementById('video') || document.querySelector('video');
                        if (v) {
                            if (v.paused || v.muted) {
                                triggerImmediateAutoplayAndUnmute();
                            }
                            notifyDimensions();
                        }
                    }, 1500);
                }
            })();
        """.trimIndent()
        wv?.evaluateJavascript(script, null)
    }

    private fun healAudioAndStream() {
        val js = """
            (function() {
                try {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused) v.play();
                    }
                    var unmute = document.getElementById('unmuteBtn');
                    if (unmute && !unmute.hidden) unmute.click();
                } catch(e) {
                    console.error(e);
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js) {
            Toast.makeText(this@MainActivity, "已触发声画自愈与音量同步", Toast.LENGTH_SHORT).show()
        }
    }

    private fun healAudioAndStreamSilent() {
        val js = """
            (function() {
                try {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused) v.play();
                    }
                    var unmute = document.getElementById('unmuteBtn');
                    if (unmute && !unmute.hidden) unmute.click();
                } catch(e) {}
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    /**
     * 息屏再开时的网页直播流自愈检测：
     * 自动检测视频播放状态，若由于锁屏挂起导致假死，自动触发拉流恢复
     */
    private fun resumeLiveStreamOnScreenOn() {
        val js = """
            (function() {
                try {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused || v.readyState < 2) {
                            var retryBtn = document.getElementById('retryBtn');
                            var reloadBtn = document.getElementById('reloadBtn');
                            if (retryBtn && !retryBtn.hidden) {
                                retryBtn.click();
                            } else if (reloadBtn) {
                                reloadBtn.click();
                            } else {
                                v.play().catch(function(){});
                            }
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    private fun injectRoomToWeb(roomInput: String) {
        val sanitized = roomInput.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ")
        val js = """
            (function() {
                try {
                    var input = document.getElementById('q');
                    var form = document.getElementById('addForm');
                    if (input && form) {
                        input.value = '$sanitized';
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                    } else {
                        window.location.href = '${preferences.targetUrl}?room=' + encodeURIComponent('$sanitized');
                    }
                } catch(e) {
                    console.error(e);
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js) {
            Toast.makeText(this@MainActivity, "已发送至中控台解析", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideCustomViewInternal() {
        customView?.let { cv ->
            requestedOrientation = if (preferences.forceLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            setImmersiveMode(false)
            customViewContainer.removeView(cv)
            customViewContainer.visibility = View.GONE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            webView?.visibility = View.VISIBLE
        }
    }

    private fun setImmersiveMode(enable: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebSettings(wv: WebView, isDesktop: Boolean) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = if (isDesktop) desktopUserAgent else defaultMobileUserAgent

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode && pipModeState.value) {
                pipModeState.value = false
                webView?.evaluateJavascript(
                    "(function() { if (window.__exitPipIsolation) window.__exitPipIsolation(); })();",
                    null
                )
                webView?.post {
                    webView?.setInitialScale(0)
                    webView?.requestLayout()
                    webView?.invalidate()
                }
            }
        }
        webView?.onResume()
        resumeLiveStreamOnScreenOn()
        checkAndPerformAutoCacheCleanup()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // 画中画模式保持活动运行，绝不挂起 WebView，避免视频黑屏与音频中断
        } else {
            webView?.onPause()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        webView?.let { wv ->
            wv.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            wv.clearHistory()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
