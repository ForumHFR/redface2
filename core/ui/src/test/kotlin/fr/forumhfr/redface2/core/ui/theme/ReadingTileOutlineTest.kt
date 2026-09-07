package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTileOutlineTest {

    @Test
    fun `white light surface uses a one dp outline in the supplied theme colour`() {
        val color = RedfaceLightColorScheme.outlineVariant

        assertEquals(
            BorderStroke(1.dp, color),
            tileOutlineFor(LightSurfaceTone.WHITE, darkTheme = false, outlineColor = color),
        )
    }

    @Test
    fun `other light surface tones have no automatic outline`() {
        LightSurfaceTone.entries.filterNot { it == LightSurfaceTone.WHITE }.forEach { tone ->
            assertNull(
                tone.name,
                tileOutlineFor(tone, darkTheme = false, RedfaceLightColorScheme.outlineVariant),
            )
        }
    }

    @Test
    fun `dark theme has no automatic outline regardless of stored light tone`() {
        LightSurfaceTone.entries.forEach { tone ->
            assertNull(
                tone.name,
                tileOutlineFor(tone, darkTheme = true, RedfaceDarkColorScheme.outlineVariant),
            )
        }
    }

    @Test
    fun `outline follows the resolved palette instead of a fixed colour`() {
        val color = RedfaceRedLightColorScheme.outlineVariant

        assertEquals(
            BorderStroke(1.dp, color),
            tileOutlineFor(LightSurfaceTone.WHITE, darkTheme = false, outlineColor = color),
        )
    }
}
