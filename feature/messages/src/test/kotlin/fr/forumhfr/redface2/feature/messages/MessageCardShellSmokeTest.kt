package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.CREATOR_PSEUDO_TEXT_TAG
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import java.time.Instant
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * caller-supplied creator slot. The topic-side twin is `TopicPostCardFullWidthTest`.
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
    fun `creator pseudo uses the gold-sheen leaf and stays the card's single heading`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage())
            }
        }

        composeTestRule.onNodeWithTag(CREATOR_PSEUDO_TEXT_TAG).assertIsDisplayed()
        assertPseudoBrush("XaTriX", expectedGold = true)
        assertSingleHeadingOnPseudo("XaTriX")
    }

    @Test
    fun `plain pseudo skips the gold-sheen leaf and stays the card's single heading`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage(author = "Lt Ripley"))
            }
        }

        composeTestRule.onNodeWithTag(CREATOR_PSEUDO_TEXT_TAG).assertDoesNotExist()
        assertPseudoBrush("Lt Ripley", expectedGold = false)
        assertSingleHeadingOnPseudo("Lt Ripley")
    }

    @Test
    fun `edited and cited metadata render in the two card slots when present`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(
                    message = sampleMessage().copy(
                        editedAt = Instant.parse("2025-01-02T03:04:05Z"),
                        citedCount = 2,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("· édité").assertIsDisplayed()
        composeTestRule.onNodeWithText("cité 2 fois").assertIsDisplayed()
    }

    @Test
    fun `missing edited and cited metadata emits neither card slot`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage())
            }
        }

        composeTestRule.onNodeWithText("· édité").assertDoesNotExist()
        composeTestRule.onNodeWithText("cité", substring = true).assertDoesNotExist()
    }

    private fun assertSingleHeadingOnPseudo(author: String) {
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        // #884 a11y — both the supplied creator slot and the fallback put heading() on the real
        // pseudo text node…
        composeTestRule
            .onNode(heading.and(hasText(author)), useUnmergedTree = true)
            .assertExists()
        // …and it is the ONLY heading of the card — the shared header adds no wrapper heading.
        composeTestRule
            .onAllNodes(heading, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun assertPseudoBrush(author: String, expectedGold: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        val pseudo = composeTestRule.onNodeWithText(author, useUnmergedTree = true)
        val readLayout = requireNotNull(
            pseudo.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue("the pseudo Text layout must be readable", readLayout(layouts))
        val brush = layouts.single().layoutInput.style.brush
        if (expectedGold) {
            assertNotNull("creator pseudo must carry the gold-sheen brush", brush)
        } else {
            assertNull("plain pseudo must keep the neutral text style", brush)
        }
    }

    private fun sampleMessage(author: String = "XaTriX"): Post = Post(
        numreponse = 1,
        author = author,
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
