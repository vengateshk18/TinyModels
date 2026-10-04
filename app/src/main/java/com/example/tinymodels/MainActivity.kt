package com.example.tinymodels

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.core.network.NetworkMonitor
import com.example.tinymodels.core.ui.theme.TinyModelsTheme
import com.example.tinymodels.core.ui.theme.resolveColorScheme
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.navigation.TinyModelsApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity hosting the Compose navigation graph. Applies the user's
 * theme + dynamic-color preference from [SettingsRepository].
 *
 * [enableEdgeToEdge] is re-invoked whenever the theme changes so the system
 * navigation bar is painted the same solid color as the bottom
 * [androidx.compose.material3.NavigationBar] (`surfaceContainer`), and the
 * status bar stays transparent.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            // Re-apply edge-to-edge whenever the theme changes, so the system
            // navigation bar follows the app theme instead of staying
            // transparent/light.
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            // Mirror the bottom NavigationBar's container color
            // (surfaceContainer) so the system navigation bar reads as a
            // continuation of it instead of a mismatched scrim.
            val navBarColor = resolveColorScheme(
                context = this@MainActivity,
                darkTheme = darkTheme,
                dynamicColor = settings.useDynamicColor
            ).surfaceContainer.toArgb()

            LaunchedEffect(darkTheme, navBarColor) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = android.graphics.Color.TRANSPARENT,
                        darkScrim = android.graphics.Color.TRANSPARENT,
                        detectDarkMode = { darkTheme }
                    ),
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(navBarColor)
                    } else {
                        SystemBarStyle.light(navBarColor, navBarColor)
                    }
                )
            }

            // Ask for the notification runtime permission once (Android 13+) so the
            // model-download foreground notification is allowed to appear. Denial is
            // graceful: downloads keep working, the notification just stays hidden.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* result ignored — downloads work either way */ }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            TinyModelsTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor,
                fontChoice = settings.fontChoice,
                fontScale = settings.fontScale
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TinyModelsApp(networkMonitor = networkMonitor)
                }
            }
        }
    }
}
