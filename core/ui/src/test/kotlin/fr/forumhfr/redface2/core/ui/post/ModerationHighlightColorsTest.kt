package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.graphics.Color
import fr.forumhfr.redface2.core.ui.theme.egoHighlightColors
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1112 — exhaustive JVM coverage of the pure moderation-container palette decision, the claim
 * the KDoc of [moderationHighlightColor] relies on. Mirrors `EgoHighlightColorsTest`: the three
 * surface regimes resolve distinct opaque pinks, the AMOLED regime is keyed on the pure-black
 * surface, and every pink stays visibly separated from the sibling EgoPost blue and EgoQuote
 * violet so dynamic colour can never collapse the moderation signal onto a reading highlight.
 */
class ModerationHighlightColorsTest {

    @Test
    fun `light surface selects the light opaque pink`() {
        val color = moderationHighlightColor(surface = Color.White)

        assertEquals(Color(0xFFF5DDE2), color)
        assertOpaque(color)
    }

    @Test
    fun `dark surface selects the dark opaque pink`() {
        val color = moderationHighlightColor(surface = Color(0xFF121212))

        assertEquals(Color(0xFF3A242B), color)
        assertOpaque(color)
    }

    @Test
    fun `pure black surface selects the AMOLED opaque pink`() {
        val color = moderationHighlightColor(surface = Color(0xFF000000))

        assertEquals(Color(0xFF241218), color)
        assertOpaque(color)
    }

    @Test
    fun `the three regimes resolve mutually distinct pinks`() {
        val light = moderationHighlightColor(surface = Color.White)
        val dark = moderationHighlightColor(surface = Color(0xFF121212))
        val amoled = moderationHighlightColor(surface = Color(0xFF000000))

        assertTrue(rgbDistance(light, dark) >= MIN_REGIME_DISTANCE)
        assertTrue(rgbDistance(dark, amoled) >= MIN_REGIME_DISTANCE)
        assertTrue(rgbDistance(light, amoled) >= MIN_REGIME_DISTANCE)
    }

    @Test
    fun `moderation pink stays distinguishable from the EgoPost and EgoQuote containers`() {
        SURFACES.forEach { surface ->
            val moderation = moderationHighlightColor(surface = surface)
            val ego = egoHighlightColors(surface = surface)

            assertTrue(
                "moderation must retain visible RGB separation from EgoPost",
                rgbDistance(moderation, ego.postContainer) >= MIN_SIBLING_DISTANCE,
            )
            assertTrue(
                "moderation must retain visible RGB separation from EgoQuote",
                rgbDistance(moderation, ego.quoteContainer) >= MIN_SIBLING_DISTANCE,
            )
        }
    }

    private fun assertOpaque(color: Color) {
        assertEquals(1f, color.alpha, 0f)
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }

    private companion object {
        val SURFACES = listOf(Color.White, Color(0xFF121212), Color(0xFF000000))

        // The tightest sibling pair is pink vs EgoQuote violet in the light palette (~0.12 sRGB);
        // the tightest inter-regime pair is dark vs AMOLED (~0.11). 0.04 mirrors the Ego threshold.
        const val MIN_SIBLING_DISTANCE = 0.04f
        const val MIN_REGIME_DISTANCE = 0.04f
    }
}
