package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * #823 — a LONG press on the footer « Citer » opens the full-screen editor directly, a one-shot
 * override of the #806 writing-surface preset. The preset bypass itself is wired in `TopicContent`
 * (never consults `writingSurfaceFor`) ; here we pin the card-level gesture split : « Citer »
 * moved from a real M3 TextButton to the hand-rolled combinedClickable pattern (a real Button's
 * inner clickable swallows stacked gesture modifiers — the same trap as the FABs, cf.
 * [MultiQuoteFabClearTest]), so a SHORT tap must fire `onQuote` alone and a LONG press
 * `onQuoteLongPress` alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPostCardQuoteLongPressTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a short tap quotes through the preset routing and does not force the editor`() {
        var quotes = 0
        var fullEditor = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    citedCount = 0,
                    onQuote = { quotes++ },
                    onQuoteLongPress = { fullEditor++ },
                    onEdit = null,
                )
            }
        }

        composeTestRule.onNodeWithText(QUOTE_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, quotes)
        assertEquals(0, fullEditor)
    }

    @Test
    fun `a long press forces the full-screen editor and does not fire the tap`() {
        var quotes = 0
        var fullEditor = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    citedCount = 0,
                    onQuote = { quotes++ },
                    onQuoteLongPress = { fullEditor++ },
                    onEdit = null,
                )
            }
        }

        composeTestRule.onNodeWithText(QUOTE_LABEL)
            .performTouchInput { longClick() }

        assertEquals(1, fullEditor)
        assertEquals(0, quotes)
    }

    @Test
    fun `without a long-press callback the button keeps a working plain tap`() {
        // onQuoteLongPress defaults to null (previews/tests) — the fallback is a plain clickable,
        // so « Citer » must stay a working tap target with no long-press semantics advertised.
        var quotes = 0
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(),
                    citedCount = 0,
                    onQuote = { quotes++ },
                    onEdit = null,
                )
            }
        }

        composeTestRule.onNodeWithText(QUOTE_LABEL)
            .assertHasClickAction()
            .performClick()

        assertEquals(1, quotes)
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
        const val QUOTE_LABEL = "Citer"
    }
}
