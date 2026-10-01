package com.example.titymodels.models.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.titymodels.models.ui.composables.BottomBarComposable
import com.example.titymodels.models.ui.composables.BottomBarItem
import com.example.titymodels.models.ui.composables.ModelsListItemComposable
import com.example.titymodels.models.data.Model
import com.example.titymodels.models.ui.composables.ChattingScreenComposable
import com.example.titymodels.models.ui.composables.SettingsScreenComposable
import com.example.titymodels.ui.theme.TityModelsTheme
import com.example.titymodels.utils.Injection

class MainActivity : ComponentActivity() {
    lateinit var viewModel: ModelListViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Injection.initialize(applicationContext)
        enableEdgeToEdge()

        val bottomBarItems = listOf(
            BottomBarItem("Chat", Icons.Filled.ChatBubble),
            BottomBarItem("Settings", Icons.Filled.Settings)
        )

        setContent {
            TityModelsTheme {
                var currentBottomTab by rememberSaveable { mutableStateOf(0) }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        BottomBarComposable(
                            items = bottomBarItems,
                            selectedItem = currentBottomTab,
                            currentSelectedIndex = { index ->
                                currentBottomTab = index
                            }
                        )
                    }
                ) { innerPadding ->
                    when(currentBottomTab){
                        0 -> ChattingScreenComposable(
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> SettingsScreenComposable()
                        else -> ChattingScreenComposable(
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}
