package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.engine.DouyinStreamResolver
import com.example.engine.LivePlayerEngine
import com.example.ui.ChangeUrlDialog
import com.example.ui.ClearCacheConfirmDialog
import com.example.ui.ControlBottomSheet
import com.example.ui.NativeLivePlayerOverlay
import com.example.ui.QuickRoomDialog
import com.example.ui.theme.*
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

    // 原生 Media3 ExoPlayer 直播引擎
    private lateinit var livePlayerEngine: LivePlayerEngine

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private var defaultMobileUserAgent: String = ""

    // 实时直播流横竖比标记
    private var isStreamPortrait: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = LivePreferences(this)
        livePlayerEngine = LivePlayerEngine(this)
        defaultMobileUserAgent = WebSettings.getDefaultUserAgent(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        requestAppAudioFocus()
        updateKeepScreenOn(preferences.keepScreenOn)

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

        var isCustomViewActive by remember { mutableStateOf(false) }
        var activeNativeRoomInfo by remember { mutableStateOf<DouyinStreamResolver.LiveRoomInfo?>(null) }

        var showBottomSheet by remember { mutableStateOf(false) }
        var showUrlDialog by remember { mutableStateOf(false) }
        var showRoomDialog by remember { mutableStateOf(false) }
        var showClearCacheDialog by remember { mutableStateOf(false) }

        // Back button handler
        BackHandler {
            when {
                activeNativeRoomInfo != null -> {
                    activeNativeRoomInfo = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                customView != null -> {
                    hideCustomViewInternal()
                    isCustomViewActive = false
                }
                showBottomSheet -> showBottomSheet = false
                showUrlDialog -> showUrlDialog = false
                showRoomDialog -> showRoomDialog = false
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

                        // 注入原生与前端通信桥梁，用于实时监测视频宽高比与流切换自愈
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onVideoRatioDetected(portrait: Boolean) {
                                isStreamPortrait = portrait
                            }

                            @JavascriptInterface
                            fun onStreamLineChanged() {
                                // 换线时主动解静音
                                post {
                                    healAudioAndStreamSilent()
                                }
                            }
                        }, "AndroidLiveBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                isPageError = false
                                if (url != null) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                pageTitle = view?.title ?: ""
                                // 页面加载完成后注入军工级流媒体声画保活与解静音补丁
                                injectLiveStabilizerScript(view)
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
                                // 放行同域名资源，提升安全基线并容错私有自建证书节点
                                val host = Uri.parse(error?.url ?: "").host ?: ""
                                if (host.contains("fuxiaoya.com") || host.contains("douyin.com") || host.contains("bytevod.com")) {
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

                                // 智能全屏横竖屏决策
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

            // Top Linear Progress Bar
            if (isLoading && loadProgress in 1..99 && !isCustomViewActive && activeNativeRoomInfo == null) {
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
            if (isPageError && !isCustomViewActive && activeNativeRoomInfo == null) {
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

            // Floating Control Pill (Normal Mode)
            if (!isCustomViewActive && activeNativeRoomInfo == null) {
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
            if (isCustomViewActive) {
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

            // 原生 ExoPlayer 直播浮层
            activeNativeRoomInfo?.let { roomInfo ->
                NativeLivePlayerOverlay(
                    roomInfo = roomInfo,
                    playerEngine = livePlayerEngine,
                    onClose = {
                        activeNativeRoomInfo = null
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                )
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
                    showRoomDialog = true
                },
                onOpenUrlDialog = {
                    showBottomSheet = false
                    showUrlDialog = true
                },
                onOpenQuickRoomDialog = {
                    showBottomSheet = false
                    showRoomDialog = true
                },
                onClearCache = {
                    showBottomSheet = false
                    showClearCacheDialog = true
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

        if (showRoomDialog) {
            QuickRoomDialog(
                onDismiss = { showRoomDialog = false },
                onInjectRoom = { roomInput ->
                    showRoomDialog = false
                    injectRoomToWeb(roomInput)
                },
                onPlayInNativeEngine = { roomInput ->
                    showRoomDialog = false
                    launchNativePlayerForInput(roomInput) { info ->
                        activeNativeRoomInfo = info
                    }
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

    private fun launchNativePlayerForInput(
        input: String,
        onSuccess: (DouyinStreamResolver.LiveRoomInfo) -> Unit
    ) {
        Toast.makeText(this, "正在解析直播间直连信号...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val roomId = DouyinStreamResolver.extractRoomId(input) ?: input
            val roomInfo = DouyinStreamResolver.resolveLiveRoom(roomId)
            if (roomInfo != null && roomInfo.streams.isNotEmpty()) {
                val firstKey = roomInfo.streams.keys.first()
                val firstUrl = roomInfo.streams[firstKey]!!
                // 核心：无缝切换线路并强制开启播放与音量
                livePlayerEngine.switchStreamSource(firstUrl, firstKey)
                onSuccess(roomInfo)
            } else {
                Toast.makeText(this@MainActivity, "无法获取该直播间流地址或当前未开播", Toast.LENGTH_LONG).show()
            }
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
     * 军工级流媒体稳定性与声画自愈脚本
     */
    private fun injectLiveStabilizerScript(wv: WebView?) {
        val script = """
            (function() {
                if (window.__douyin_live_stabilizer_injected) return;
                window.__douyin_live_stabilizer_injected = true;

                function notifyDimensions() {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.videoWidth > 0 && v.videoHeight > 0) {
                        var isPortrait = (v.videoHeight >= v.videoWidth);
                        if (window.AndroidLiveBridge && window.AndroidLiveBridge.onVideoRatioDetected) {
                            window.AndroidLiveBridge.onVideoRatioDetected(isPortrait);
                        }
                    }
                }

                function autoUnmute() {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v) {
                        if (v.muted) {
                            v.muted = false;
                        }
                        var btn = document.getElementById('unmuteBtn');
                        if (btn && !btn.hidden) {
                            btn.click();
                        }
                    }
                }

                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        setTimeout(autoUnmute, 50);
                        setTimeout(notifyDimensions, 100);
                    }
                }, true);

                document.addEventListener('loadedmetadata', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        notifyDimensions();
                        setTimeout(autoUnmute, 100);
                    }
                }, true);

                document.addEventListener('volumechange', function(e) {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && v.muted) {
                        setTimeout(autoUnmute, 150);
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
                    setTimeout(autoUnmute, 300);
                    setTimeout(autoUnmute, 800);
                    setTimeout(autoUnmute, 1500);
                }

                var protoSel = document.getElementById('protoSel');
                var qualSel = document.getElementById('qualSel');
                if (protoSel) protoSel.addEventListener('change', onLineChange);
                if (qualSel) qualSel.addEventListener('change', onLineChange);

                setInterval(function() {
                    var v = document.getElementById('video') || document.querySelector('video');
                    if (v && !v.paused && v.muted) {
                        autoUnmute();
                    }
                    notifyDimensions();
                }, 1500);
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
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = if (isDesktop) desktopUserAgent else defaultMobileUserAgent

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onDestroy() {
        livePlayerEngine.release()
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
