package com.example.tinymodels.models.ui

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.example.tinymodels.models.data.Model
import com.example.tinymodels.models.ui.composables.ModelsListItemComposable
import com.example.tinymodels.ui.theme.TinyModelsTheme
import com.example.tinymodels.utils.Injection

@OptIn(ExperimentalMaterial3Api::class)
class ModelListActivity : ComponentActivity() {
    lateinit var viewModel: ModelListViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModelFactory= ModelListViewModelFactory(Injection.UseCases.getListUseCase())
        viewModel= ViewModelProvider(this,viewModelFactory)[ModelListViewModel::class]

        viewModel.getListOfModels()

        setContent {
            TinyModelsTheme {
                val models by viewModel.modelList.collectAsState()
                val errorRes by viewModel.modelListFailure.collectAsState()
                val isLoading by viewModel.isLoading.collectAsState()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(text = "Models") },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        PrintModels(
                            models = models,
                            errorRes = errorRes,
                            isLoading = isLoading,
                            onModelClick = { model ->
                                startActivity(
                                    Intent(
                                        this@ModelListActivity,
                                        ModelDetailsActivity::class.java
                                    ).putExtra(
                                        ModelDetailsActivity.EXTRA_MODEL_ID,
                                        model.modelId
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrintModels(
    models: List<Model>,
    errorRes: String,
    isLoading: Boolean,
    onModelClick: (Model) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (errorRes.isNotBlank()) {
        Text(
            text = errorRes,
            style = MaterialTheme.typography.titleLarge,
            color = Color.Black,
            modifier = Modifier.fillMaxSize().padding(vertical = 70.dp, horizontal = 24.dp)
        )
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(models) { model ->
            ModelsListItemComposable(model = model, onClick = { onModelClick(model) })
        }
    }
}