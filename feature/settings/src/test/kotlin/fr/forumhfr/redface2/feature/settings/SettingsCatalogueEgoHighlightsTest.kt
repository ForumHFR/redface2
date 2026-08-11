package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueEgoHighlightsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the two rows live together in the topic section and are searchable`() {
        val catalogue = buildCatalogue()
        val topicIds = catalogue.first { it.id == "topic" }.items.map { it.searchable.id }

        assertEquals(
            listOf("ego_quote", "ego_post"),
            topicIds.filter { it == "ego_quote" || it == "ego_post" },
        )
        assertEquals(
            listOf("ego_quote"),
            filterSettingsSections(catalogue.map { it.toSearchable() }, "EgoQuote")
                .flatMap { it.items }
                .map { it.id },
        )
        assertEquals(
            listOf("ego_post"),
            filterSettingsSections(catalogue.map { it.toSearchable() }, "EgoPost")
                .flatMap { it.items }
                .map { it.id },
        )
    }

    @Test
    fun `tapping EgoQuote dispatches its independent intent`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(id = "ego_quote", state = SettingsState(), onIntent = received::add)

        composeTestRule.onNode(isToggleable()).assertIsEnabled().performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.EgoQuoteChanged(false)), received)
    }

    @Test
    fun `tapping EgoPost dispatches its independent intent`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(id = "ego_post", state = SettingsState(), onIntent = received::add)

        composeTestRule.onNode(isToggleable()).assertIsEnabled().performClick()

        assertEquals(listOf<SettingsIntent>(SettingsIntent.EgoPostChanged(false)), received)
    }

    @Test
    fun `EgoQuote is disabled only during its own write`() {
        mountRow(
            id = "ego_quote",
            state = SettingsState(isUpdatingEgoQuote = true, isUpdatingEgoPost = false),
        )

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun `EgoPost is disabled only during its own write`() {
        mountRow(
            id = "ego_post",
            state = SettingsState(isUpdatingEgoQuote = false, isUpdatingEgoPost = true),
        )

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    private fun buildCatalogue(state: SettingsState = SettingsState()): List<SettingsCatalogueSection> {
        lateinit var sections: List<SettingsCatalogueSection>
        composeTestRule.setContent {
            sections = testCatalogue(state = state, onIntent = {})
        }
        composeTestRule.waitForIdle()
        return sections
    }

    private fun mountRow(
        id: String,
        state: SettingsState,
        onIntent: (SettingsIntent) -> Unit = {},
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(state = state, onIntent = onIntent)
                    .first { it.id == "topic" }
                    .items
                    .first { it.searchable.id == id }
                    .render()
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
}
