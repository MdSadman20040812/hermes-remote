package com.hermes.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Hermes-brand colour system
val HermesDeep = Color(0xFF0D1117)
val HermesSurface = Color(0xFF161B22)
val HermesSurfaceVariant = Color(0xFF21262D)
val HermesBorder = Color(0xFF30363D)
val HermesPrimary = Color(0xFF7C5CFC)
val HermesPrimaryContainer = Color(0xFF4B3FD1)
val HermesSecondary = Color(0xFF58A6FF)
val HermesAccent = Color(0xFF39D353)
val HermesError = Color(0xFFF85149)
val HermesWarning = Color(0xFFD29922)
val HermesOnPrimary = Color.White
val HermesOnSurface = Color(0xFFE6EDF3)
val HermesOnSurfaceVariant = Color(0xFF8B949E)

private val DarkColorScheme = darkColorScheme(
    primary = HermesPrimary,
    onPrimary = HermesOnPrimary,
    primaryContainer = HermesPrimaryContainer,
    secondary = HermesSecondary,
    tertiary = HermesAccent,
    error = HermesError,
    surface = HermesSurface,
    onSurface = HermesOnSurface,
    surfaceVariant = HermesSurfaceVariant,
    onSurfaceVariant = HermesOnSurfaceVariant,
    outline = HermesBorder,
    background = HermesDeep,
    onBackground = HermesOnSurface
)

private val LightColorScheme = lightColorScheme(
    primary = HermesPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    secondary = HermesSecondary,
    tertiary = HermesAccent,
    error = HermesError,
    surface = Color(0xFFFAFBFC),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFEFF1F4),
    onSurfaceVariant = Color(0xFF656D76),
    outline = HermesBorder,
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328)
)

@Composable
fun HermesMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = HermesTypography,
        shapes = HermesShapes,
        content = content
    )
}
