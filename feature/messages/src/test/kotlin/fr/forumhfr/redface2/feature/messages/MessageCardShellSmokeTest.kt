package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #884/#1040 — control smoke for [MessageCard] on the default inset path of the shared
 * [fr.forumhfr.redface2.core.ui.post.PostCardShell]: the identity header (author + date) and body
 * slots render, and no flat closing hairline is emitted. MP full-width parity is a separate opt-in
 * path owned by #1040 Lot 2; that path must keep the card subtree stable and receive its own
 * runtime-toggle coverage when wired.
 *
 * Also guards the MP side of the `PostIdentityHeader` heading contract: the real author pseudo is
 * the card's exactly-one TalkBack heading, whether [MessageCard] uses the fallback text or a
 * feature-owned pseudo slot. The topic-side twin is `TopicPostCardFullWidthTest`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageCardShellSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `default MessageCard keeps its card rendering with slots and no flat divider`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage())
            }
        }

        // Header slot: author pseudo + formatted date (Instant.EPOCH in Europe/Paris).
        composeTestRule.onNodeWithText("XaTriX").assertIsDisplayed()
        composeTestRule.onNodeWithText("01/01/1970 01:00:00").assertIsDisplayed()
        // Body slot: the rendered paragraph.
        composeTestRule.onNodeWithText("bonjour").assertIsDisplayed()
        // No flat hairline on the default (card) shell.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `MessageCard exposes exactly one heading on the author pseudo`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage())
            }
        }

        // #884 a11y — the real author pseudo is the card's single heading. PostIdentityHeader marks
        // its fallback text; if MessageCard supplies a pseudo slot, that slot must mark its own text.
        // The heading rides on the pseudo text node…
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        composeTestRule
            .onNode(heading.and(hasText("XaTriX")), useUnmergedTree = true)
            .assertExists()
        // …and it is the ONLY heading of the card — the shared header adds no wrapper heading.
        composeTestRule
            .onAllNodes(heading, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun sampleMessage(): Post = Post(
        numreponse = 1,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = listOf(PostInline.Text("bonjour")),
                ),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )
}
