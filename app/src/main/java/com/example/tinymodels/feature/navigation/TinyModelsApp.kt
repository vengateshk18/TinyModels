package com.example.tinymodels.feature.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.tinymodels.feature.chat.ChatTabScreen
import com.example.tinymodels.feature.models.screens.DownloadedModelsScreen
import com.example.tinymodels.feature.models.screens.ModelDetailsScreen
import com.example.tinymodels.feature.models.screens.ModelListScreen
import com.example.tinymodels.feature.settings.SettingsScreen

/**
 * Top-level app scaffold with a bottom [NavigationBar] hosting 4 tabs:
 * Home, Chat, Models, Settings. Pushed routes (model details, chat room)
 * are full-screen and hide the bottom bar.
 */
@Composable
fun TinyModelsApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in setOf(
        Routes.HOME, Routes.CHAT, Routes.MODELS, Routes.SETTINGS
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // --- Tab destinations ---

            composable(Routes.HOME) {
                HomePlaceholder()
            }

            composable(Routes.CHAT) {
                ChatTabScreen(
                    onOpenChat = { chatId -> navController.navigate(Routes.chatRoom(chatId)) },
                    onNavigateToModels = { navController.navigate(Routes.MODELS) }
                )
            }

            composable(Routes.MODELS) {
                ModelListScreen(
                    onBack = { navController.popBackStack() },
                    onModelClick = { modelId -> navController.navigate(Routes.modelDetails(modelId)) }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onManageModels = { navController.navigate(Routes.MODELS) },
                    onDownloadedModels = { navController.navigate(Routes.DOWNLOADED_MODELS) }
                )
            }

            // --- Pushed routes ---

            composable(
                route = Routes.MODEL_DETAILS,
                arguments = listOf(navArgument("modelId") { type = NavType.StringType })
            ) {
                ModelDetailsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.DOWNLOADED_MODELS) {
                DownloadedModelsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    BottomNavItem(Routes.CHAT, "Chat", Icons.Outlined.Chat, Icons.Filled.Chat),
    BottomNavItem(Routes.MODELS, "Models", Icons.Outlined.Download, Icons.Filled.Download),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
private fun HomePlaceholder() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = "Home — Device Info\n(coming in N8)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
