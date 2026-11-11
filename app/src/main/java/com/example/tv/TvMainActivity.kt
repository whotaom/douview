package com.example.tv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.data.LivePreferences
import com.example.tv.navigation.TvNavGraph
import com.example.ui.theme.DarkBg
import com.example.ui.theme.MyApplicationTheme

/**
 * 抖眸流控 · 电视端专属主入口 Activity (Android TV / Google TV Leanback 专属)
 * 特性：
 * 1. 强制横屏大屏沉浸模式（避免被电视系统当做手机竖屏窗口运行）；
 * 2. 屏幕常亮守护（防止大屏监控直播过程中电视自动睡眠黑屏）；
 * 3. 完整支持遥控器方向键（D-pad）与确认键、返回键交互；
 * 4. 搭载针对电视端优化的 10-foot UI 设计系统。
 */
class TvMainActivity : ComponentActivity() {

    private lateinit var preferences: LivePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = LivePreferences(this)

        // 电视大屏强制横屏呈现
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // 保持大屏常亮
        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBg),
                    color = DarkBg
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TvNavGraph()
                    }
                }
            }
        }
    }
}
