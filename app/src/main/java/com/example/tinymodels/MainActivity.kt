package com.example.tinymodels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.example.tinymodels.core.ui.theme.TinyModelsTheme
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.navigation.TinyNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity hosting the Compose navigation graph. Applies the user's
 * theme + dynamic-color preference from [SettingsRepository].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            TinyModelsTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor,
                fontChoice = settings.fontChoice
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    TinyNavHost(navController)
                }
            }
        }
    }
}
