package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #808 — the quote-cards block of the quick-reply sheet is CAPPED so a heavy selection can never
 * crush the text field under the IME. This mounts the exact capped block QuickReplySheet composes
 * (same [QUICK_REPLY_MAX_CARDS_HEIGHT] + internal scroll) with more cards than the budget holds,
 * and pins the two halves of the contract : the block never grows past the cap, and the cards
 * past the fold stay reachable through the block's OWN scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class QuickReplyQuoteCardsCapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `five cards never grow the block past the cap and scroll to the last one`() {
        val quotes = (1..5).map { n ->
            QuotedPostPreview(numreponse = n, author = "author$n", excerpt = "excerpt $n")
        }
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                QuoteCardsColumn(
                    quotes = quotes,
                    enabled = true,
                    callbacks = QuoteCardsCallbacks(
                        onMoveUp = {},
                        onMoveDown = {},
                        onRemove = {},
                    ),
                    modifier = Modifier
                        .heightIn(max = QUICK_REPLY_MAX_CARDS_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .testTag(BLOCK_TAG),
                )
            }
        }

        val block = composeTestRule.onNodeWithTag(BLOCK_TAG)
        val height = block.fetchSemanticsNode().boundsInRoot.height
        val capPx = with(composeTestRule.density) { QUICK_REPLY_MAX_CARDS_HEIGHT.toPx() }
        assertTrue(
            "cards block is ${height}px, cap is ${capPx}px",
            height <= capPx + 1f,
        )

        // The 5th card sits past the fold — the block's own scroll must reach it.
        block.performScrollToNode(hasText("author5", substring = true))
        composeTestRule.onNode(hasText("author5", substring = true)).assertExists()
    }

    private companion object {
        const val BLOCK_TAG = "quick_reply_quote_cards_under_test"
    }
}
