package com.example.tv.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.tv.screens.TvHomeScreen
import com.example.tv.screens.TvPlayerScreen
import com.example.ui.ChangeUrlDialog
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TvRoutes {
    const val HOME = "tv_home"
    const val PLAYER = "tv_player/{url}"

    fun player(url: String): String {
        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
        return "tv_player/$encoded"
    }
}

@Composable
fun TvNavGraph(
    navController: NavHostController = rememberNavController()
) {
    var showUrlDialog by remember { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = TvRoutes.HOME
    ) {
        composable(TvRoutes.HOME) {
            TvHomeScreen(
                onEnterPlayer = { url ->
                    navController.navigate(TvRoutes.player(url))
                },
                onOpenUrlDialog = { showUrlDialog = true }
            )
        }

        composable(
            route = TvRoutes.PLAYER,
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { backStackEntry ->
            val rawUrl = backStackEntry.arguments?.getString("url") ?: ""
            val decodedUrl = try {
                URLDecoder.decode(rawUrl, StandardCharsets.UTF_8.toString())
            } catch (e: Exception) {
                rawUrl
            }
            TvPlayerScreen(
                url = decodedUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }

    if (showUrlDialog) {
        ChangeUrlDialog(
            currentUrl = "",
            onDismiss = { showUrlDialog = false },
            onConfirmUrl = { confirmedUrl ->
                showUrlDialog = false
                navController.navigate(TvRoutes.player(confirmedUrl))
            }
        )
    }
}
