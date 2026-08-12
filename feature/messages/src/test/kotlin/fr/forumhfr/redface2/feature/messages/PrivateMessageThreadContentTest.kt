package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1041 — characterization of the complete, state-hoisted private-thread surface before the shared
 * reading-card refactor. These assertions pin the CURRENT MP composition: subject/correspondent
 * chrome, one ordered card per message, a trailing pager on multi-page threads, and the anonymous
 * placeholder. They deliberately do not ask the MP host for topic-only affordances.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class PrivateMessageThreadContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `content renders the header ordered message list and trailing pager`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet caracterise",
                    correspondent = "Correspondant",
                    messages = listOf(
                        message(101, "Alpha", "Premier message"),
                        message(102, "Beta", "Deuxieme message"),
                        message(103, "Gamma", "Troisieme message"),
                    ),
                    page = 2,
                    totalPages = 4,
                    canReply = true,
                ),
            ),
            page = 2,
            totalPages = 4,
        )

        compose.onNodeWithText("Sujet caracterise").assertIsDisplayed()
        compose.onNodeWithText("Correspondant").assertIsDisplayed()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(3)
        val authorTops = listOf("Alpha", "Beta", "Gamma").map { author ->
            compose.onNode(heading.and(hasText(author)), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        }
        assertTrue(
            "messages must stay oldest-first in the LazyColumn",
            authorTops.zipWithNext().all { (a, b) -> a < b },
        )

        listOf("Premier message", "Deuxieme message", "Troisieme message").forEach { body ->
            compose.onNodeWithText(body).assertIsDisplayed()
        }

        compose.onNodeWithText("Page 2 / 4").assertIsDisplayed()
        compose.onNodeWithText("Précédent").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Suivant").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `requires login keeps private content out of the composition`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.RequiresLogin,
            page = 1,
            totalPages = 1,
        )

        compose.onNodeWithText("Conversation privée").assertIsDisplayed()
        compose.onNodeWithText("Connexion requise").assertIsDisplayed()
        compose.onNodeWithText("Connectez-vous depuis le menu compte pour lire vos messages privés.")
            .assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Page 1 / 1").assertDoesNotExist()
        compose.onNodeWithText("Répondre").assertDoesNotExist()
    }

    private fun setContent(
        mode: PrivateMessageThreadUiState.Mode,
        page: Int,
        totalPages: Int,
    ) {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = page)
        val state = PrivateMessageThreadUiState(
            request = request,
            mode = mode,
            page = page,
            totalPages = totalPages,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }
    }

    private fun message(numreponse: Int, author: String, body: String): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.EPOCH.plusSeconds(numreponse.toLong()),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(body))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private companion object {
        const val THREAD_ID = 42

        val NO_OP_CALLBACKS = PrivateMessageThreadCallbacks(
            onBack = {},
            onReply = {},
            onRetry = {},
            onRefresh = {},
            onSelectPage = {},
            onOpenRoster = {},
            onDismissRoster = {},
            onRetryRoster = {},
            onManageRecipients = {},
        )
    }
}
