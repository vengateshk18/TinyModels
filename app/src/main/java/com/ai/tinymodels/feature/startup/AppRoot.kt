package com.ai.tinymodels.feature.startup

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.feature.navigation.TinyModelsApp
import com.ai.tinymodels.feature.onboarding.OnboardingScreen
import com.ai.tinymodels.feature.splash.AppSplashScreen
import kotlinx.coroutines.delay

/**
 * Root of the UI. Chooses between the first-run tour and the app shell, and
 * keeps the splash over the shell until Home has settled.
 *
 * [onboardingCompletedInitially] comes from the synchronous settings read in
 * `MainActivity`, so the very first frame is already the correct screen.
 */
@Composable
fun AppRoot(
    onboardingCompletedInitially: Boolean,
    onOnboardingFinished: () -> Unit,
    networkMonitor: NetworkMonitor
) {
    var introVisible by rememberSaveable { mutableStateOf(!onboardingCompletedInitially) }

    if (introVisible) {
        OnboardingScreen(
            onFinish = {
                introVisible = false
                onOnboardingFinished()
            },
            onSkip = {
                introVisible = false
                onOnboardingFinished()
            }
        )
    } else {
        AppShellWithSplash(networkMonitor = networkMonitor)
    }
}

/**
 * The normal app shell, covered by [AppSplashScreen] until either Home reports
 * that its first real state is on screen (and the minimum beat has elapsed) or
 * the safety cap is reached. The app is composed underneath from frame one, so
 * its data is already loading while the splash plays — the splash hides work,
 * it does not add work.
 */
@Composable
private fun AppShellWithSplash(networkMonitor: NetworkMonitor) {
    var homeReady by remember { mutableStateOf(false) }
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    // Survives rotation: rotating mid-splash must not restart the beat.
    val startedAt by rememberSaveable { mutableStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (shouldShowSplash(homeReady, SystemClock.elapsedRealtime() - startedAt)) {
            delay(40L)
        }
        splashVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TinyModelsApp(
            networkMonitor = networkMonitor,
            onHomeReady = { homeReady = true }
        )

        AnimatedVisibility(
            visible = splashVisible,
            exit = fadeOut(animationSpec = tween(420))
        ) {
            AppSplashScreen(
                // Swallow touches for as long as the splash covers the app.
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}
