package com.ai.tinymodels.feature.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.feature.benchmark.BenchmarkScreen
import com.ai.tinymodels.feature.chat.ChatListViewModel
import com.ai.tinymodels.feature.chat.ChatRoomScreen
import com.ai.tinymodels.feature.chat.ChatTabScreen
import com.ai.tinymodels.feature.models.screens.DownloadedFileDetailScreen
import com.ai.tinymodels.feature.models.screens.DownloadedModelsScreen
import com.ai.tinymodels.feature.models.screens.ModelDetailsScreen
import com.ai.tinymodels.feature.models.screens.ModelListScreen
import com.ai.tinymodels.feature.models.screens.ModelsTabScreen
import com.ai.tinymodels.feature.home.DeviceInfoScreen
import com.ai.tinymodels.feature.home.HomeScreen
import com.ai.tinymodels.feature.settings.SettingsScreen

/**
 * Top-level app scaffold with a bottom [NavigationBar] hosting 4 tabs:
 * Home, Chat, Models, Settings. Pushed routes (model details, chat room)
 * are full-screen and hide the bottom bar.
 */
@Composable
fun TinyModelsApp(
    navController: NavHostController = rememberNavController(),
    networkMonitor: NetworkMonitor
) {
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
                                // Tapping the tab you are already on must be a
                                // no-op: without this guard, popUpTo popped the
                                // current entry before launchSingleTop could
                                // dedupe the tap, rebuilding the screen.
                                if (TabNavigator.shouldNavigate(currentRoute, item.route)) {
                                    val startDest = navController.graph.findStartDestination().id
                                    navController.navigate(item.route) {
                                        // Pop to start destination to avoid building up
                                        // a deep back stack when switching tabs.
                                        popUpTo(startDest) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        // Sibling tabs restore their preserved state
                                        // (ViewModels survive the save/restore round trip).
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                AnimatedNavIcon(
                                    selected = selected,
                                    selectedIcon = item.selectedIcon,
                                    unselectedIcon = item.icon,
                                    label = item.label
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize()
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
                        onModelClick = { modelId -> navController.navigate(Routes.modelDetails(modelId)) },
                        onFileClick = { modelId, fileName ->
                            navController.navigate(Routes.downloadedFileDetail(modelId, fileName))
                        }
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onManageModels = { navController.navigate(Routes.MODELS) },
                        onDownloadedModels = { navController.navigate(Routes.DOWNLOADED_MODELS) },
                        onDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                        onBenchmark = { navController.navigate(Routes.BENCHMARK) }
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
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
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
                    DownloadedModelsScreen(
                        onBack = { navController.popBackStack() },
                        onFileClick = { modelId, fileName ->
                            navController.navigate(Routes.downloadedFileDetail(modelId, fileName))
                        }
                    )
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

                composable(Routes.BENCHMARK) {
                    BenchmarkScreen(onBack = { navController.popBackStack() })
                }
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

/**
 * Bottom-bar tab icon with a selection animation: the icon cross-fades between
 * its outlined and filled variants while doing a small springy pop, so tab
 * switches feel alive instead of snapping between static glyphs.
 */
@Composable
private fun AnimatedNavIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navIconScale"
    )
    // Unmistakable swing every time a tab is selected: snap tilted, spring back.
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            wiggle.snapTo(-16f)
            wiggle.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }
    AnimatedContent(
        targetState = selected,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        label = "navIconSwap"
    ) { isSelected ->
        Icon(
            imageVector = if (isSelected) selectedIcon else unselectedIcon,
            contentDescription = label,
            modifier = Modifier
                .scale(scale)
                .rotate(wiggle.value)
        )
    }
}
