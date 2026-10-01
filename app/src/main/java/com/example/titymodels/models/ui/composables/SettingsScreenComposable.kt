package com.example.titymodels.models.ui.composables

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.titymodels.models.ui.ModelListActivity
import com.example.titymodels.models.ui.DownloadedModelsActivity

@Composable
fun SettingsScreenComposable() {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(top = 70.dp, start = 24.dp, end = 24.dp)) {
        Text(
            text = "Models",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Blue,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Manage AI Models",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black,
            modifier = Modifier.clickable {
                val modelListIntent = Intent(context, ModelListActivity::class.java)
                context.startActivity(modelListIntent)
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Downloaded Models",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Blue,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Manage Downloaded AI Models",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Black,
            modifier = Modifier.clickable {
                context.startActivity(Intent(context, DownloadedModelsActivity::class.java))
            }
        )
    }
}