package com.minibrain.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.minibrain.ui.screens.ChatHistoryScreen
import com.minibrain.ui.screens.ChatScreen
import com.minibrain.ui.screens.EvalScreen
import com.minibrain.ui.screens.HomeScreen
import com.minibrain.ui.screens.OnboardingScreen
import com.minibrain.ui.screens.SettingsScreen

private const val NAV_ANIM_MS = 250

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat"
    const val CHAT_HISTORY = "chat_history"
    const val SETTINGS = "settings"
    const val EVAL = "eval"
}

@Composable
fun AppNav(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.ONBOARDING,
        // 進むときは右から、戻るときは左から。少しだけずらしてフェードを重ね、動きを控えめにする
        enterTransition = { slideInHorizontally(tween(NAV_ANIM_MS)) { it / 4 } + fadeIn(tween(NAV_ANIM_MS)) },
        exitTransition = { slideOutHorizontally(tween(NAV_ANIM_MS)) { -it / 4 } + fadeOut(tween(NAV_ANIM_MS)) },
        popEnterTransition = { slideInHorizontally(tween(NAV_ANIM_MS)) { -it / 4 } + fadeIn(tween(NAV_ANIM_MS)) },
        popExitTransition = { slideOutHorizontally(tween(NAV_ANIM_MS)) { it / 4 } + fadeOut(tween(NAV_ANIM_MS)) },
    ) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onReady = { openChat ->
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                    // 普段使いはチャットから始める。戻ると Home（フォルダ・インデックス状態）
                    if (openChat) navController.navigate(Routes.CHAT)
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { navController.navigate(Routes.CHAT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSession = { sessionId -> navController.navigate("${Routes.CHAT}?sessionId=$sessionId") },
            )
        }

        composable(
            route = "${Routes.CHAT}?sessionId={sessionId}",
            arguments = listOf(navArgument("sessionId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenHistory = { navController.navigate(Routes.CHAT_HISTORY) },
            )
        }

        composable(Routes.CHAT_HISTORY) {
            ChatHistoryScreen(
                onBack = { navController.popBackStack() },
                onSelectSession = { sessionId ->
                    navController.navigate("${Routes.CHAT}?sessionId=$sessionId") {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenEval = { navController.navigate(Routes.EVAL) },
            )
        }

        composable(Routes.EVAL) {
            EvalScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
