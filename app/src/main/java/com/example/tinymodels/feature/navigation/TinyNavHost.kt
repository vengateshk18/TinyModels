package com.example.tinymodels.feature.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.tinymodels.feature.chat.ChatScreen
import com.example.tinymodels.feature.models.screens.DownloadedModelsScreen
import com.example.tinymodels.feature.models.screens.ModelDetailsScreen
import com.example.tinymodels.feature.models.screens.ModelListScreen
import com.example.tinymodels.feature.settings.SettingsScreen

/**
 * Single-activity navigation graph. Chat is the start destination.
 */
@Composable
fun TinyNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToModels = { navController.navigate(Routes.MODELS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.MODELS) {
            ModelListScreen(
                onBack = { navController.popBackStack() },
                onModelClick = { modelId -> navController.navigate(Routes.modelDetails(modelId)) }
            )
        }

        composable(
            route = Routes.MODEL_DETAILS,
            arguments = listOf(navArgument("modelId") { type = NavType.StringType })
        ) {
            ModelDetailsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DOWNLOADED_MODELS) {
            DownloadedModelsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onManageModels = { navController.navigate(Routes.MODELS) },
                onDownloadedModels = { navController.navigate(Routes.DOWNLOADED_MODELS) }
            )
        }
    }
}
