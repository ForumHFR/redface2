package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #772 — the top-bar title is a tap toggle : collapsed (1 line, ellipsised) ↔ expanded (2 lines).
 * The toggle is transient local state inside [TopicTopBar] ; these tests pin the a11y contract —
 * the title is clickable and announces its state (« Titre réduit » / « Titre développé ») — which
 * is also the only observable surface of the maxLines flip (line counts are not semantics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class TopicTopBarTitleExpandTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the title starts collapsed and a tap expands it`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicTopBar(
                    state = sampleState(),
                    barTitle = LONG_TITLE,
                    barPageIndicator = "page 3 / 10",
                    backLabel = "Retour",
                    scrollBehavior = null,
                    onBack = {},
                    onIntent = {},
                )
            }
        }

        composeTestRule.onNode(withStateDescription(COLLAPSED_STATE)).assertHasClickAction()
        composeTestRule.onNodeWithText(LONG_TITLE).performClick()
        composeTestRule.onNode(withStateDescription(EXPANDED_STATE)).assertHasClickAction()
    }

    @Test
    fun `a second tap folds the title back`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicTopBar(
                    state = sampleState(),
                    barTitle = LONG_TITLE,
                    barPageIndicator = "page 3 / 10",
                    backLabel = "Retour",
                    scrollBehavior = null,
                    onBack = {},
                    onIntent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(LONG_TITLE).performClick()
        composeTestRule.onNode(withStateDescription(EXPANDED_STATE)).assertHasClickAction()
        composeTestRule.onNodeWithText(LONG_TITLE).performClick()
        composeTestRule.onNode(withStateDescription(COLLAPSED_STATE)).assertHasClickAction()
    }

    @Test
    fun `a long press emits the flag-removal intent while a short tap still toggles (#809)`() {
        val intents = mutableListOf<TopicIntent>()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicTopBar(
                    state = sampleState(),
                    barTitle = LONG_TITLE,
                    barPageIndicator = "page 3 / 10",
                    backLabel = "Retour",
                    scrollBehavior = null,
                    onBack = {},
                    onIntent = { intents += it },
                )
            }
        }

        // A long press fires the drapeau-removal intent and does NOT toggle the title (combinedClickable
        // routes a long press to onLongClick only): the title stays collapsed and clickable.
        composeTestRule.onNodeWithText(LONG_TITLE).performTouchInput { longClick() }
        assertEquals(listOf<TopicIntent>(TopicIntent.RequestRemoveTopicFlag), intents)
        composeTestRule.onNode(withStateDescription(COLLAPSED_STATE)).assertHasClickAction()

        // A short tap still toggles to expanded — the #772 tap contract survives the #809 long-press wiring.
        composeTestRule.onNodeWithText(LONG_TITLE).performClick()
        composeTestRule.onNode(withStateDescription(EXPANDED_STATE)).assertHasClickAction()
    }

    private fun withStateDescription(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun sampleState(): TopicUiState = TopicUiState(
        request = TopicRequest(cat = 23, post = 35421, page = 3, scrollTo = null),
        mode = TopicUiState.Mode.Loading,
        availablePages = emptyList(),
    )

    private companion object {
        const val LONG_TITLE =
            "Redface 2 — DEV (canal de développement) : un titre volontairement interminable " +
                "qui ne tient jamais sur une seule ligne de top bar"
        const val COLLAPSED_STATE = "Titre réduit"
        const val EXPANDED_STATE = "Titre développé"
    }
}
