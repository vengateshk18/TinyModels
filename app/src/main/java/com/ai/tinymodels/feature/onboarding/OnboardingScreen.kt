package com.ai.tinymodels.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.components.TinyModelsLogo
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * First-run tour. Five pages, swipeable, dismissible at any time.
 *
 * The same composable serves both entry points:
 *  - first launch (`isReplay = false`) — finishing or skipping records the
 *    visit and opens the app;
 *  - Settings → "View intro" (`isReplay = true`) — the labels say Close/Done
 *    and the caller closes the screen.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    isReplay: Boolean = false
) {
    val pages = OnboardingPages.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val lastPage = pagerState.currentPage == pages.lastIndex

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.surfaceContainerLowest, colors.background)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // ---- Header: brand + escape hatch ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TinyModelsLogo(size = 31.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSkip) {
                    Text(stringResource(if (isReplay) R.string.common_close else R.string.onboarding_skip))
                }
            }

            // ---- Pages ----
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { index ->
                val page = pages[index]

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OnboardingIllustration(
                        art = page.art,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(0.86f)
                            .graphicsLayer {
                                // Read the pager scroll state inside the draw
                                // layer, not in composition, so a swipe scales
                                // the art without recomposing the whole page.
                                val offset = (pagerState.currentPage - index) +
                                    pagerState.currentPageOffsetFraction
                                val proximity = 1f - abs(offset).coerceIn(0f, 1f)
                                val scale = 0.88f + 0.12f * proximity
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.35f + 0.65f * proximity
                            }
                    )

                    Text(
                        text = stringResource(page.titleRes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp,
                        textAlign = TextAlign.Center,
                        color = colors.onSurface
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(page.bodyRes),
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(Modifier.height(28.dp))
                }
            }

            // ---- Dots ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    val selected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (selected) 26.dp else 8.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "dotWidth$index"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(
                                if (selected) colors.primary
                                else colors.primary.copy(alpha = 0.20f)
                            )
                    )
                }
            }

            // ---- Primary action ----
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (lastPage) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                val ctaRes = when {
                    lastPage && isReplay -> R.string.onboarding_done
                    lastPage -> R.string.onboarding_get_started
                    else -> R.string.onboarding_next
                }
                AnimatedContent(
                    targetState = ctaRes,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "onboardingCta"
                ) { label ->
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Illustration
// ---------------------------------------------------------------------------

private data class ArtSpec(
    val icon: ImageVector,
    val badges: List<ImageVector>
)

private fun OnboardingArt.spec(): ArtSpec = when (this) {
    OnboardingArt.PRIVACY -> ArtSpec(Icons.Rounded.Lock, listOf(Icons.Rounded.VisibilityOff, Icons.Rounded.Bolt))
    OnboardingArt.DOWNLOAD -> ArtSpec(Icons.Rounded.Download, listOf(Icons.Rounded.WifiOff, Icons.Rounded.CloudDone))
    OnboardingArt.CHAT -> ArtSpec(Icons.AutoMirrored.Rounded.Chat, listOf(Icons.Rounded.AutoAwesome, Icons.Rounded.Image))
    OnboardingArt.PERSONALIZE -> ArtSpec(Icons.Rounded.Palette, listOf(Icons.Rounded.Language, Icons.Rounded.AutoAwesome))
    OnboardingArt.READY -> ArtSpec(Icons.Rounded.Widgets, listOf(Icons.Rounded.PhoneAndroid, Icons.Rounded.Storage))
}

@Composable
private fun OnboardingIllustration(art: OnboardingArt, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val spec = remember(art) { art.spec() }
    val accent = when (art) {
        OnboardingArt.PRIVACY, OnboardingArt.READY -> colors.primary
        OnboardingArt.DOWNLOAD, OnboardingArt.PERSONALIZE -> colors.secondary
        OnboardingArt.CHAT -> colors.tertiary
    }

    val drift by rememberInfiniteTransition(label = "artDrift").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "artDriftValue"
    )

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        if (side.value <= 0f) return@BoxWithConstraints

        Box(modifier = Modifier.size(side)) {
            // Soft glow under the tile.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.26f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.52f
                    ),
                    radius = size.minDimension * 0.52f,
                    center = center
                )
            }

            // The tile itself.
            Box(
                modifier = Modifier
                    .fillMaxSize(0.82f)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(34.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.22f),
                                accent.copy(alpha = 0.07f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(34.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(side * 0.34f)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(colors.surface.copy(alpha = 0.94f))
                        .border(1.dp, accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(side * 0.16f)
                    )
                }
            }

            // Floating satellite chips.
            BadgeChip(
                icon = spec.badges[0],
                accent = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = (side * 0.02f).roundToPx(),
                            y = (side * 0.10f + (drift * 5).dp).roundToPx()
                        )
                    },
                size = side * 0.20f
            )
            BadgeChip(
                icon = spec.badges[1],
                accent = accent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        IntOffset(
                            x = (side * 0.02f).roundToPx(),
                            y = (-(side * 0.10f) - (drift * 5).dp).roundToPx()
                        )
                    },
                size = side * 0.20f
            )
        }
    }
}

@Composable
private fun BadgeChip(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, accent.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.copy(alpha = 0.85f),
            modifier = Modifier.size(size * 0.46f)
        )
    }
}
