package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #973 (§8 [AMENDEMENT-v1.5-2]) — the display-profile plumbing to the renderer subtree, on the
 * #332 foldLongQuotes model: `RedfaceTheme` provides [LocalMediaDisplayProfile] from
 * [ReadingDisplaySettings.mediaDisplayProfile], and every default is M (×1,5 — the XaTriX choice),
 * so previews / hosts that provide nothing render like the shipped default. The scope is the whole
 * themed subtree: the 4 hosts of the shared renderer (Topic, MP, editor preview, signature) sit
 * under the single app-root `RedfaceTheme`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaDisplayProfileThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the local defaults to M for unthemed hosts`() {
        var seen: MediaDisplayProfile? = null
        composeTestRule.setContent {
            seen = LocalMediaDisplayProfile.current
        }
        assertEquals(MediaDisplayProfile.M, seen)
    }

    @Test
    fun `the reading settings default to M`() {
        assertEquals(MediaDisplayProfile.M, ReadingDisplaySettings().mediaDisplayProfile)
    }

    @Test
    fun `RedfaceTheme provides the profile from the reading settings`() {
        var seen: MediaDisplayProfile? = null
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                amoledTheme = false,
                dynamicColor = false,
                reading = ReadingDisplaySettings(mediaDisplayProfile = MediaDisplayProfile.L),
            ) {
                seen = LocalMediaDisplayProfile.current
            }
        }
        assertEquals(MediaDisplayProfile.L, seen)
    }
}
