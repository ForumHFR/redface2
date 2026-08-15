package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import java.time.Instant
import org.junit.Assert.assertEquals
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
 * placeholder. #1050 adds the mounted full-width sequence, hot signature presentation and internal
 * LazyListState anchor proofs without weakening those default-path assertions.
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

    @Test
    fun `full width keeps one heading per message and no hairline before the pager`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Pleine largeur",
                    correspondent = "Correspondant",
                    messages = listOf(
                        message(101, "Alpha", "Premier message"),
                        message(102, "Beta", "Deuxieme message"),
                        message(103, "Gamma", "Troisieme message"),
                    ),
                    page = 2,
                    totalPages = 4,
                    canReply = false,
                ),
            ),
            page = 2,
            totalPages = 4,
            fullWidthPosts = true,
        )

        // Three messages produce exactly two message→message boundaries. The third message is
        // followed by the pager island and must not leave a second/dangling boundary before it.
        compose.onAllNodesWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertCountEquals(2)
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun `signature preference renders parsed signature without replacing the message`() {
        val state = mutableStateOf(
            contentState(
                messages = listOf(
                    message(
                        numreponse = 101,
                        author = "Alpha",
                        body = "Corps stable",
                        signature = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(PostInline.Text("Signature MP simulée")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Signature MP simulée").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(showSignatures = true) }

        compose.onNodeWithText("Corps stable").assertIsDisplayed()
        compose.onNodeWithText("Signature MP simulée").assertIsDisplayed()
    }

    @Test
    fun `full width toggle keeps the internal lazy list reading anchor`() {
        val state = mutableStateOf(
            contentState(
                messages = (1..20).map { index ->
                    message(index, "Auteur $index", "Message $index")
                },
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollToIndex) { scroll -> scroll(TARGET_INDEX) }
        compose.waitForIdle()
        val anchor = compose.onNodeWithText(TARGET_AUTHOR)
        anchor.assertIsDisplayed()
        val topBefore = anchor.fetchSemanticsNode().boundsInRoot.top

        compose.runOnIdle { state.value = state.value.copy(fullWidthPosts = true) }
        compose.waitForIdle()

        // At least one flat divider proves the new geometry reached the visible list. If the
        // internal rememberLazyListState had been recreated, TARGET_AUTHOR would no longer be the
        // displayed anchor: the first message would replace it at the top.
        assertTrue(
            compose.onAllNodesWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        anchor.assertIsDisplayed()
        assertEquals(
            "the same keyed message must stay pinned after the render-only preference flip",
            topBefore,
            anchor.fetchSemanticsNode().boundsInRoot.top,
            ANCHOR_TOLERANCE_PX,
        )
    }

    private fun setContent(
        mode: PrivateMessageThreadUiState.Mode,
        page: Int,
        totalPages: Int,
        fullWidthPosts: Boolean = false,
    ) {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = page)
        val state = PrivateMessageThreadUiState(
            request = request,
            mode = mode,
            page = page,
            totalPages = totalPages,
            fullWidthPosts = fullWidthPosts,
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

    private fun contentState(messages: List<Post>): PrivateMessageThreadUiState {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = 1)
        return PrivateMessageThreadUiState(
            request = request,
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet",
                    correspondent = "Correspondant",
                    messages = messages,
                    page = 1,
                    totalPages = 1,
                    canReply = false,
                ),
            ),
            page = 1,
            totalPages = 1,
        )
    }

    private fun message(
        numreponse: Int,
        author: String,
        body: String,
        signature: PostContent? = null,
    ): Post = Post(
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
        signature = signature,
    )

    private companion object {
        const val THREAD_ID = 42
        const val TARGET_INDEX = 10
        const val TARGET_AUTHOR = "Auteur 11"
        const val ANCHOR_TOLERANCE_PX = 0.5f

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
