package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * #436 — the per-post « + » multi-quote affordance in [TopicPostCard]'s footer (RF1 quote+/quote-
 * parity). The card already received the SELECTED state (border + pill, #436 lot 1) ; this proves
 * the new ACTION: it shows under the same gate as « Citer », flips its label/contentDescription on
 * membership, and fires the toggle on tap. Pure UI behaviour — the visibility gate itself
 * (`shouldShowQuoteAction`) is unit-tested in [TopicActionGatesTest] ; here we pin the wiring that
 * turns that gate into a tappable control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPostCardMultiQuoteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `unselected post shows the add affordance and a tap fires the toggle`() {
        var toggles = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    highlighted = false,
                    citedCount = 0,
                    onQuote = {},
                    onEdit = null,
                    multiQuoteSelected = false,
                    onToggleMultiQuote = { toggles++ },
                )
            }
        }

        // Visible label = the « add » short form ; TalkBack reads the long menu label.
        composeTestRule.onNodeWithText(ADD_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(ADD_DESC)
            .assertHasClickAction()
            .performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `selected post flips the label and contentDescription to remove`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    highlighted = false,
                    citedCount = 0,
                    onQuote = {},
                    onEdit = null,
                    multiQuoteSelected = true,
                    onToggleMultiQuote = {},
                )
            }
        }

        composeTestRule.onNodeWithText(REMOVE_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(REMOVE_DESC).assertIsDisplayed()
        // The add state must be gone — the control is a single toggle, not two buttons.
        composeTestRule.onNodeWithText(ADD_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a non-quotable post keeps Citer hidden and the add affordance absent`() {
        // onToggleMultiQuote = null mirrors the call-site gate (quoteAction null → no « + »).
        // « Citer » is also gated out (onQuote = null), but the row still renders here via onEdit,
        // so the absence below is a real assertion, not an empty-row artefact.
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    highlighted = false,
                    citedCount = 0,
                    onQuote = null,
                    onEdit = {},
                    multiQuoteSelected = false,
                    onToggleMultiQuote = null,
                )
            }
        }

        composeTestRule.onNodeWithText(ADD_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(ADD_DESC).assertDoesNotExist()
    }

    private fun samplePost(): Post = Post(
        numreponse = 16244,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = 1,
        profileId = null,
    )

    private companion object {
        const val ADD_LABEL = "+ Citer"
        const val REMOVE_LABEL = "✓ Cité"
        const val ADD_DESC = "Ajouter à la citation multiple"
        const val REMOVE_DESC = "Retirer de la citation multiple"
    }
}
