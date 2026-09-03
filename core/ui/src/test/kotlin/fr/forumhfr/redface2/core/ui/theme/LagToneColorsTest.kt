package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.forumhfr.redface2.core.model.LagTone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #814 — pure-JVM guard on [lagToneColors] : the tone → Material role mapping, and the two properties
 * the mapping was chosen for, checked on EVERY static scheme (rose light/dark, AMOLED, « Rouge
 * REDFACE1 » light/dark) : the `+N` label stays legible on its container (WCAG ≥ 4.5:1, the pill uses
 * `labelSmall`), and the three containers stay visually distinguishable from each other — the trap
 * being light-theme tonal containers that all collapse onto the same pale rose.
 */
class LagToneColorsTest {

    @Test
    fun `LOW is the neutral surfaceVariant pair`() {
        staticSchemes.forEach { (name, scheme) ->
            val colors = lagToneColors(LagTone.LOW, scheme)
            assertEquals("$name container", scheme.surfaceVariant, colors.container)
            assertEquals("$name content", scheme.onSurfaceVariant, colors.content)
        }
    }

    @Test
    fun `MEDIUM is the tonal tertiary pair`() {
        staticSchemes.forEach { (name, scheme) ->
            val colors = lagToneColors(LagTone.MEDIUM, scheme)
            assertEquals("$name container", scheme.tertiaryContainer, colors.container)
            assertEquals("$name content", scheme.onTertiaryContainer, colors.content)
        }
    }

    @Test
    fun `HIGH is the solid error pair, not the tonal errorContainer`() {
        staticSchemes.forEach { (name, scheme) ->
            val colors = lagToneColors(LagTone.HIGH, scheme)
            assertEquals("$name container", scheme.error, colors.container)
            assertEquals("$name content", scheme.onError, colors.content)
        }
    }

    @Test
    fun `content stays legible on its container in every static scheme`() {
        staticSchemes.forEach { (name, scheme) ->
            LagTone.entries.forEach { tone ->
                val colors = lagToneColors(tone, scheme)
                val ratio = contrastRatio(colors.content, colors.container)
                assertTrue(
                    "$name / $tone : contrast $ratio < $MIN_TEXT_CONTRAST",
                    ratio >= MIN_TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun `the three containers stay distinguishable from each other in every static scheme`() {
        staticSchemes.forEach { (name, scheme) ->
            val containers = LagTone.entries.map { it to lagToneColors(it, scheme).container }
            containers.forEachIndexed { i, (toneA, a) ->
                containers.drop(i + 1).forEach { (toneB, b) ->
                    val distance = rgbDistance(a, b)
                    assertTrue(
                        "$name : $toneA vs $toneB containers too close ($distance < $MIN_CONTAINER_DISTANCE)",
                        distance >= MIN_CONTAINER_DISTANCE,
                    )
                }
            }
        }
    }

    @Test
    fun `containers are opaque so the pill matches over any row surface`() {
        staticSchemes.forEach { (name, scheme) ->
            LagTone.entries.forEach { tone ->
                assertEquals("$name / $tone", 1f, lagToneColors(tone, scheme).container.alpha, 0f)
            }
        }
    }

    /** WCAG 2.x contrast ratio from the relative luminance of the two colours. */
    private fun contrastRatio(first: Color, second: Color): Float {
        val l1 = first.luminance()
        val l2 = second.luminance()
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }

    private companion object {
        /** WCAG AA for normal-size text — the pill label is `labelSmall`, so no large-text discount. */
        const val MIN_TEXT_CONTRAST = 4.5f

        /**
         * The tightest prescribed pair is LOW vs MEDIUM on the dark schemes (`#534342` vs `#3E4854`,
         * ~0.11 in sRGB space) — warm vs cool greys, still distinct in hue.
         */
        const val MIN_CONTAINER_DISTANCE = 0.10f

        val staticSchemes: List<Pair<String, ColorScheme>> = listOf(
            "rose light" to RedfaceLightColorScheme,
            "rose dark" to RedfaceDarkColorScheme,
            "amoled" to RedfaceAmoledColorScheme,
            "rouge light" to RedfaceRedLightColorScheme,
            "rouge dark" to RedfaceRedDarkColorScheme,
        )
    }
}
