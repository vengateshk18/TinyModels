package com.ai.tinymodels

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
import kotlinx.coroutines.runBlocking
import com.ai.tinymodels.core.common.AppLocale
import com.ai.tinymodels.core.common.AppLocaleStore
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.core.ui.theme.TinyModelsTheme
import com.ai.tinymodels.core.ui.theme.resolveColorScheme
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.feature.navigation.TinyModelsApp
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

    /**
     * Applies the user's chosen app language before any resource is resolved.
     *
     * On API 33+ the platform owns per-app locales (set via [AppLocale.apply]),
     * so we must not override the configuration here or we would fight the
     * system-level per-app language setting. Below API 33 there is no such
     * API, so the stored locale is applied to the base context instead.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ""
        } else {
            AppLocaleStore.currentTag(newBase)
        }
        super.attachBaseContext(AppLocale.wrapContext(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read the persisted settings ONCE, synchronously, BEFORE the first
        // composition. Collecting the flow with a default initial value
        // instead renders the first frames with the default (light) theme
        // and then flips to the persisted (dark) theme a moment later —
        // the visible "app bar flash" on launch.
        val initialSettings = runBlocking { settingsRepository.getSettings() }

        // Apply edge-to-edge with the correct theme up front (not in a
        // LaunchedEffect after first composition) so the system bars are
        // painted the right color on the very first frame.
        val initialDarkTheme = when (initialSettings.themeMode) {
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        val initialNavBarColor = resolveColorScheme(
            context = this,
            darkTheme = initialDarkTheme,
            dynamicColor = initialSettings.useDynamicColor
        ).surfaceContainer.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
                detectDarkMode = { initialDarkTheme }
            ),
            navigationBarStyle = if (initialDarkTheme) {
                SystemBarStyle.dark(initialNavBarColor)
            } else {
                SystemBarStyle.light(initialNavBarColor, initialNavBarColor)
            }
        )

        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = initialSettings)

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
