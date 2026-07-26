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
 * #884 — control smoke for [MessageCard] now that the shared
 * [fr.forumhfr.redface2.core.ui.post.PostCardShell] has a `flat` mode: the MP card passes nothing
 * new, so its default card rendering must stay exactly what it was — identity header (author +
 * date) and body slots present, and NO flat closing hairline (the divider is a `flat`-only,
 * topic-owned affordance the MP never opts into). Also guards the MP side of the
 * `PostIdentityHeader` heading contract (review Sol r4): the fallback pseudo is the message's
 * exactly-one TalkBack heading — the topic-side twin is `TopicPostCardFullWidthTest`.
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
    fun `MessageCard exposes exactly one heading - the author pseudo (fallback variant)`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(message = sampleMessage())
            }
        }

        // #884 a11y (review Sol r4) — the MP card uses PostIdentityHeader's FALLBACK pseudo (no
        // slot), whose Text carries heading() itself: TalkBack heading navigation jumps message to
        // message. The heading rides on the pseudo text node…
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
