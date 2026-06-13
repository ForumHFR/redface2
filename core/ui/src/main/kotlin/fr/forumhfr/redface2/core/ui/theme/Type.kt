package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

val RedfaceTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * Returns a copy of this [Typography] with the font size and line height of every role multiplied
 * by [factor] (#287 lot C). [factor] is the `FontScalePreference` multiplier, applied on top of the
 * OS font zoom (never instead of it).
 *
 * `letterSpacing` is NOT scaled: M3 expresses it in `sp` but it is conceptually relative to the
 * glyph size, so scaling it would double the effect and loosen the tracking unnaturally.
 *
 * Short-circuits to `this` when [factor] is `1f` (the M preset), so the default path allocates
 * nothing and stays referentially identical.
 */
fun Typography.scaledForReading(factor: Float): Typography {
    if (factor == 1f) return this
    fun TextStyle.scale(): TextStyle = copy(
        fontSize = fontSize.scaleSp(factor),
        lineHeight = lineHeight.scaleSp(factor),
    )
    return copy(
        displayLarge = displayLarge.scale(),
        displayMedium = displayMedium.scale(),
        displaySmall = displaySmall.scale(),
        headlineLarge = headlineLarge.scale(),
        headlineMedium = headlineMedium.scale(),
        headlineSmall = headlineSmall.scale(),
        titleLarge = titleLarge.scale(),
        titleMedium = titleMedium.scale(),
        titleSmall = titleSmall.scale(),
        bodyLarge = bodyLarge.scale(),
        bodyMedium = bodyMedium.scale(),
        bodySmall = bodySmall.scale(),
        labelLarge = labelLarge.scale(),
        labelMedium = labelMedium.scale(),
        labelSmall = labelSmall.scale(),
    )
}

/** Multiplies a specified [TextUnit] by [factor]; an unspecified unit is left untouched. */
private fun TextUnit.scaleSp(factor: Float): TextUnit =
    if (isSpecified) (value * factor).sp else this
