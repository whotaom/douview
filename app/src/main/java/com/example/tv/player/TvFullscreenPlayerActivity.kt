package com.example.tv.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
 * 电视大屏真正沉浸式全屏纯净播放 Activity
 * 1. 彻底解决有声音但黑屏：强制启用硬件加速 (LAYER_TYPE_HARDWARE)，允许混合内容与自动播放；
 * 2. 彻底去除任何残留 UI 元素：进入真实沉浸全屏，注入 CSS 抹除网页顶部导航与冗余按钮，仅留铺满大屏的高清视频；
 * 3. 遥控器原生优化：D-pad 返回键双重拦截，退出时干净销毁 WebView，杜绝退出后背景继续发声。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout

    // WebChromeClient 全屏视频支持
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0

    // 重试相关
    private var currentUrl: String = ""
    private var retryCount = 0
    private val maxRetry = 3
    private var hasError = false

    // 超时检测
    private val TIMEOUT_MS = 18_000L   // 18 秒超时机制
    private val timeoutRunnable = Runnable {
        if (!hasError && webView.progress < 100) {
            onLoadTimeout()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 进入绝对沉浸全屏 + 屏幕常亮守护
        enterTrueFullscreen()

        // 2. 创建纯黑全屏底色容器（杜绝任何边缘白边/灰边）
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        // 3. 创建核心播放器 WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            // 关键军工级防黑屏：强制硬件加速让 GPU 合成视频渲染管线，解决电视盒子上声音正常但画面黑屏问题
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        rootContainer.addView(webView)

        // 4. 配置专用播放内核
        setupWebView()

        // 5. 遥控器返回键处理
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })

        // 6. 加载目标中控/直播地址
        currentUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (currentUrl.isBlank()) {
            finish()
            return
        }
        loadUrlWithRetry(currentUrl)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false      // 关键！允许电视自动开播，无需手动手势点击
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            blockNetworkImage = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 关键军工级净化：注入 CSS 彻底抹平网页自带的顶部「快看」、说明与操作栏，强制全屏铺满视频
                view?.evaluateJavascript(
                    """
                    (function() {
                        var style = document.getElementById('tv-fullscreen-clean-style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'tv-fullscreen-clean-style';
                            style.innerHTML = `
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
                                video, .stage, .player-container, #player, .xgplayer, [class*="player"] {
                                    position: fixed !important;
                                    top: 0 !important;
                                    left: 0 !important;
                                    width: 100vw !important;
                                    height: 100vh !important;
                                    max-width: 100vw !important;
                                    max-height: 100vh !important;
                                    object-fit: contain !important;
                                    z-index: 999999 !important;
                                    background: #000 !important;
                                }
                            `;
                            document.head.appendChild(style);
                        }

                        // 强制触发播放并解除静音
                        var v = document.querySelector('video');
                        if (v) {
                            v.muted = false;
                            v.play().catch(function(e){});
                        }
                    })();
                    """.trimIndent(),
                    null
                )
                webView.removeCallbacks(timeoutRunnable)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    hasError = true
                    webView.removeCallbacks(timeoutRunnable)
                    handleLoadError("加载失败: ${error?.description}")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                hasError = true
                webView.removeCallbacks(timeoutRunnable)
                handleLoadError("加载失败: $description")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                originalSystemUiVisibility = window.decorView.systemUiVisibility

                (window.decorView as FrameLayout).addView(
                    customView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                enterTrueFullscreen()
            }

            override fun onHideCustomView() {
                (window.decorView as FrameLayout).removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }
    }

    private fun loadUrlWithRetry(url: String) {
        hasError = false
        retryCount = 0
        webView.removeCallbacks(timeoutRunnable)
        webView.loadUrl(url)
        webView.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    private fun onLoadTimeout() {
        hasError = true
        handleLoadError("加载超时，正在自动重试...")
    }

    private fun handleLoadError(msg: String) {
        if (retryCount < maxRetry) {
            retryCount++
            webView.postDelayed({
                webView.stopLoading()
                webView.loadUrl(currentUrl)
                webView.postDelayed(timeoutRunnable, TIMEOUT_MS)
            }, 1500L * retryCount)
        } else {
            // 重试上限后直接优雅退出，避免长时间黑屏卡住
            finish()
        }
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
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun exitPlayer() {
        try {
            webView.removeCallbacks(timeoutRunnable)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
        }
        finish()
    }

    // 遥控器返回键硬件兜底拦截（适配部分深度定制的低配电视机顶盒）
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
            webView.onPause()
            webView.pauseTimers()
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
            enterTrueFullscreen()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            webView.removeCallbacks(timeoutRunnable)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
