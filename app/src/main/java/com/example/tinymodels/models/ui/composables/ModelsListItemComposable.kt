package com.example.tinymodels.models.ui.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.tinymodels.models.data.Model

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsListItemComposable(
    model: Model,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(modifier=modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 8.dp, horizontal = 16.dp)
        .border(
        BorderStroke(2.dp, color = Color.Black), shape = RoundedCornerShape(5.dp)).background(Color.White)){
        Column(modifier= Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.Center) {
            Row(modifier.fillMaxWidth()) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Blue
                )
            }
            Spacer(modifier.height(4.dp))
            Row(modifier= Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Likes: ${model.likes}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                Text(
                    text = "Downloads: ${model.downloads}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
            Spacer(modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                model.tags?.forEach { item ->
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.Gray, shape = RoundedCornerShape(8.dp))
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}