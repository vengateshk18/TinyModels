package com.example.tinymodels.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tinymodels.feature.chat.model.ChatListItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Drawer content listing prior chats, with new-chat + swipe/delete. */
@Composable
fun ChatHistoryDrawer(
    chats: List<ChatListItem>,
    activeChatId: String?,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<ChatListItem?>(null) }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Chats",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("New chat")
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            if (chats.isEmpty()) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn {
                    items(chats, key = { it.id }) { chat ->
                        ChatHistoryRow(
                            chat = chat,
                            isActive = chat.id == activeChatId,
                            onClick = { onOpenChat(chat.id) },
                            onDelete = { pendingDelete = chat }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    "\"${chat.title.ifBlank { "Untitled" }}\" will be permanently deleted. " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteChat(chat.id)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ChatHistoryRow(
    chat: ChatListItem,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val container = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    androidx.compose.material3.Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                chat.preview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatRelative(chat.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatRelative(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
