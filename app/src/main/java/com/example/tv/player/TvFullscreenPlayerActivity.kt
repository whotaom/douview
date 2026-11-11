package com.example.tv.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 电视大屏深度修复版全屏纯净播放 Activity
 * 针对电视盒子（如晶晨 Amlogic、瑞芯微 Rockchip、全志等）WebView 播放有声音黑屏问题进行了深度多重兜底：
 * 1. 动态 LayerType 策略切换：初次尝试 GPU 硬件加速，重试时自动降级切换为软件渲染 (LAYER_TYPE_SOFTWARE)；
 * 2. 多重 User-Agent 切换重试：规避不同流媒体站点对 TV 端特定 UA 的解码或格式降级限制；
 * 3. 激进式 JS 强力注入：解除静音自动开播、触发播放器内部播放按钮、隐藏 loading/解码遮罩层，同时抹除顶栏导航；
 * 4. 纯净沉浸式大屏全屏：彻底清除系统栏与冗余 UI，屏幕常亮，遥控器返回键干净销毁。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null

    private var currentUrl: String = ""
    private var retryCount = 0
    private val maxRetry = 4

    private val handler = Handler(Looper.getMainLooper())
    private val TIMEOUT_MS = 15_000L

    // 不同重试阶段的 User-Agent 候选策略
    private val userAgents = listOf(
        // 策略 0: 默认系统 WebView UA
        null,
        // 策略 1: 桌面 Chrome UA（很多站点给桌面端推送标准 H.264/MP4 流）
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        // 策略 2: 现代手机 Chrome UA（触发移动端原生全屏视频流）
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        // 策略 3: 兼容版 Android TV Chrome UA
        "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Safari/537.36"
    )

    private val timeoutRunnable = Runnable {
        doRetry("加载超时")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTrueFullscreen()

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        currentUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        // 遥控器返回键拦截
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })

        // 初始第 0 次尝试：优先使用硬件加速
        createAndLoadWebView(useSoftwareLayer = false)
    }

    private fun createAndLoadWebView(useSoftwareLayer: Boolean) {
        destroyWebView()

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)

            // 核心破黑屏关键：电视盒子 GPU 合成层失败时切换为软件渲染
            if (useSoftwareLayer) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            } else {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }

        rootContainer.addView(webView)
        setupWebView(webView!!)
        loadWithCurrentStrategy()
    }

    @Suppress("DEPRECATION")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false      // 允许无需手势自动播放
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE         // 避免缓存残留旧的黑屏状态
            loadsImagesAutomatically = true
            blockNetworkImage = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        // 根据当前重试轮次切换 UA
        val ua = userAgents.getOrNull(retryCount)
        if (ua != null) {
            wv.settings.userAgentString = ua
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.removeCallbacks(timeoutRunnable)

                // 注入强力视频控制与界面纯净化 JS
                forceVideoPlay(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    handler.removeCallbacks(timeoutRunnable)
                    doRetry("主页面加载错误")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                handler.removeCallbacks(timeoutRunnable)
                doRetry("加载错误: $description")
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            // 空实现保证网页正常运行全屏逻辑
        }
    }

    private fun forceVideoPlay(view: WebView?) {
        val js = """
            (function() {
                // 1. 彻底抹除网页顶部「快看」、说明与操作栏，清除遮挡
                var cleanStyle = document.getElementById('tv-fullscreen-clean-style');
                if (!cleanStyle) {
                    cleanStyle = document.createElement('style');
                    cleanStyle.id = 'tv-fullscreen-clean-style';
                    cleanStyle.innerHTML = `
                        header, nav, .header, .nav, .top-bar, .navbar, .toolbar, .menu,
                        .notice, .tip, .btn, [class*="nav"], [class*="header"], [class*="title"], [class*="toolbar"] {
                            display: none !important;
                        }
                        body, html {
                            width: 100vw !important;
                            height: 100vh !important;
                            overflow: hidden !important;
                            background: #000 !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                    `;
                    document.head.appendChild(cleanStyle);
                }

                // 2. 强制所有 video 标签样式拉伸全屏并触发开播
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    v.style.width = '100vw';
                    v.style.height = '100vh';
                    v.style.maxWidth = '100vw';
                    v.style.maxHeight = '100vh';
                    v.style.objectFit = 'contain';
                    v.style.background = '#000';
                    v.style.position = 'fixed';
                    v.style.top = '0';
                    v.style.left = '0';
                    v.style.zIndex = '999999';
                    v.muted = false;
                    v.controls = false;
                    v.play().catch(function(e){});
                }
                
                // 3. 尝试触发页面上的播放按钮（覆盖常见播放器内核）
                var playBtns = document.querySelectorAll('.play, .play-btn, .vjs-big-play-button, [class*="play"]');
                for (var j = 0; j < playBtns.length; j++) {
                    try { playBtns[j].click(); } catch(e){}
                }
                
                // 4. 隐藏遮挡画面的 loading 层与解码中遮罩
                var loadings = document.querySelectorAll('.loading, .spinner, [class*="loading"], [class*="decode"]');
                for (var k = 0; k < loadings.length; k++) {
                    loadings[k].style.display = 'none';
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)

        // 延迟 2 秒二次执行，防止播放器脚本异步初始化较慢
        handler.postDelayed({
            view?.evaluateJavascript(js, null)
        }, 2000)
    }

    private fun loadWithCurrentStrategy() {
        handler.removeCallbacks(timeoutRunnable)
        webView?.loadUrl(currentUrl)
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun doRetry(reason: String) {
        if (retryCount >= maxRetry) {
            // 重试耗尽，退出播放避免长时间黑屏假死
            exitPlayer()
            return
        }

        retryCount++

        // 策略切换：
        // 第 0 次：硬件加速 + 默认 UA
        // 第 1 次：硬件加速 + 桌面 UA
        // 第 2 次：软件渲染 (LAYER_TYPE_SOFTWARE) + 手机 UA (很多低配盒子在此成功出画)
        // 第 3 次：软件渲染 + 旧版 TV UA
        val useSoftware = retryCount >= 2

        handler.postDelayed({
            createAndLoadWebView(useSoftwareLayer = useSoftware)
        }, 800)
    }

    private fun enterTrueFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun destroyWebView() {
        try {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (_: Exception) {
        }
        webView = null
        rootContainer.removeAllViews()
    }

    private fun exitPlayer() {
        handler.removeCallbacksAndMessages(null)
        destroyWebView()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitPlayer()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        try {
            webView?.onPause()
            webView?.pauseTimers()
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView?.onResume()
            webView?.resumeTimers()
            enterTrueFullscreen()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        destroyWebView()
        super.onDestroy()
    }
}
