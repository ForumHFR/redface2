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
import androidx.compose.ui.test.performClick
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
 * LazyListState anchor proofs without weakening those default-path assertions. #509/#1050 adds the
 * blacklist placeholder, page-local reveal and blocked-quote provider contracts.
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
    fun `message long click opens only the decided MP menu and keeps one message heading`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Menu MP",
                    correspondent = "Correspondant",
                    messages = listOf(message(101, "Alice", "Texte à copier")),
                    page = 1,
                    totalPages = 1,
                    canReply = false,
                ),
            ),
            page = 1,
            totalPages = 1,
        )

        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnLongClick)

        compose.onNodeWithText("Copier le texte").assertIsDisplayed()
        compose.onNodeWithText("Masquer cet utilisateur").assertIsDisplayed()
        compose.onNodeWithText("Copier le lien de ce post").assertDoesNotExist()
        compose.onNodeWithText("Ouvrir dans le navigateur").assertDoesNotExist()
        compose.onNodeWithText("Citer").assertDoesNotExist()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `visible message with server ref exposes the footer quote action`() {
        var quotedMessage: Post? = null
        val state = contentState(
            messages = listOf(message(101, "Alice", "Message citable").copy(quoteRef = 4)),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(onQuote = { quotedMessage = it }),
                )
            }
        }

        compose.onNodeWithText("Citer").assertIsDisplayed().performClick()

        assertEquals(101, quotedMessage?.numreponse)
        assertEquals(4, quotedMessage?.quoteRef)
    }

    @Test
    fun `missing ref and hidden message expose no quote action`() {
        val state = contentState(
            messages = listOf(
                message(101, "Alice", "Visible sans rang"),
                message(102, "Bob", "Masqué avec rang").copy(quoteRef = 2),
            ),
            hiddenNumreponses = setOf(102),
            blockedQuoteAuthors = setOf("bob"),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(onQuote = { _ -> }),
                )
            }
        }

        compose.onNodeWithText("Visible sans rang").assertIsDisplayed()
        compose.onNodeWithText("Post de Bob masqué").assertIsDisplayed()
        compose.onNodeWithText("Citer").assertDoesNotExist()
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
    fun `blacklist keeps one placeholder per message in one-to-one and DT threads`() {
        val messages = listOf(
            message(101, "Alice", "Secret Alice"),
            message(102, "Bob", "Message visible"),
        )
        val state = mutableStateOf(
            contentState(
                messages = messages,
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                isMultiRecipient = false,
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    // Deliberately false: masking must not depend on this ephemeral inbox hint.
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret Alice").assertDoesNotExist()
        compose.onNodeWithText("Message visible").assertIsDisplayed()
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(2)

        compose.runOnIdle {
            state.value = contentState(
                messages = messages,
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                isMultiRecipient = true,
            )
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret Alice").assertDoesNotExist()
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `revealing a hidden message lasts only until another page lands`() {
        val state = mutableStateOf(
            contentState(
                messages = listOf(message(101, "Alice", "Secret page 1")),
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                page = 1,
                totalPages = 2,
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

        compose.onNodeWithText("Secret page 1").assertDoesNotExist()
        compose.onNodeWithText("Afficher").performClick()
        compose.onNodeWithText("Secret page 1").assertIsDisplayed()

        compose.runOnIdle {
            state.value = contentState(
                messages = listOf(message(201, "Alice", "Secret page 2")),
                hiddenNumreponses = setOf(201),
                blockedQuoteAuthors = setOf("alice"),
                page = 2,
                totalPages = 2,
            )
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret page 2").assertDoesNotExist()
    }

    @Test
    fun `blocked quote authors are masked inside a visible MP message`() {
        val quotedBody = "Fuite par citation"
        val quotingMessage = message(102, "Bob", "Introduction").copy(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(inlines = listOf(PostInline.Text("Introduction"))),
                    PostBlock.Quote(
                        author = "Alice",
                        numreponse = 101,
                        page = 1,
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(inlines = listOf(PostInline.Text(quotedBody))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val state = contentState(
            messages = listOf(quotingMessage),
            hiddenNumreponses = emptySet(),
            blockedQuoteAuthors = setOf("alice"),
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

        compose.onNodeWithText("Introduction").assertIsDisplayed()
        compose.onNodeWithText("Citation de Alice masquée").assertIsDisplayed()
        compose.onNodeWithText(quotedBody).assertDoesNotExist()
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

    @Suppress("LongParameterList") // Test state factory: every argument controls one independent contract.
    private fun contentState(
        messages: List<Post>,
        hiddenNumreponses: Set<Int> = emptySet(),
        blockedQuoteAuthors: Set<String> = emptySet(),
        page: Int = 1,
        totalPages: Int = 1,
        isMultiRecipient: Boolean = false,
        canReply: Boolean = false,
    ): PrivateMessageThreadUiState {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = page)
        return PrivateMessageThreadUiState(
            request = request,
            mode = PrivateMessageThreadUiState.Mode.Content(
                thread = PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet",
                    correspondent = "Correspondant",
                    messages = messages,
                    page = page,
                    totalPages = totalPages,
                    canReply = canReply,
                    isMultiRecipient = isMultiRecipient,
                ),
                hiddenNumreponses = hiddenNumreponses,
                blockedQuoteAuthors = blockedQuoteAuthors,
            ),
            page = page,
            totalPages = totalPages,
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
