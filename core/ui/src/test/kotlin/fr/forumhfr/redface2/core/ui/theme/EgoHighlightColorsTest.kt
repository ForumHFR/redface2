package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EgoHighlightColorsTest {

    @Test
    fun `light surface selects the light opaque palette`() {
        val colors = egoHighlightColors(surface = Color.White)

        assertEquals(Color(0xFFEDE7FF), colors.quoteContainer)
        assertEquals(Color(0xFFE4EDFF), colors.postContainer)
        assertOpaque(colors)
    }

    @Test
    fun `dark surface selects the dark opaque palette`() {
        val colors = egoHighlightColors(surface = Color(0xFF121212))

        assertEquals(Color(0xFF241C3D), colors.quoteContainer)
        assertEquals(Color(0xFF16233A), colors.postContainer)
        assertOpaque(colors)
    }

    @Test
    fun `pure black surface selects the AMOLED opaque palette`() {
        val colors = egoHighlightColors(surface = Color(0xFF000000))

        assertEquals(Color(0xFF150F28), colors.quoteContainer)
        assertEquals(Color(0xFF0A1526), colors.postContainer)
        assertOpaque(colors)
    }

    @Test
    fun `quote container remains distinguishable when nested in an EgoPost container`() {
        val palettes = listOf(
            egoHighlightColors(surface = Color.White),
            egoHighlightColors(surface = Color(0xFF121212)),
            egoHighlightColors(surface = Color(0xFF000000)),
        )

        palettes.forEach { colors ->
            assertTrue(
                "quoteContainer must retain visible RGB separation over postContainer",
                rgbDistance(colors.quoteContainer, colors.postContainer) >= MIN_CONTAINER_DISTANCE,
            )
        }
    }

    private fun assertOpaque(colors: EgoHighlightColors) {
        assertEquals(1f, colors.quoteContainer.alpha, 0f)
        assertEquals(1f, colors.postContainer.alpha, 0f)
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }

    private companion object {
        // The closest prescribed pair is the light palette (~0.042 in sRGB space).
        const val MIN_CONTAINER_DISTANCE = 0.04f
    }
}
