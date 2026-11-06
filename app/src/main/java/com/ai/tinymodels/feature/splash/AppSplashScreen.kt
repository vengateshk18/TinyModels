package com.ai.tinymodels.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.tinymodels.core.ui.components.TinyModelsLogo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launch splash: the brand gets one calm beat while Home assembles its data
 * behind it, so the user never sees a spinner on a half-built dashboard.
 *
 * Everything here is pure animation — a breathing bloom, the mark settling in
 * with a spring, and the wordmark/slogan rising into place. It holds no data
 * and decides nothing; [com.ai.tinymodels.feature.startup.AppRoot] owns how
 * long it stays.
 */
@Composable
fun AppSplashScreen(
    modifier: Modifier = Modifier,
    /** Fired only once the last entrance animation has settled. */
    onIntroComplete: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val notifyIntroComplete by rememberUpdatedState(onIntroComplete)

    val breathe by rememberInfiniteTransition(label = "splashBreathe").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splashBreatheValue"
    )

    val markScale = remember { Animatable(0.86f) }
    val markAlpha = remember { Animatable(0f) }
    val wordAlpha = remember { Animatable(0f) }
    val wordShift = remember { Animatable(18f) }
    val sloganAlpha = remember { Animatable(0f) }
    val sloganShift = remember { Animatable(14f) }

    LaunchedEffect(Unit) {
        // The whole entrance is awaited before reporting completion, so the
        // splash can never be dismissed mid-animation (a cut slogan reads as
        // a glitch). The gate in AppRoot waits for this signal.
        coroutineScope {
            launch { markAlpha.animateTo(1f, tween(420)) }
            launch {
                markScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                )
            }
            launch {
                delay(160)
                wordAlpha.animateTo(1f, tween(480))
            }
            launch {
                delay(160)
                wordShift.animateTo(0f, tween(620, easing = FastOutSlowInEasing))
            }
            launch {
                delay(280)
                sloganAlpha.animateTo(1f, tween(480))
            }
            launch {
                delay(280)
                sloganShift.animateTo(0f, tween(620, easing = FastOutSlowInEasing))
            }
        }
        notifyIntroComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.surfaceContainerLow, colors.background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Breathing bloom behind the mark — the only continuous motion.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.42f)
            val radius = size.minDimension * (0.66f + 0.06f * breathe)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.26f),
                        colors.secondary.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .offset(y = (-16).dp)
        ) {
            TinyModelsLogo(
                size = 92.dp,
                // Decorative: the wordmark right below carries the name for
                // screen readers, so announcing the image too would repeat it.
                modifier = Modifier.graphicsLayer {
                    scaleX = markScale.value
                    scaleY = markScale.value
                    alpha = markAlpha.value
                }
            )

            Spacer(Modifier.height(26.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.offset(y = wordShift.value.dp)
            ) {
                Text(
                    text = "Tiny",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                    color = colors.primary.copy(alpha = wordAlpha.value)
                )
                Text(
                    text = "Models",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.8).sp,
                    color = colors.onSurface.copy(alpha = wordAlpha.value)
                )
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                colors.primary.copy(alpha = 0.85f * wordAlpha.value),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Small models. Big possibilities.",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center,
                color = colors.onSurfaceVariant.copy(alpha = sloganAlpha.value),
                modifier = Modifier.offset(y = sloganShift.value.dp)
            )
        }

        Text(
            text = "Private  ·  Offline  ·  Yours",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.55f * sloganAlpha.value),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp)
        )
    }
}
