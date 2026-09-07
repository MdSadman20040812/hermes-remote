package com.hermes.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------
//
// Hermes' identity is the desktop dashboard's: near-black ink, an electric
// violet accent, GitHub-family neutrals. The mobile client is the same product
// on a smaller screen, so the world is preserved and only the execution is
// raised. The two schemes are authored independently — dark is not an invert
// of light, and light is not a wash of dark — because the real use scene is a
// phone glanced at in a dark room and, less often, one held in daylight.

/** Signature violet, at the tone each scheme needs. */
private val VioletLight = Color(0xFF6A45E8)
private val VioletDark = Color(0xFFA98BFF)

private val DarkColorScheme = darkColorScheme(
    primary = VioletDark,
    onPrimary = Color(0xFF24104F),
    primaryContainer = Color(0xFF392A7A),
    onPrimaryContainer = Color(0xFFE4DAFF),
    inversePrimary = VioletLight,

    secondary = Color(0xFF7CC0FF),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF16436E),
    onSecondaryContainer = Color(0xFFCFE5FF),

    tertiary = Color(0xFF5FE07A),
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF14532A),
    onTertiaryContainer = Color(0xFFB8F5C4),

    error = Color(0xFFFF897D),
    onError = Color(0xFF5F1412),
    errorContainer = Color(0xFF7A2420),
    onErrorContainer = Color(0xFFFFDAD5),

    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFF9FABB8),

    surfaceContainerLowest = Color(0xFF07090E),
    surfaceContainerLow = Color(0xFF11151C),
    surfaceContainer = Color(0xFF161B23),
    surfaceContainerHigh = Color(0xFF1C222B),
    surfaceContainerHighest = Color(0xFF232A34),

    outline = Color(0xFF6C7783),
    outlineVariant = Color(0xFF2B333D),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF16191F),
)

private val LightColorScheme = lightColorScheme(
    primary = VioletLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF21005E),
    inversePrimary = VioletDark,

    secondary = Color(0xFF0B62C4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E6FF),
    onSecondaryContainer = Color(0xFF001C3B),

    tertiary = Color(0xFF176F33),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB7F0C4),
    onTertiaryContainer = Color(0xFF00210C),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF16191F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16191F),
    surfaceVariant = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF515964),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFE9ECF0),
    surfaceContainerHighest = Color(0xFFE2E6EB),

    outline = Color(0xFF757D88),
    outlineVariant = Color(0xFFD6DBE1),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFF1F3F6),
)

// ---------------------------------------------------------------------------
// Roles Material does not have
// ---------------------------------------------------------------------------

/**
 * Semantic colours outside the M3 scheme, provided through a CompositionLocal
 * so they resolve light/dark exactly like the built-in roles do.
 *
 * [warning] exists because an approval request is not an *error*. Painting a
 * routine "may I run this command?" in error red trains the user to dismiss
 * the colour that should mean something has gone wrong. Approvals are amber —
 * blocked, waiting on you — and red stays reserved for actual failures.
 *
 * [code] is the surface behind fenced code and terminal scrollback. It sits a
 * step off the neutral ramp so a code block reads as a different material from
 * the message that contains it.
 */
@Immutable
data class HermesSemantics(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val code: Color,
    val onCode: Color,
    val codeBorder: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    /** Live-connection indicator; deliberately not `tertiary`, which also means "done". */
    val online: Color,
    val offline: Color,
)

private val DarkSemantics = HermesSemantics(
    warning = Color(0xFFF0C14B),
    onWarning = Color(0xFF3D2C00),
    warningContainer = Color(0xFF4A3800),
    onWarningContainer = Color(0xFFFFE29A),
    success = Color(0xFF5FE07A),
    successContainer = Color(0xFF14532A),
    onSuccessContainer = Color(0xFFB8F5C4),
    code = Color(0xFF10151C),
    onCode = Color(0xFFD3DCE6),
    codeBorder = Color(0xFF262E38),
    diffAdded = Color(0xFF56D364),
    diffRemoved = Color(0xFFFF7B72),
    online = Color(0xFF3FB950),
    offline = Color(0xFF6C7783),
)

private val LightSemantics = HermesSemantics(
    warning = Color(0xFF8A6100),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFEFC4),
    onWarningContainer = Color(0xFF2A1D00),
    success = Color(0xFF176F33),
    successContainer = Color(0xFFB7F0C4),
    onSuccessContainer = Color(0xFF00210C),
    code = Color(0xFFF3F5F8),
    onCode = Color(0xFF232830),
    codeBorder = Color(0xFFDCE1E7),
    diffAdded = Color(0xFF11692C),
    diffRemoved = Color(0xFFB3261E),
    online = Color(0xFF1A7F37),
    offline = Color(0xFF8B949E),
)

val LocalHermesSemantics = staticCompositionLocalOf { DarkSemantics }

/** `MaterialTheme.hermes.warning` reads like the built-in roles do. */
val MaterialTheme.hermes: HermesSemantics
    @Composable get() = LocalHermesSemantics.current

// ---------------------------------------------------------------------------

@Composable
fun HermesMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You is off by default. The identity of this app is the same
     * violet the desktop dashboard uses — deriving it from wallpaper would make
     * the phone and the PC look like two different products. It is offered as
     * an opt-in rather than removed.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Edge-to-edge: the system bars are transparent, so their icon colour has to
    // follow the scheme or they vanish into the background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalHermesSemantics provides if (darkTheme) DarkSemantics else LightSemantics,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
