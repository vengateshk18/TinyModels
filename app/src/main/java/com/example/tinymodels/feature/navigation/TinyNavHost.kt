package com.example.tinymodels.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.tinymodels.feature.chat.ChatScreen
import com.example.tinymodels.feature.models.screens.ModelDetailsScreen
import com.example.tinymodels.feature.models.screens.ModelListScreen

/**
 * Single-activity navigation graph. Chat is the start destination; models,
 * downloaded-models and settings destinations are filled in by their own slices.
 */
@Composable
fun TinyNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                onNavigateToModels = { navController.navigate(Routes.MODELS) }
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

        // Filled by slices 6/7.
        composable(Routes.DOWNLOADED_MODELS) { PlaceholderScreen("Downloaded models (slice 6)") }
        composable(Routes.SETTINGS) { PlaceholderScreen("Settings (slice 7)") }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
