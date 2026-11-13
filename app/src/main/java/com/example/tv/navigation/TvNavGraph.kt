package com.example.tv.navigation

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.LivePreferences
import com.example.data.StreamLines
import com.example.tv.player.TvFullscreenPlayerActivity
import com.example.tv.screens.TvHomeScreen
import com.example.ui.ChangeUrlDialog

object TvRoutes {
    const val HOME = "tv_home"
}

@Composable
fun TvNavGraph(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val preferences = remember { LivePreferences(context) }
    var showUrlDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = TvRoutes.HOME
    ) {
        composable(TvRoutes.HOME) {
            TvHomeScreen(
                onEnterPlayer = { url ->
                    val currentLine = preferences.currentLine
                    val base = StreamLines.baseUrl(currentLine)
                    val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                        putExtra(TvFullscreenPlayerActivity.EXTRA_URL, url)
                        putExtra(TvFullscreenPlayerActivity.EXTRA_LINE, currentLine)
                        putExtra(TvFullscreenPlayerActivity.EXTRA_BASE_URL, base)
                        putExtra(TvFullscreenPlayerActivity.EXTRA_TITLE, "抖眸流控 · 大屏全屏直播监控")
                    }
                    context.startActivity(intent)
                },
                onOpenUrlDialog = { showUrlDialog = true }
            )
        }
    }

    if (showUrlDialog) {
        ChangeUrlDialog(
            currentUrl = preferences.targetUrl,
            onDismiss = { showUrlDialog = false },
            onConfirmUrl = { confirmedUrl ->
                showUrlDialog = false
                preferences.targetUrl = confirmedUrl
                val line = StreamLines.detectLine(confirmedUrl)
                val base = StreamLines.baseUrl(line)
                val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                    putExtra(TvFullscreenPlayerActivity.EXTRA_URL, confirmedUrl)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_LINE, line)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_BASE_URL, base)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_TITLE, "抖眸流控 · 大屏全屏直播监控")
                }
                context.startActivity(intent)
            }
        )
    }
}
