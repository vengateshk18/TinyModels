package com.ai.tinymodels.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ai.tinymodels.MainActivity
import com.ai.tinymodels.R

class TinyModelsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { WidgetStateLoader.load(context) }
            .getOrElse { WidgetUiState(WidgetDisplayState.GET_STARTED) }

        provideContent {
            GlanceTheme {
                WidgetContent(context = context, state = state)
            }
        }
    }
}

@Composable
private fun WidgetContent(context: Context, state: WidgetUiState) {
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    })

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .clickable(openApp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand + content
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(28.dp)
                        .cornerRadius(8.dp)
                        .background(Color.White)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.mipmap.ic_launcher_foreground),
                        contentDescription = context.getString(R.string.app_name),
                        modifier = GlanceModifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = context.getString(R.string.app_name),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            when (state.displayState) {
                WidgetDisplayState.GET_STARTED -> {
                    Text(
                        text = context.getString(R.string.widget_get_started_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = context.getString(R.string.widget_get_started_subtitle),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }

                WidgetDisplayState.READY -> {
                    Text(
                        text = context.getString(R.string.widget_ready_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = context.resources.getQuantityString(
                            R.plurals.widget_models_ready,
                            state.downloadedModelCount,
                            state.downloadedModelCount
                        ),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }

                WidgetDisplayState.CONTINUE -> {
                    Text(
                        text = state.lastChatTitle?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.widget_continue_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = context.getString(R.string.widget_continue_title) + " · " +
                            context.resources.getQuantityString(
                                R.plurals.widget_models_ready,
                                state.downloadedModelCount,
                                state.downloadedModelCount
                            ),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = GlanceModifier.width(10.dp))

        // CTA
        Box(
            modifier = GlanceModifier
                .cornerRadius(16.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (state.displayState) {
                    WidgetDisplayState.GET_STARTED -> context.getString(R.string.widget_cta_browse_models)
                    WidgetDisplayState.READY -> context.getString(R.string.widget_cta_start_chat)
                    WidgetDisplayState.CONTINUE -> context.getString(R.string.widget_cta_continue_chat)
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            )
        }
    }
}
