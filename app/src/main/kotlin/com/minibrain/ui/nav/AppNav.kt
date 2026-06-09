package com.minibrain.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.minibrain.ui.screens.ChatHistoryScreen
import com.minibrain.ui.screens.ChatScreen
import com.minibrain.ui.screens.HomeScreen
import com.minibrain.ui.screens.OnboardingScreen
import com.minibrain.ui.screens.SettingsScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat"
    const val CHAT_HISTORY = "chat_history"
    const val SETTINGS = "settings"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenChat = { navController.navigate(Routes.CHAT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
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
            )
        }
    }
}
