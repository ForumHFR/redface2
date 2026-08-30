package fr.forumhfr.redface2.feature.settings

import androidx.compose.ui.test.assertIsEnabled
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

/** #1170 — catalogue, French copy and independence contract of the unanswered-polls toggle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCatalogueUnansweredPollsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the French row directly follows the global poll expansion setting`() {
        val catalogue = buildCatalogue()
        val topic = catalogue.first { it.id == "topic" }
        val ids = topic.items.map { it.searchable.id }
        val row = topic.items.first { it.searchable.id == "topic_unanswered_polls_expanded" }

        assertEquals(
            ids.indexOf("topic_polls_expanded") + 1,
            ids.indexOf("topic_unanswered_polls_expanded"),
        )
        assertEquals("Déplier les sondages non répondus", row.searchable.title)
        assertEquals(
            listOf("topic_unanswered_polls_expanded"),
            filterSettingsSections(catalogue.map { it.toSearchable() }, "non répondus")
                .flatMap { it.items }
                .map { it.id },
        )
    }

    @Test
    fun `the switch stays enabled while the independent global setting is active`() {
        mountRow(
            state = SettingsState(
                topicPollsExpanded = true,
                isUpdatingTopicPollsExpanded = true,
            ),
        )

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun `tapping the switch dispatches TopicUnansweredPollsExpandedChanged`() {
        val received = mutableListOf<SettingsIntent>()
        mountRow(state = SettingsState(), onIntent = received::add)

        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(
            listOf<SettingsIntent>(SettingsIntent.TopicUnansweredPollsExpandedChanged(true)),
            received,
        )
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
        state: SettingsState,
        onIntent: (SettingsIntent) -> Unit = {},
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                testCatalogue(state = state, onIntent = onIntent)
                    .first { it.id == "topic" }
                    .items
                    .first { it.searchable.id == "topic_unanswered_polls_expanded" }
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
