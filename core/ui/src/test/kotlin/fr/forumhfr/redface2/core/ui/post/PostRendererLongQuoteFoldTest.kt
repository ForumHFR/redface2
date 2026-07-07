package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #784 — a folded long quote (#332) shows a bounded PREVIEW of its body instead of a bare header
 * line, and the gesture contract is desambiguated: tapping the body/preview UNFOLDS, tapping the
 * « Citation de X » header still JUMPS to the cited post (#699) without toggling the fold. The pure
 * sizing/clip decisions are pinned separately in [PostRendererQuoteDepthTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererLongQuoteFoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Well over LONG_QUOTE_CHAR_THRESHOLD (280) so the quote folds by default.
    private val wallOfText = "Mur de texte volontairement long pour déclencher le repli. ".repeat(12)

    private fun longQuote(): PostBlock.Quote = PostBlock.Quote(
        author = "Lt Ripley",
        numreponse = 74749781,
        page = 8270,
        content = PostContent(
            blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(wallOfText)))),
        ),
    )

    private fun setPost(onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PostRenderer(
                        content = PostContent(blocks = listOf(longQuote())),
                        onGoToCitedPost = onGoToCitedPost,
                    )
                }
            }
        }
    }

    @Test
    fun `a long quote folds to a preview - body visible, Déplier offered`() {
        setPost()

        // #784 vs #332: the folded state now RENDERS the beginning of the body (bounded preview)
        // instead of hiding it entirely.
        composeTestRule.onNodeWithText("Mur de texte", substring = true).assertExists()
        composeTestRule.onNodeWithText("Déplier").assertExists()
        composeTestRule.onNodeWithText("Replier").assertDoesNotExist()
    }

    @Test
    fun `tapping the preview body expands, tapping Replier collapses back`() {
        setPost()

        // Tap INSIDE the visible part of the preview body (top-left corner of the text node: the
        // node's own bounds may extend past the clip, but its top is always visible). Plain text
        // carries no click handler of its own, so the tap reaches the frame's fold toggle.
        composeTestRule.onNodeWithText("Mur de texte", substring = true)
            .performTouchInput { click(Offset(10f, 10f)) }
        composeTestRule.onNodeWithText("Replier").assertExists()
        composeTestRule.onNodeWithText("Déplier").assertDoesNotExist()

        composeTestRule.onNodeWithText("Replier").performClick()
        composeTestRule.onNodeWithText("Déplier").assertExists()
        composeTestRule.onNodeWithText("Replier").assertDoesNotExist()
    }

    @Test
    fun `tapping the header jumps to the cited post without toggling the fold`() {
        var jumpedTo: Pair<Int, Int>? = null
        setPost(onGoToCitedPost = { page, numreponse -> jumpedTo = page to numreponse })

        composeTestRule.onNodeWithText("Citation de Lt Ripley").performClick()

        // #699 contract untouched: the header's clickable consumed the tap for the jump…
        assertEquals(8270 to 74749781, jumpedTo)
        // …and the frame's fold toggle never fired (still folded, still offering « Déplier »).
        composeTestRule.onNodeWithText("Déplier").assertExists()
        composeTestRule.onNodeWithText("Replier").assertDoesNotExist()
    }
}
