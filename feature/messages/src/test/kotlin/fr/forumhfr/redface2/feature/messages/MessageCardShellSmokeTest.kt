package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
 * topic-owned affordance the MP never opts into).
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
                MessageCard(
                    message = Post(
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
                    ),
                )
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
}
