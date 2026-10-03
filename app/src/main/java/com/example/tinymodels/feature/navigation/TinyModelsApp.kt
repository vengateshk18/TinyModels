package com.example.tinymodels.feature.navigation

import androidx.compose.foundation.layout.WindowInsets
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
import com.example.tinymodels.feature.chat.ChatListViewModel
import com.example.tinymodels.feature.chat.ChatRoomScreen
import com.example.tinymodels.feature.chat.ChatTabScreen
import com.example.tinymodels.feature.models.screens.DownloadedFileDetailScreen
import com.example.tinymodels.feature.models.screens.DownloadedModelsScreen
import com.example.tinymodels.feature.models.screens.ModelDetailsScreen
import com.example.tinymodels.feature.models.screens.ModelListScreen
import com.example.tinymodels.feature.models.screens.ModelsTabScreen
import com.example.tinymodels.feature.home.DeviceInfoScreen
import com.example.tinymodels.feature.home.HomeScreen
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                HomeScreen(
                    onBrowseModels = { navController.navigate(Routes.MODELS) },
                    onResumeChat = { chatId ->
                        navController.navigate(Routes.chatRoom(chatId))
                    },
                    onNewChat = { modelId ->
                        navController.navigate(
                            if (modelId != null) {
                                Routes.chatRoomWithPreference(modelId)
                            } else {
                                Routes.chatRoom(ChatListViewModel.NEW_CHAT_SENTINEL)
                            }
                        )
                    },
                    onManageModels = { navController.navigate(Routes.DOWNLOADED_MODELS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.CHAT) {
                ChatTabScreen(
                    onOpenChat = { chatId -> navController.navigate(Routes.chatRoom(chatId)) },
                    onNavigateToModels = { navController.navigate(Routes.MODELS) }
                )
            }

            composable(Routes.MODELS) {
                ModelsTabScreen(
                    onModelClick = { modelId -> navController.navigate(Routes.modelDetails(modelId)) }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onManageModels = { navController.navigate(Routes.MODELS) },
                    onDownloadedModels = { navController.navigate(Routes.DOWNLOADED_MODELS) },
                    onDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) }
                )
            }

            // --- Pushed routes ---

            composable(
                route = Routes.MODEL_DETAILS,
                arguments = listOf(navArgument("modelId") { type = NavType.StringType })
            ) { backStackEntry ->
                val modelId = backStackEntry.arguments?.getString("modelId") ?: return@composable
                ModelDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onDownloadedFileClick = { fileName ->
                        navController.navigate(Routes.downloadedFileDetail(modelId, fileName))
                    }
                )
            }

            composable(
                route = Routes.DOWNLOADED_FILE_DETAIL,
                arguments = listOf(
                    navArgument("modelId") { type = NavType.StringType },
                    navArgument("fileName") { type = NavType.StringType }
                )
            ) {
                DownloadedFileDetailScreen(
                    onBack = { navController.popBackStack() },
                    onStartChat = { preferredModelId ->
                        // Navigate directly to a new chat room with the preferred model.
                        // Model loading happens inside ChatViewModel — not here.
                        navController.navigate(Routes.chatRoomWithPreference(preferredModelId))
                    }
                )
            }

            composable(Routes.DOWNLOADED_MODELS) {
                DownloadedModelsScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.CHAT_ROOM,
                arguments = listOf(navArgument("chatId") { type = NavType.StringType })
            ) {
                ChatRoomScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.CHAT_ROOM_WITH_PREF,
                arguments = listOf(
                    navArgument("chatId") { type = NavType.StringType },
                    navArgument("preferredModelId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                ChatRoomScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.DEVICE_INFO) {
                DeviceInfoScreen(
                    onBack = { navController.popBackStack() },
                    onBrowseModels = {
                        navController.popBackStack()
                        navController.navigate(Routes.MODELS)
                    }
                )
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
