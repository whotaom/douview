package com.example.tv.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.LivePreferences
import com.example.tv.components.TvActionButton
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TvPlayerScreen(
    url: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { LivePreferences(context) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showHud by remember { mutableStateOf(false) }

    // 遥控器返回键处理：按返回键优先隐藏 HUD，再次按返回键退出全屏大屏模式返回主页
    BackHandler {
        if (showHud) {
            showHud = false
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // 电视大屏 WebView 核心视图
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.BLACK)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT

                        if (preferences.isDesktopUa) {
                            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            safeBrowsingEnabled = false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 电视端自适应大屏 CSS 注入（防止布局溢出与黑边）
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var style = document.createElement('style');
                                    style.innerHTML = 'body, html { width: 100% !important; height: 100% !important; overflow: hidden !important; background: #000 !important; } video { object-fit: contain !important; width: 100% !important; height: 100% !important; }';
                                    document.head.appendChild(style);
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }

                    webChromeClient = WebChromeClient()

                    loadUrl(url)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部大屏遥控器悬浮操作 HUD（按【菜单】或遥控器交互时展开）
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DarkSurfaceElevated.copy(alpha = 0.95f),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column {
                        Text("当前监控大屏: $url", color = TextPrimary, fontSize = 14.sp)
                        Text("按遥控器【返回键】关闭控制条或退出全屏", color = TextSecondary, fontSize = 11.sp)
                    }

                    TvActionButton(
                        text = "刷新画面",
                        icon = Icons.Default.Refresh,
                        onClick = {
                            webViewRef?.reload()
                            showHud = false
                        }
                    )

                    TvActionButton(
                        text = "退出全屏",
                        icon = Icons.Default.Close,
                        isPrimary = true,
                        onClick = onBack
                    )
                }
            }
        }

        // 屏幕右上角常驻极简提示（按返回键随时退出）
        if (!showHud) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 10.dp),
                color = DarkSurfaceElevated.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Text(
                    text = "按【返回键】退出大屏模式",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
