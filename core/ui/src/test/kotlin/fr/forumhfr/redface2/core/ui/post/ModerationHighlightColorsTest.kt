package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.forumhfr.redface2.core.ui.theme.egoHighlightColors
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1112 — exact RF1 light colours plus the derived dark/AMOLED semantic palettes. */
class ModerationHighlightColorsTest {

    @Test
    fun `light palette matches RF1 exactly`() {
        assertEquals(
            ModerationHighlightColors(
                headerContainer = Color(0xFFB71C1C),
                bodyContainer = Color(0xFFD32F2F),
                subSurfaceContainer = Color(0xFFC62828),
                onModeration = Color.White,
                onModerationVariant = Color(0xFFFFF8F8),
                linkColor = Color(0xFFFFF9C4),
            ),
            moderationHighlightColors(surface = Color.White),
        )
    }

    @Test
    fun `dark palette stays in the RF1 red family`() {
        assertEquals(
            ModerationHighlightColors(
                headerContainer = Color(0xFF7F1010),
                bodyContainer = Color(0xFF991B1B),
                subSurfaceContainer = Color(0xFF891515),
                onModeration = Color.White,
                onModerationVariant = Color(0xFFFFF8F8),
                linkColor = Color(0xFFFFF9C4),
            ),
            moderationHighlightColors(surface = Color(0xFF121212)),
        )
    }

    @Test
    fun `pure black surface selects the deeper AMOLED palette`() {
        assertEquals(
            ModerationHighlightColors(
                headerContainer = Color(0xFF5D0A0A),
                bodyContainer = Color(0xFF731010),
                subSurfaceContainer = Color(0xFF661010),
                onModeration = Color.White,
                onModerationVariant = Color(0xFFFFF8F8),
                linkColor = Color(0xFFFFF9C4),
            ),
            moderationHighlightColors(surface = Color.Black),
        )
    }

    @Test
    fun `all six roles are opaque in all three regimes`() {
        palettes().forEach { (name, colors) ->
            colors.allRoles().forEach { color ->
                assertEquals("$name role must be opaque", 1f, color.alpha, 0f)
            }
        }
    }

    @Test
    fun `header body and sub-surface stay distinct`() {
        palettes().forEach { (name, colors) ->
            assertNotEquals("$name header/body", colors.headerContainer, colors.bodyContainer)
            assertNotEquals("$name header/sub-surface", colors.headerContainer, colors.subSurfaceContainer)
            assertNotEquals("$name body/sub-surface", colors.bodyContainer, colors.subSurfaceContainer)
        }
    }

    @Test
    fun `every semantic foreground meets WCAG AA on every moderation surface`() {
        palettes().forEach { (name, colors) ->
            val backgrounds = listOf(
                "header" to colors.headerContainer,
                "body" to colors.bodyContainer,
                "sub-surface" to colors.subSurfaceContainer,
            )
            val foregrounds = listOf(
                "onModeration" to colors.onModeration,
                "onModerationVariant" to colors.onModerationVariant,
                "linkColor" to colors.linkColor,
            )
            backgrounds.forEach { (backgroundName, background) ->
                foregrounds.forEach { (foregroundName, foreground) ->
                    val contrast = contrastRatio(foreground, background)
                    assertTrue(
                        "$name $foregroundName/$backgroundName contrast was $contrast",
                        contrast >= MINIMUM_TEXT_CONTRAST,
                    )
                }
            }
        }
    }

    @Test
    fun `moderation surfaces stay separate from EgoPost and EgoQuote`() {
        SURFACES.forEach { surface ->
            val moderation = moderationHighlightColors(surface)
            val ego = egoHighlightColors(surface)
            moderation.containers().forEach { container ->
                assertTrue(rgbDistance(container, ego.postContainer) >= MIN_SIBLING_DISTANCE)
                assertTrue(rgbDistance(container, ego.quoteContainer) >= MIN_SIBLING_DISTANCE)
            }
        }
    }

    @Test
    fun `dark and AMOLED palettes do not collapse together`() {
        val dark = moderationHighlightColors(Color(0xFF121212))
        val amoled = moderationHighlightColors(Color.Black)

        assertNotEquals(dark.headerContainer, amoled.headerContainer)
        assertNotEquals(dark.bodyContainer, amoled.bodyContainer)
        assertNotEquals(dark.subSurfaceContainer, amoled.subSurfaceContainer)
    }

    private fun palettes(): List<Pair<String, ModerationHighlightColors>> = listOf(
        "light" to moderationHighlightColors(Color.White),
        "dark" to moderationHighlightColors(Color(0xFF121212)),
        "AMOLED" to moderationHighlightColors(Color.Black),
    )

    private fun ModerationHighlightColors.containers(): List<Color> =
        listOf(headerContainer, bodyContainer, subSurfaceContainer)

    private fun ModerationHighlightColors.allRoles(): List<Color> = containers() +
        listOf(onModeration, onModerationVariant, linkColor)

    private fun contrastRatio(first: Color, second: Color): Float {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }

    private companion object {
        val SURFACES = listOf(Color.White, Color(0xFF121212), Color.Black)
        const val MINIMUM_TEXT_CONTRAST = 4.5f
        const val MIN_SIBLING_DISTANCE = 0.04f
    }
}
