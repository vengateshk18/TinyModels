package com.example.tinymodels.models.ui.composables

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BottomBarComposable(
    items: List<BottomBarItem>,
    selectedItem: Int,
    currentSelectedIndex: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = {
                    Icon(imageVector = item.icon, contentDescription = item.name)
                },
                label = { Text(text = item.name) },
                selected = selectedItem == index,
                onClick = { currentSelectedIndex(index) }
            )
        }
    }
}

data class BottomBarItem(
    var name: String,
    var icon: ImageVector
)