package com.ai.tinymodels.feature.chat.components

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.tinymodels.R
import com.ai.tinymodels.domain.model.WebSearchResult
import com.ai.tinymodels.feature.chat.model.UiChatMessage
import com.mikepenz.markdown.m3.Markdown
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * A single chat bubble. User messages are right-aligned with primary-container
 * colors; assistant messages are left-aligned and rendered as Markdown (code
 * blocks, lists, bold). Copying (footer button or long-press) copies the raw
 * text and reports via [onCopied] so the screen can confirm with a Snackbar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiChatMessage,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onCopied: (() -> Unit)? = null,
    /** Speak this message aloud (assistant, completed). */
    onSpeak: (() -> Unit)? = null,
    /** Stop speaking this message. */
    onStopSpeak: (() -> Unit)? = null,
    /** TTS is initializing/loading for this message. */
    isSpeakLoading: Boolean = false,
    /** This message is currently being spoken. */
    isSpeaking: Boolean = false,
    /** Show the stored per-message token count in the footer (Settings). */
    showTokenCounts: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.isUser
    // Single copy path shared by the footer button and long-press.
    val copy: () -> Unit = {
        if (message.text.isNotBlank()) {
            clipboard.setText(AnnotatedString(message.text))
            onCopied?.invoke()
        }
    }

    var showSourcesSheet by remember(message.id) { mutableStateOf(false) }
    val sources = message.webSearchSources
    val canShowSources = !isUser && !message.isStreaming && sources.isNotEmpty()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = {
                            if (isUser && onEdit != null && !message.isStreaming) {
                                onEdit(message.text)
                            }
                        },
                        onLongClick = { copy() }
                    ),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.imagePath?.let { path ->
                        AttachedPhoto(path = path)
                        if (message.text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (isUser) {
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else {
                        // Typing dots until the first token, then the reply is
                        // rendered as Markdown live, with an animated inline
                        // caret. One retained Markdown state spans streaming ->
                        // complete, so the bubble never blanks while the final
                        // parse settles.
                        Crossfade(
                            targetState = message.isStreaming && message.text.isBlank(),
                            animationSpec = tween(durationMillis = 180),
                            label = "assistantContent"
                        ) { waitingForFirstToken ->
                            if (waitingForFirstToken) {
                                TypingIndicator()
                            } else {
                                StreamingMarkdown(
                                    content = message.text,
                                    isStreaming = message.isStreaming
                                )
                            }
                        }
                    }
                }
            }
        }
        // Timestamp + action row below the bubble.
        MessageFooter(message, onRegenerate, onCopy = copy, onSpeak = if (!isUser) onSpeak else null, onStopSpeak = if (!isUser) onStopSpeak else null, isSpeakLoading = !isUser && isSpeakLoading, isSpeaking = !isUser && isSpeaking, showTokenCounts = showTokenCounts)
        if (canShowSources) {
            SourcesButton(
                count = sources.size,
                onClick = { showSourcesSheet = true },
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }

    if (showSourcesSheet) {
        SourcesBottomSheet(
            sources = sources,
            onDismiss = { showSourcesSheet = false }
        )
    }
}

/**
 * Compact footer below a bubble: timestamp text + action buttons.
 * - User message: "HH:mm" + [copy icon]
 * - Assistant (complete): "HH:mm • {duration}" + [copy icon] + [regenerate icon]
 * - Assistant (streaming): hidden until done (copying partial text misleads).
 */
@Composable
private fun MessageFooter(
    message: UiChatMessage,
    onRegenerate: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onSpeak: (() -> Unit)?,
    onStopSpeak: (() -> Unit)?,
    isSpeakLoading: Boolean,
    isSpeaking: Boolean,
    showTokenCounts: Boolean
) {
    if (message.timestamp <= 0L) return
    val context = LocalContext.current
    val showRegen = !message.isUser && !message.isStreaming && onRegenerate != null
    val showCopy = !message.isStreaming && message.text.isNotBlank() && onCopy != null
    val showSpeak = !message.isUser && !message.isStreaming && message.text.isNotBlank() && (onSpeak != null || onStopSpeak != null || isSpeakLoading || isSpeaking)
    val showTokens = showTokenCounts && !message.isStreaming && (message.tokenCount ?: 0) > 0
    val label = remember(message.id, message.completedAt, message.isStreaming) {
        buildTimestampLabel(message, context)
    }
    if (label.isBlank() && !showRegen && !showCopy && !showSpeak && !showTokens) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier.padding(
            start = if (message.isUser) 0.dp else 4.dp,
            end = if (message.isUser) 4.dp else 0.dp,
            top = 2.dp
        )
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showTokens) {
            if (label.isNotBlank() || showCopy || showRegen) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = stringResource(R.string.chat_message_token_count, message.tokenCount ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showCopy) {
            if (label.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = onCopy!!,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.chat_message_copy_content_description),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showSpeak) {
            if (label.isNotBlank() || showCopy) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            when {
                isSpeakLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                isSpeaking && onStopSpeak != null -> {
                    IconButton(
                        onClick = onStopSpeak,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.chat_stop_speaking),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                onSpeak != null -> {
                    IconButton(
                        onClick = onSpeak,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.chat_speak_message),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (showRegen) {
            if (label.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = onRegenerate!!,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                                        imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.chat_message_regenerate_content_description),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Pure helper — formats sent/completed timestamps into a compact line. */
private fun buildTimestampLabel(message: UiChatMessage, context: Context): String {
    val sent = formatTime(message.timestamp, context)
    if (message.isUser) return sent
    if (message.isStreaming) return ""
    val completed = message.completedAt
    return if (completed != null && completed > message.timestamp) {
        "$sent \u2022 ${formatDuration(completed - message.timestamp, context.resources)}"
    } else {
        sent
    }
}

/** Localized time-of-day, e.g. "14:05" or "2:05 PM". */
private fun formatTime(epochMillis: Long, context: Context): String =
    DateFormat.getTimeFormat(context).format(Date(epochMillis))

/** Human-friendly duration: "1.2s", "12s", "1m 05s". */
private fun formatDuration(millis: Long, res: Resources): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    return if (seconds < 60) {
        res.getString(R.string.chat_duration_seconds, millis / 1000.0)
    } else {
        val m = seconds / 60
        val s = seconds % 60
        res.getString(R.string.chat_duration_minutes_seconds, m, s)
    }
}

@Composable
private fun SourcesButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = if (count == 1) {
        stringResource(R.string.chat_sources_count_one)
    } else {
        stringResource(R.string.chat_sources_count_other, count)
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesBottomSheet(
    sources: List<WebSearchResult>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSource by remember { mutableStateOf<WebSearchResult?>(null) }
    val query = sources.firstOrNull()?.query.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.chat_sources_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (query.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.chat_sources_searched_for),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = query,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            sources.forEachIndexed { index, source ->
                SourceCard(
                    source = source,
                    onClick = { selectedSource = source }
                )
                if (index < sources.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    selectedSource?.let { source ->
        SourceWebPageSheet(
            source = source,
            onDismiss = { selectedSource = null }
        )
    }
}

@Composable
private fun SourceCard(
    source: WebSearchResult,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = source.displayHost.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.displayHost,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = source.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.chat_source_open_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (source.snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceWebPageSheet(
    source: WebSearchResult,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 760.dp)
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = source.displayHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100) / 100f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                progress = 100
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) isLoading = false
                            }
                        }
                        loadUrl(source.url)
                    }
                },
                update = { view ->
                    if (view.url != source.url) view.loadUrl(source.url)
                }
            )
        }
    }
}
