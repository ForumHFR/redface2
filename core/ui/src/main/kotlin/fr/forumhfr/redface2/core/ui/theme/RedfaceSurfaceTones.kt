package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone

/** Applies the user-selected neutral surface tone while keeping chromatic roles intact. */
internal fun ColorScheme.withRedfaceSurfaceTones(
    darkTheme: Boolean,
    lightSurfaceTone: LightSurfaceTone,
    darkSurfaceTone: DarkSurfaceTone,
): ColorScheme = if (darkTheme) {
    withDarkSurfaceTone(darkSurfaceTone)
} else {
    withLightSurfaceTone(lightSurfaceTone)
}

/** Redface slate tertiary, forced after dynamic or seeded generation to avoid yellow functional roles. */
internal fun ColorScheme.withRedfaceSlateTertiary(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        copy(
            tertiary = SlateTertiaryDark,
            onTertiary = SlateOnTertiaryDark,
            tertiaryContainer = SlateTertiaryContainerDark,
            onTertiaryContainer = SlateOnTertiaryContainerDark,
        )
    } else {
        copy(
            tertiary = SlateTertiaryLight,
            onTertiary = SlateOnTertiaryLight,
            tertiaryContainer = SlateTertiaryContainerLight,
            onTertiaryContainer = SlateOnTertiaryContainerLight,
        )
    }

private fun ColorScheme.withLightSurfaceTone(tone: LightSurfaceTone): ColorScheme = when (tone) {
    LightSurfaceTone.MATERIAL_TINTED -> this
    LightSurfaceTone.WHITE -> copy(
        background = WhiteSurface,
        onBackground = OnLightSurface,
        surface = WhiteSurface,
        onSurface = OnLightSurface,
        surfaceVariant = WhiteSurfaceVariant,
        onSurfaceVariant = OnLightSurfaceVariant,
        outlineVariant = WhiteOutlineVariant,
        surfaceDim = WhiteSurfaceDim,
        surfaceBright = WhiteSurface,
        surfaceContainerLowest = WhiteSurface,
        surfaceContainerLow = WhiteSurfaceContainerLow,
        surfaceContainer = WhiteSurface,
        surfaceContainerHigh = WhiteSurfaceContainerHigh,
        surfaceContainerHighest = WhiteSurfaceContainerHighest,
        surfaceTint = Color.Transparent,
    )
    LightSurfaceTone.REDFACE1_GRAY -> copy(
        background = Rf1GraySurface,
        onBackground = OnLightSurface,
        surface = Rf1GraySurface,
        onSurface = OnLightSurface,
        surfaceVariant = Rf1GraySurfaceVariant,
        onSurfaceVariant = OnLightSurfaceVariant,
        outlineVariant = Rf1GrayOutlineVariant,
        surfaceDim = Rf1GraySurfaceDim,
        surfaceBright = Rf1GraySurface,
        surfaceContainerLowest = LightPostSurface,
        surfaceContainerLow = Rf1GraySurfaceContainerLow,
        surfaceContainer = LightPostSurface,
        surfaceContainerHigh = Rf1GraySurfaceContainerHigh,
        surfaceContainerHighest = Rf1GraySurfaceContainerHighest,
        surfaceTint = Color.Transparent,
    )
}

private fun ColorScheme.withDarkSurfaceTone(tone: DarkSurfaceTone): ColorScheme = when (tone) {
    DarkSurfaceTone.MATERIAL_TINTED -> this
    DarkSurfaceTone.AMOLED -> withGeneratedAmoledSurfaceTone()
}

private fun ColorScheme.withGeneratedAmoledSurfaceTone(): ColorScheme = copy(
    background = AmoledSurface,
    surface = AmoledSurface,
    surfaceDim = AmoledSurface,
    surfaceContainerLowest = AmoledSurface,
    surfaceContainerLow = scaleTowardBlack(surfaceContainerLow, AMOLED_CONTAINER_LOW_LIFT),
    surfaceContainer = scaleTowardBlack(surfaceContainer, AMOLED_CONTAINER_LIFT),
    surfaceContainerHigh = scaleTowardBlack(surfaceContainerHigh, AMOLED_CONTAINER_HIGH_LIFT),
    surfaceContainerHighest = scaleTowardBlack(surfaceContainerHighest, AMOLED_CONTAINER_HIGHEST_LIFT),
    surfaceBright = scaleTowardBlack(surfaceBright, AMOLED_SURFACE_BRIGHT_LIFT),
    surfaceVariant = scaleTowardBlack(surfaceVariant, AMOLED_SURFACE_VARIANT_LIFT),
    surfaceTint = Color.Transparent,
)

/**
 * Scales generated sRGB surface roles toward black with byte-predictable near-black lifts.
 *
 * Compose `Color.lerp` converts through Oklab before interpolating, which compresses very dark
 * colors and flattens the AMOLED ladder. These constants are authored as sRGB fractions so
 * generated tone 10/12/17/... surfaces stay deterministic and visibly distinct around pure black.
 */
private fun scaleTowardBlack(color: Color, fraction: Float): Color = Color(
    red = color.red * fraction,
    green = color.green * fraction,
    blue = color.blue * fraction,
    alpha = 1f,
)

private val SlateTertiaryLight = Color(0xFF56616C)
private val SlateOnTertiaryLight = Color(0xFFFFFFFF)
private val SlateTertiaryContainerLight = Color(0xFFDAE5F1)
private val SlateOnTertiaryContainerLight = Color(0xFF131C26)
private val SlateTertiaryDark = Color(0xFFBEC8D4)
private val SlateOnTertiaryDark = Color(0xFF28323D)
private val SlateTertiaryContainerDark = Color(0xFF3E4854)
private val SlateOnTertiaryContainerDark = Color(0xFFDAE4F1)

private val WhiteSurface = Color(0xFFFFFFFF)
private val WhiteSurfaceDim = Color(0xFFE5E5E5)
private val WhiteSurfaceVariant = Color(0xFFE6E6E6)
private val WhiteOutlineVariant = Color(0xFFC8C8C8)
private val WhiteSurfaceContainerLow = Color(0xFFF7F7F7)
private val WhiteSurfaceContainerHigh = Color(0xFFF5F5F5)
private val WhiteSurfaceContainerHighest = Color(0xFFF2F2F2)

private val Rf1GraySurface = Color(0xFFF0F0F0)
private val LightPostSurface = Color(0xFFFFFFFF)
private val Rf1GraySurfaceDim = Color(0xFFDADADA)
private val Rf1GraySurfaceVariant = Color(0xFFE1E3E6)
private val Rf1GrayOutlineVariant = Color(0xFFC5C7CA)
private val Rf1GraySurfaceContainerLow = Color(0xFFFAFAFA)
private val Rf1GraySurfaceContainerHigh = Color(0xFFF7F7F7)
private val Rf1GraySurfaceContainerHighest = Color(0xFFE8E8E8)

private val AmoledSurface = Color(0xFF000000)

private val OnLightSurface = Color(0xFF1B1B1B)
private val OnLightSurfaceVariant = Color(0xFF46474A)

private const val AMOLED_CONTAINER_LOW_LIFT = 0.22f
private const val AMOLED_CONTAINER_LIFT = 0.32f
private const val AMOLED_CONTAINER_HIGH_LIFT = 0.43f
private const val AMOLED_CONTAINER_HIGHEST_LIFT = 0.55f
private const val AMOLED_SURFACE_BRIGHT_LIFT = 0.72f
private const val AMOLED_SURFACE_VARIANT_LIFT = 0.60f
