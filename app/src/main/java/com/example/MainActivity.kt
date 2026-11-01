package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.net.http.SslError
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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.data.LivePreferences
import com.example.ui.ChangeUrlDialog
import com.example.ui.ClearCacheConfirmDialog
import com.example.ui.ControlBottomSheet
import com.example.ui.QuickRoomDialog
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private lateinit var preferences: LivePreferences
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var customViewContainer: FrameLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private var defaultMobileUserAgent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferences = LivePreferences(this)
        defaultMobileUserAgent = WebSettings.getDefaultUserAgent(this)

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

        var isCustomViewActive by remember { mutableStateOf(false) }

        var showBottomSheet by remember { mutableStateOf(false) }
        var showUrlDialog by remember { mutableStateOf(false) }
        var showRoomDialog by remember { mutableStateOf(false) }
        var showClearCacheDialog by remember { mutableStateOf(false) }

        // Back button handler
        BackHandler {
            when {
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
                                handler?.proceed()
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

                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
            if (isLoading && loadProgress in 1..99 && !isCustomViewActive) {
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
            if (isPageError && !isCustomViewActive) {
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

            // Floating Control Pill
            if (!isCustomViewActive) {
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
        }

        // Bottom Sheet & Dialogs
        if (showBottomSheet) {
            ControlBottomSheet(
                currentUrl = currentUrl,
                pageTitle = pageTitle,
                keepScreenOn = keepScreenOn,
                isDesktopUa = isDesktopUa,
                forceLandscape = forceLandscape,
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
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = if (isDesktop) desktopUserAgent else defaultMobileUserAgent
    }

    override fun onDestroy() {
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
