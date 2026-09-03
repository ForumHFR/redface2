package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.PostHeaderEmphasis
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.seedArgb
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.LagTone
import fr.forumhfr.redface2.core.ui.post.ModerationHighlightColors
import fr.forumhfr.redface2.core.ui.post.moderationHighlightColors
import fr.forumhfr.redface2.core.ui.post.postHeaderColors
import fr.forumhfr.redface2.core.ui.post.spoilerContainerColor
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RedfaceColorSchemeTest {

    @Test
    fun `legacy manual schemes stay byte-identical for historical material-tinted combinations`() {
        assertSame(RedfaceLightColorScheme, buildScheme(AccentPreset.ROSE, darkTheme = false))
        assertSame(RedfaceDarkColorScheme, buildScheme(AccentPreset.ROSE, darkTheme = true))
        assertSame(RedfaceRedLightColorScheme, buildScheme(AccentPreset.ROUGE_REDFACE1, darkTheme = false))
        assertSame(RedfaceRedDarkColorScheme, buildScheme(AccentPreset.ROUGE_REDFACE1, darkTheme = true))
        assertSame(
            RedfaceAmoledColorScheme,
            buildScheme(AccentPreset.ROSE, darkTheme = true, darkSurfaceTone = DarkSurfaceTone.AMOLED),
        )
    }

    @Test
    fun `red AMOLED keeps the red seed and only switches to black surfaces`() {
        val scheme = buildScheme(
            preset = AccentPreset.ROUGE_REDFACE1,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        )
        val customRedScheme = buildScheme(
            rgb = AccentPreset.ROUGE_REDFACE1.seedRgb,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        )

        assertNotSame(RedfaceAmoledColorScheme, scheme)
        assertNotEquals(RedfaceAmoledColorScheme.primary, scheme.primary)
        assertNotEquals(LegacyRoseAmoledOnSurface, scheme.onSurface)
        assertEquals(customRedScheme.onSurface, scheme.onSurface)
        assertContrast("red AMOLED onSurface/black", scheme.onSurface, Color.Black, MIN_TEXT_CONTRAST)
        assertContrast("red AMOLED onBackground/black", scheme.onBackground, Color.Black, MIN_TEXT_CONTRAST)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.background)
        assertAmoledSurfaceLadder("red AMOLED", scheme)
        assertEquals(Color.Transparent, scheme.surfaceTint)
    }

    @Test
    fun `blue AMOLED keeps generated seed text and tinted near-black surfaces`() {
        val materialTinted = buildScheme(preset = AccentPreset.BLUE, darkTheme = true)
        val blueAmoled = buildScheme(
            preset = AccentPreset.BLUE,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        )
        val redAmoled = buildScheme(
            preset = AccentPreset.ROUGE_REDFACE1,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        )

        assertEquals(materialTinted.onSurface, blueAmoled.onSurface)
        assertEquals(materialTinted.onBackground, blueAmoled.onBackground)
        assertEquals(materialTinted.onSurfaceVariant, blueAmoled.onSurfaceVariant)
        assertEquals(materialTinted.outline, blueAmoled.outline)
        assertEquals(materialTinted.outlineVariant, blueAmoled.outlineVariant)
        assertEquals(materialTinted.inverseSurface, blueAmoled.inverseSurface)
        assertEquals(materialTinted.inverseOnSurface, blueAmoled.inverseOnSurface)
        assertContrast("blue AMOLED onSurface/black", blueAmoled.onSurface, Color.Black, MIN_TEXT_CONTRAST)
        assertContrast("blue AMOLED onBackground/black", blueAmoled.onBackground, Color.Black, MIN_TEXT_CONTRAST)
        assertEquals(Color.Black, blueAmoled.surface)
        assertEquals(Color.Black, blueAmoled.background)
        assertEquals(Color.Black, blueAmoled.surfaceDim)
        assertEquals(Color.Black, blueAmoled.surfaceContainerLowest)
        assertAmoledSurfaceLadder("blue AMOLED", blueAmoled)
        assertNotEquals(LegacyRoseAmoledSurfaceVariant, blueAmoled.surfaceVariant)
        assertNotEquals(redAmoled.surfaceVariant, blueAmoled.surfaceVariant)
        assertContainerLift("blue AMOLED surfaceVariant", blueAmoled.surfaceVariant, Color.Black)
        assertContainerLift("blue AMOLED surfaceContainer", blueAmoled.surfaceContainer, Color.Black)
        assertContainerLift("blue AMOLED surfaceBright", blueAmoled.surfaceBright, blueAmoled.surfaceContainer)
    }

    @Test
    fun `surface tones rewrite only the neutral surfaces`() {
        val rf1Gray = buildScheme(
            preset = AccentPreset.BLUE,
            darkTheme = false,
            lightSurfaceTone = LightSurfaceTone.REDFACE1_GRAY,
        )
        val white = buildScheme(AccentPreset.BLUE, darkTheme = false, lightSurfaceTone = LightSurfaceTone.WHITE)
        val amoled = buildScheme(AccentPreset.BLUE, darkTheme = true, darkSurfaceTone = DarkSurfaceTone.AMOLED)

        assertEquals(Rf1GraySurface, rf1Gray.surface)
        assertEquals(Rf1GraySurface, rf1Gray.background)
        assertEquals(WhiteSurface, rf1Gray.surfaceContainer)
        assertEquals(Color.Transparent, rf1Gray.surfaceTint)

        assertEquals(WhiteSurface, white.surface)
        assertEquals(WhiteSurface, white.surfaceContainer)
        assertEquals(WhiteContainerHighest, white.surfaceContainerHighest)
        assertEquals(Color.Transparent, white.surfaceTint)

        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.background)
        assertNotEquals(LegacyRoseAmoledContainer, amoled.surfaceContainer)
        assertAmoledSurfaceLadder("AMOLED", amoled)
        assertContainerLift("AMOLED container", amoled.surfaceContainer, Color.Black)
        assertEquals(Color.Transparent, amoled.surfaceTint)
    }

    @Test
    fun `foreground and link contrast holds across presets customs and surface tones`() {
        allSchemes().forEach { (name, scheme) ->
            assertContrast("$name onSurface/surface", scheme.onSurface, scheme.surface, MIN_TEXT_CONTRAST)
            assertContrast(
                "$name onSecondaryContainer/secondaryContainer",
                scheme.onSecondaryContainer,
                scheme.secondaryContainer,
                MIN_TEXT_CONTRAST,
            )
            assertContrast("$name primary/surface", scheme.primary, scheme.surface, MIN_ACCENT_CONTRAST)
            assertContrast("$name primary/post", scheme.primary, scheme.surfaceContainer, MIN_LINK_CONTRAST)
        }
    }

    @Test
    fun `vivid post header uses the solid primary pair with readable contrast`() {
        allSchemes().forEach { (name, scheme) ->
            val subtle = postHeaderColors(PostHeaderEmphasis.SUBTLE, scheme)
            val vivid = postHeaderColors(PostHeaderEmphasis.VIVID, scheme)

            assertNotEquals("$name vivid/subtle container", subtle.containerColor, vivid.containerColor)
            assertEquals("$name vivid container", scheme.primary, vivid.containerColor)
            assertEquals("$name vivid content", scheme.onPrimary, vivid.contentColor)
            assertContrast("$name vivid post header", vivid.contentColor, vivid.containerColor, MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `spoiler quote and code containers stay distinguishable from the post background`() {
        allSchemes().forEach { (name, scheme) ->
            val postBackground = scheme.surfaceContainer
            val spoiler = spoilerContainerColor(scheme)
            val quoteAndCode = scheme.surfaceContainerHighest

            assertContainerLift("$name spoiler", spoiler, postBackground)
            assertContainerLift("$name quote-code", quoteAndCode, postBackground)
            assertNotEquals("$name spoiler and quote-code", spoiler, quoteAndCode)
        }
    }

    @Test
    fun `slate tertiary and lag tone roles hold across generated schemes`() {
        val generatedLight = buildScheme(
            preset = AccentPreset.BLUE,
            darkTheme = false,
            lightSurfaceTone = LightSurfaceTone.WHITE,
        )
        val generatedDark = buildScheme(AccentPreset.BLUE, darkTheme = true)

        assertEquals(SlateTertiaryContainerLight, generatedLight.tertiaryContainer)
        assertEquals(SlateTertiaryContainerDark, generatedDark.tertiaryContainer)
        allSchemes().forEach { (name, scheme) ->
            LagTone.entries.forEach { tone ->
                val colors = lagToneColors(tone, scheme)

                assertContrast("$name $tone", colors.content, colors.container, MIN_TEXT_CONTRAST)
            }
        }
    }

    @Test
    fun `neutral seeds keep generated primary nearly gray`() {
        val neutralLight = buildScheme(preset = AccentPreset.NEUTRAL, darkTheme = false)
        val neutralDark = buildScheme(preset = AccentPreset.NEUTRAL, darkTheme = true)
        val customGrayLight = buildScheme(rgb = 0x808080, darkTheme = false)
        val customGrayDark = buildScheme(rgb = 0x808080, darkTheme = true)

        listOf(
            "neutral light" to neutralLight.primary,
            "neutral dark" to neutralDark.primary,
            "custom gray light" to customGrayLight.primary,
            "custom gray dark" to customGrayDark.primary,
        ).forEach { (name, color) ->
            assertLowSaturation(name, color)
        }
    }

    @Test
    fun `colored generated presets still use tonal spot primaries`() {
        val light = buildScheme(preset = AccentPreset.TEAL, darkTheme = false)
        val dark = buildScheme(preset = AccentPreset.TEAL, darkTheme = true)

        assertEquals(tonalSpotPrimary(AccentPreset.TEAL, darkTheme = false), light.primary)
        assertEquals(tonalSpotPrimary(AccentPreset.TEAL, darkTheme = true), dark.primary)
    }

    @Test
    fun `fixed highlight palettes stay visible on white RF1 gray AMOLED and generated surfaces`() {
        representativeSurfaces().forEach { (name, surface) ->
            FlagType.entries
                .map { FlagPalette.colorFor(it) }
                .forEach { flagColor ->
                    assertContrast("$name flag", flagColor, surface, MIN_MARKER_CONTRAST)
                }

            val ego = egoHighlightColors(surface)
            assertContainerLift("$name ego quote", ego.quoteContainer, surface)
            assertContainerLift("$name ego post", ego.postContainer, surface)

            val moderation = moderationHighlightColors(surface)
            moderation.containers().forEach { container ->
                assertContrast("$name moderation", moderation.onModeration, container, MIN_TEXT_CONTRAST)
                assertContrast("$name moderation link", moderation.linkColor, container, MIN_ACCENT_CONTRAST)
            }

            val creator = creatorGoldColors(surface)
            assertContrast("$name creator base", creator.base, surface, MIN_LARGE_BOLD_CONTRAST)
            assertContrast("$name creator highlight", creator.highlight, surface, MIN_LARGE_BOLD_CONTRAST)
        }
    }

    private fun buildScheme(
        preset: AccentPreset,
        darkTheme: Boolean,
        lightSurfaceTone: LightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
        darkSurfaceTone: DarkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    ): ColorScheme = buildRedfaceColorScheme(
        RedfaceColorSchemeOptions(
            accent = ThemeAccent.Preset(preset),
            darkTheme = darkTheme,
            lightSurfaceTone = lightSurfaceTone,
            darkSurfaceTone = darkSurfaceTone,
        ),
    )

    private fun buildScheme(
        rgb: Int,
        darkTheme: Boolean,
        lightSurfaceTone: LightSurfaceTone = LightSurfaceTone.MATERIAL_TINTED,
        darkSurfaceTone: DarkSurfaceTone = DarkSurfaceTone.MATERIAL_TINTED,
    ): ColorScheme = buildRedfaceColorScheme(
        RedfaceColorSchemeOptions(
            accent = ThemeAccent.Custom(rgb = rgb),
            darkTheme = darkTheme,
            lightSurfaceTone = lightSurfaceTone,
            darkSurfaceTone = darkSurfaceTone,
        ),
    )

    private fun allSchemes(): List<Pair<String, ColorScheme>> {
        val cases = mutableListOf<Pair<String, ColorScheme>>()
        AccentPreset.entries.forEach { preset ->
            LightSurfaceTone.entries.forEach { tone ->
                cases += "$preset light $tone" to buildScheme(preset, darkTheme = false, lightSurfaceTone = tone)
            }
            DarkSurfaceTone.entries.forEach { tone ->
                cases += "$preset dark $tone" to buildScheme(preset, darkTheme = true, darkSurfaceTone = tone)
            }
        }
        customRgbs.forEach { rgb ->
            LightSurfaceTone.entries.forEach { tone ->
                cases += "custom $rgb light $tone" to buildScheme(rgb, darkTheme = false, lightSurfaceTone = tone)
            }
            DarkSurfaceTone.entries.forEach { tone ->
                cases += "custom $rgb dark $tone" to buildScheme(rgb, darkTheme = true, darkSurfaceTone = tone)
            }
        }
        return cases
    }

    private fun representativeSurfaces(): List<Pair<String, Color>> = listOf(
        "white" to Color.White,
        "RF1 gray" to Rf1GraySurface,
        "AMOLED" to Color.Black,
        "red AMOLED" to buildScheme(
            preset = AccentPreset.ROUGE_REDFACE1,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        ).surface,
        "blue AMOLED" to buildScheme(
            preset = AccentPreset.BLUE,
            darkTheme = true,
            darkSurfaceTone = DarkSurfaceTone.AMOLED,
        ).surface,
        "custom black light" to buildScheme(CUSTOM_BLACK_RGB, darkTheme = false).surface,
        "custom white dark" to buildScheme(CUSTOM_WHITE_RGB, darkTheme = true).surface,
    )

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Float) {
        val contrast = contrastRatio(foreground, background)
        assertTrue("$name contrast was $contrast", contrast >= minimum)
    }

    private fun assertContainerLift(name: String, container: Color, surface: Color) {
        val distance = rgbDistance(container, surface)
        assertTrue("$name RGB distance was $distance", distance >= MIN_CONTAINER_DISTANCE)
    }

    private fun assertLowSaturation(name: String, color: Color) {
        val spread = colorChannelSpread(color)
        assertTrue("$name channel spread was $spread", spread <= MAX_NEUTRAL_CHANNEL_SPREAD)
    }

    private fun tonalSpotPrimary(preset: AccentPreset, darkTheme: Boolean): Color =
        SchemeTonalSpot(
            sourceColorHct = Hct.fromInt(ThemeAccent.Preset(preset).seedArgb()),
            isDark = darkTheme,
            contrastLevel = 0.0,
        ).primary.toColorForTest()

    private fun assertAmoledSurfaceLadder(name: String, scheme: ColorScheme) {
        assertIncreasingDistance(
            name,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "surfaceBright" to scheme.surfaceBright,
        )
        assertNotEquals("$name surfaceVariant/surfaceContainer", scheme.surfaceVariant, scheme.surfaceContainer)
        assertNotEquals("$name surfaceVariant/surfaceBright", scheme.surfaceVariant, scheme.surfaceBright)
        assertContainerLift(
            "$name spoiler surfaceBright/containerLow",
            scheme.surfaceBright,
            scheme.surfaceContainerLow,
        )
    }

    private fun assertIncreasingDistance(name: String, vararg colors: Pair<String, Color>) {
        colors
            .map { (role, color) -> role to rgbDistance(color, Color.Black) }
            .zipWithNext()
            .forEach { (current, next) ->
                assertTrue(
                    "$name ${current.first} distance ${current.second} >= ${next.first} distance ${next.second}",
                    current.second < next.second,
                )
            }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + LUMINANCE_OFFSET) / (dark + LUMINANCE_OFFSET)
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }

    private fun colorChannelSpread(color: Color): Float =
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)

    private fun Int.toColorForTest(): Color = Color(toLong() and ARGB_LONG_MASK)

    private fun ModerationHighlightColors.containers(): List<Color> =
        listOf(headerContainer, bodyContainer, subSurfaceContainer)

    private companion object {
        const val CUSTOM_BLACK_RGB = 0x000000
        const val CUSTOM_WHITE_RGB = 0xFFFFFF
        const val CUSTOM_MUTED_RGB = 0x123456
        const val CUSTOM_MAGENTA_RGB = 0xFF00FF
        val customRgbs = listOf(CUSTOM_BLACK_RGB, CUSTOM_WHITE_RGB, CUSTOM_MUTED_RGB, CUSTOM_MAGENTA_RGB)

        val Rf1GraySurface = Color(0xFFF0F0F0)
        val WhiteSurface = Color.White
        val WhiteContainerHighest = Color(0xFFF2F2F2)
        val LegacyRoseAmoledOnSurface = Color(0xFFF0DEDC)
        val LegacyRoseAmoledSurfaceVariant = Color(0xFF4A3A3A)
        val LegacyRoseAmoledContainer = Color(0xFF0B0909)
        val SlateTertiaryContainerLight = Color(0xFFDAE5F1)
        val SlateTertiaryContainerDark = Color(0xFF3E4854)

        const val MIN_TEXT_CONTRAST = 4.5f
        const val MIN_ACCENT_CONTRAST = 3f
        const val MIN_LINK_CONTRAST = 3f
        const val MIN_MARKER_CONTRAST = 1.5f
        const val MIN_LARGE_BOLD_CONTRAST = 3f
        const val MIN_CONTAINER_DISTANCE = 0.015f
        const val MAX_NEUTRAL_CHANNEL_SPREAD = 0.02f
        const val LUMINANCE_OFFSET = 0.05f
        const val ARGB_LONG_MASK = 0xFFFF_FFFFL
    }
}
