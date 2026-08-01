package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #884 — catalogue contract of the « Posts en pleine largeur » toggle: the row lives in the
 * « Sujet et lecture » section right after the fold-long-quotes toggle, the settings SEARCH finds
 * it through the real (resolved-strings) index, tapping the switch dispatches
 * [SettingsIntent.FullWidthPostsChanged], and the in-flight write gate disables the switch.
 * Robolectric hosts `createComposeRule()` on the JVM (same harness as the :feature:topic and
 * :core:ui Compose tests) because [buildSettingsCatalogue] resolves `stringResource`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueFullWidthPostsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Builds the REAL catalogue (resolved strings) without rendering any row. */
    private fun buildCatalogue(state: SettingsState = SettingsState()): List<SettingsCatalogueSection> {
        lateinit var sections: List<SettingsCatalogueSection>
        composeTestRule.setContent {
            sections = testCatalogue(state = state, onIntent = {})
        }
        composeTestRule.waitForIdle()
        return sections
    }

    /** Mounts ONLY the full-width-posts row of the real catalogue, wired to [onIntent]. */
    private fun mountRow(state: SettingsState, onIntent: (SettingsIntent) -> Unit = {}) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val row = testCatalogue(state = state, onIntent = onIntent)
                    .first { it.id == "topic" }
                    .items
                    .first { it.searchable.id == "full_width_posts" }
                row.render()
            }
        }
        composeTestRule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun testCatalogue(
        state: SettingsState,
        onIntent: (SettingsIntent) -> Unit,
    ): List<SettingsCatalogueSection> = buildSettingsCatalogue(
        state = state,
        onIntent = onIntent,
        startScreenState = StartScreenSettingsState(),
        onStartScreenIntent = {},
        onOpenProxy = {},
        onOpenMaintenance = {},
        onOpenDisplay = {},
        onOpenImages = {},
        onOpenAccountAbout = {},
        onOpenBlacklist = {},
    )

    @Test
    fun `the row sits in the topic section right after fold_long_quotes`() {
        val topic = buildCatalogue().first { it.id == "topic" }
        val ids = topic.items.map { it.searchable.id }

        assertTrue("full_width_posts must be a row of the topic section", "full_width_posts" in ids)
        assertEquals(
            "full_width_posts must directly follow fold_long_quotes",
            ids.indexOf("fold_long_quotes") + 1,
            ids.indexOf("full_width_posts"),
        )
    }

    @Test
    fun `the settings search finds the row through the real index`() {
        val searchable = buildCatalogue().map { it.toSearchable() }

        val filtered = filterSettingsSections(searchable, "pleine largeur")

        assertEquals(
            listOf("full_width_posts"),
            filtered.flatMap { section -> section.items }.map { it.id },
        )
    }

    @Test
    fun `tapping the switch dispatches FullWidthPostsChanged with the flipped value`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(state = SettingsState(), onIntent = received::add)

        composeTestRule.onNode(isToggleable()).assertIsEnabled().performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.FullWidthPostsChanged(true)), received)
    }

    @Test
    fun `the switch is disabled while the write is in flight`() {
        mountRow(state = SettingsState(isUpdatingFullWidthPosts = true))

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }
}
