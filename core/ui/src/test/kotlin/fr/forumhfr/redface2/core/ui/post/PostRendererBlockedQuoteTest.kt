package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #785 — the blacklist applies INSIDE quotes: a citation whose author is black-listed renders as
 * the `BlockedQuoteBlock` placeholder (body hidden, « Afficher »/« Masquer » reveal), while every
 * surface that does not provide [LocalBlockedQuoteAuthors] (editor preview, MP threads, signatures)
 * keeps rendering quotes untouched through the empty default. The pure canonical-match decision
 * (`isBlockedQuoteAuthor`) is pinned separately in [PostRendererQuoteDepthTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererBlockedQuoteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val quotedBody = "propos du posteur masqué"

    private fun quoteFrom(author: String, body: String = quotedBody): PostBlock.Quote = PostBlock.Quote(
        author = author,
        numreponse = 12345,
        page = 3,
        content = PostContent(
            blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(body)))),
        ),
    )

    private fun setPost(blocked: Set<String>?, vararg blocks: PostBlock) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    val content = PostContent(blocks = blocks.toList())
                    if (blocked != null) {
                        CompositionLocalProvider(LocalBlockedQuoteAuthors provides blocked) {
                            PostRenderer(content = content)
                        }
                    } else {
                        // No provider — exercises the empty default every non-topic surface gets.
                        PostRenderer(content = content)
                    }
                }
            }
        }
    }

    @Test
    fun `a blocked author's quote is masked by default and reveals on tap`() {
        setPost(setOf("alice"), quoteFrom("Alice"))

        // Masked: placeholder visible (with the pseudo, HiddenPostCard parity), body absent.
        composeTestRule.onNodeWithText("Citation de Alice masquée").assertExists()
        composeTestRule.onNodeWithText(quotedBody).assertDoesNotExist()

        // Reveal: tap the frame (via its « Afficher » affordance) → header + body render.
        composeTestRule.onNodeWithText("Afficher").performClick()
        composeTestRule.onNodeWithText(quotedBody).assertExists()
        composeTestRule.onNodeWithText("Citation de Alice").assertExists()

        // Re-mask: the toggle is symmetric (« Masquer »), like the other quote folds.
        composeTestRule.onNodeWithText("Masquer").performClick()
        composeTestRule.onNodeWithText(quotedBody).assertDoesNotExist()
    }

    @Test
    fun `a non-blocked author's quote renders untouched next to a blocked one`() {
        val visibleBody = "propos parfaitement visibles"
        setPost(
            setOf("alice"),
            quoteFrom("Alice"),
            quoteFrom("Bob", body = visibleBody),
        )

        composeTestRule.onNodeWithText("Citation de Alice masquée").assertExists()
        composeTestRule.onNodeWithText(visibleBody).assertExists()
        composeTestRule.onNodeWithText("Citation de Bob").assertExists()
    }

    @Test
    fun `a blocked citation nested inside another quote is masked too`() {
        val outer = PostBlock.Quote(
            author = "Bob",
            numreponse = 22222,
            page = 1,
            content = PostContent(blocks = listOf(quoteFrom("Alice"))),
        )
        setPost(setOf("alice"), outer)

        // The outer (non-blocked) quote renders; the nested blocked one collapses to the placeholder.
        composeTestRule.onNodeWithText("Citation de Bob").assertExists()
        composeTestRule.onNodeWithText("Citation de Alice masquée").assertExists()
        composeTestRule.onNodeWithText(quotedBody).assertDoesNotExist()
    }

    @Test
    fun `without a provider the default empty set leaves quotes alone`() {
        // Editor preview / MP threads / signatures never provide the local: nothing may mask there.
        setPost(blocked = null, quoteFrom("Alice"))

        composeTestRule.onNodeWithText(quotedBody).assertExists()
        composeTestRule.onNodeWithText("Citation de Alice masquée").assertDoesNotExist()
    }
}
