package com.example.tv.navigation

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    var showUrlDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = TvRoutes.HOME
    ) {
        composable(TvRoutes.HOME) {
            TvHomeScreen(
                onEnterPlayer = { url ->
                    val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                        putExtra(TvFullscreenPlayerActivity.EXTRA_URL, url)
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
            currentUrl = "",
            onDismiss = { showUrlDialog = false },
            onConfirmUrl = { confirmedUrl ->
                showUrlDialog = false
                val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                    putExtra(TvFullscreenPlayerActivity.EXTRA_URL, confirmedUrl)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_TITLE, "抖眸流控 · 大屏全屏直播监控")
                }
                context.startActivity(intent)
            }
        )
    }
}
