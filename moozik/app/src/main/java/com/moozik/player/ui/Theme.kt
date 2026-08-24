package com.moozik.player.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Material You theming: on Android 12+ the wallpaper-derived dynamic palette
 * is used (Pixel-style); older devices fall back to hand-picked schemes.
 */
@Composable
fun MoozikTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) androidx.compose.material3.dynamicDarkColorScheme(context)
            else androidx.compose.material3.dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }

    MaterialTheme(colorScheme = colorScheme, typography = MoozikTypography, content = content)
}

private val DarkFallback = darkColorScheme()

private val LightFallback = lightColorScheme()

/** Slight typographic personality: airy display for headers, tight labels. */
val MoozikTypography = androidx.compose.material3.Typography()
