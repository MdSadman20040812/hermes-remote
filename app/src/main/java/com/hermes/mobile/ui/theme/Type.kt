package com.hermes.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * One family, a tight scale.
 *
 * A control surface has far more type elements than a marketing page, so the
 * ratio between steps stays near 1.15 — exaggerated contrast reads as noise
 * when a screen carries a title, a chip, four labels and a wall of body at
 * once. Sizes are sp throughout, so the system font-size setting still works.
 *
 * The previous scale defined seven roles and let the other eleven fall back to
 * Material's defaults, which is why a `titleSmall` heading and a `bodyMedium`
 * paragraph could end up the same size. Every role is spelled out here.
 */
/** Code, terminal scrollback, command strings, token counts — never chrome. */
val HermesMono: FontFamily = HermesMonoFamily

/** Trim the extra leading Compose adds by default; dense UI needs the pixels. */
private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
    family: FontFamily = FontFamily.Default,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Trim,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * The scale, bound to a chosen family.
 *
 * Typography used to be a single `val`, which meant the family was fixed at
 * class-init and a user font choice could not reach it. It is a function now so
 * the theme can rebuild the scale when the preference changes.
 */
fun hermesTypography(sans: FontFamily = FontFamily.Default) = Typography(
    displayLarge = style(44, 52, FontWeight.ExtraBold, -1.0, family = sans),
    displayMedium = style(34, 42, FontWeight.Bold, -0.6, family = sans),
    displaySmall = style(28, 36, FontWeight.Bold, -0.4, family = sans),

    headlineLarge = style(26, 34, FontWeight.Bold, -0.4, family = sans),
    headlineMedium = style(23, 30, FontWeight.SemiBold, -0.3, family = sans),
    headlineSmall = style(20, 27, FontWeight.SemiBold, -0.2, family = sans),

    titleLarge = style(19, 26, FontWeight.SemiBold, -0.1, family = sans),
    titleMedium = style(16, 23, FontWeight.SemiBold, family = sans),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1, family = sans),

    bodyLarge = style(16, 25, family = sans),
    bodyMedium = style(14, 21, tracking = 0.1, family = sans),
    bodySmall = style(12, 18, tracking = 0.2, family = sans),

    labelLarge = style(14, 20, FontWeight.Medium, 0.1, family = sans),
    labelMedium = style(12, 16, FontWeight.Medium, 0.4, family = sans),
    labelSmall = style(11, 15, FontWeight.Medium, 0.4, family = sans),
)

/** Fenced code and inline spans inside a transcript message. */
val HermesCodeStyle = style(12, 18, family = HermesMono)

/** Terminal scrollback — one step tighter, because 80 columns has to fit. */
val HermesTerminalStyle = style(11, 15, family = HermesMono)
