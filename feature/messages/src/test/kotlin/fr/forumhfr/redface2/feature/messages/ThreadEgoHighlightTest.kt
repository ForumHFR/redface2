package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1050 — the Ego markers on the MP thread surface, derived by the list from the session pseudo
 * exposed in [PrivateMessageThreadUiState] (lot 2, PR 2). The raison d'être of this PR is pinned
 * first: EgoPost follows the session-bound author comparison of `core.domain.ego.isEgoPost`, NEVER
 * the cached `Post.isOwnPost` bit (stale across an A → B account switch, and absent for
 * `affichoutils=0` profiles — #545). The marker semantics (« Votre message » StateDescription,
 * `LocalEgoQuotePseudo` quote container) are the same as the topic's; their per-card rendering is
 * pinned by `TopicEgoHighlightRenderTest`, so this suite asserts the MP DERIVATION: which cards of
 * a mounted conversation are marked, under which state.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ThreadEgoHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `EgoPost derives from the session pseudo and ignores the cached isOwnPost bit`() {
        // One axis, both failure modes of trusting Post.isOwnPost (#545 / account switch):
        //  - the OTHER author carries a stale `isOwnPost = true` (cached under a previous account,
        //    or simply wrong) → must NOT be marked;
        //  - the session author carries `isOwnPost = false` (HFR toolbar disabled, no edit link
        //    for the parser) → MUST be marked anyway.
        setThreadContent(
            state = contentState(
                messages = listOf(
                    message(101, author = OTHER_AUTHOR, body = "Message de l'autre", isOwnPost = true),
                    message(102, author = SESSION_PSEUDO, body = "Mon message", isOwnPost = false),
                ),
                connectedPseudo = SESSION_PSEUDO,
            ),
        )

        assertStateCount(OWN_POST_STATE, 1)
        // …and the one marked identity node is the SESSION author's, not the stale-bit carrier's.
        compose.onNode(
            stateMatcher(OWN_POST_STATE).and(hasAnyDescendant(hasText(SESSION_PSEUDO))),
            useUnmergedTree = true,
        ).assertExists()
        // #884 — marking adds a StateDescription, never a heading: still exactly one per message.
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertCountEquals(2)
    }

    @Test
    fun `the four Ego preference combinations stay independent on an own message with an auto-quote`() {
        // The carrier message accumulates both markers: the reader's own message (EgoPost) quoting
        // one of their earlier messages (EgoQuote). Each preference must gate ITS marker only.
        val state = mutableStateOf(
            contentState(
                messages = listOf(ownMessageWithAutoQuote()),
                connectedPseudo = SESSION_PSEUDO,
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

        // Both on (the defaults): the cumul is visible — EgoPost card + EgoQuote inside it.
        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 1)

        compose.runOnIdle { state.value = state.value.copy(egoQuoteEnabled = false) }
        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 0)

        compose.runOnIdle {
            state.value = state.value.copy(egoQuoteEnabled = true, egoPostEnabled = false)
        }
        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 1)

        compose.runOnIdle { state.value = state.value.copy(egoQuoteEnabled = false) }
        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Test
    fun `anonymous and blank session pseudos keep both markers off`() {
        // Anonymous (connectedPseudo purged at logout) and a pseudo that canonicalizes to an empty
        // string must both collapse to the same safe no-marker rendering, stale own bit included.
        val state = mutableStateOf(
            contentState(
                messages = listOf(ownMessageWithAutoQuote(isOwnPost = true)),
                connectedPseudo = null,
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

        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)

        compose.runOnIdle { state.value = state.value.copy(connectedPseudo = "   ") }
        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Test
    fun `EgoPost marks the session author in a one-to-one conversation`() {
        // The 1:1 case is the arbitrated one (#1050 framing gate): the MP list renders uniform
        // cards with no positional alignment, so without EgoPost nothing distinguishes your own
        // messages. The session pseudo is provided in its raw padded form to prove the surface
        // routes it through canonicalization.
        setThreadContent(
            state = contentState(
                messages = listOf(
                    message(101, author = OTHER_AUTHOR, body = "Message de l'autre"),
                    message(102, author = SESSION_PSEUDO, body = "Mon message"),
                ),
                connectedPseudo = "  $SESSION_PSEUDO  ",
                isMultiRecipient = false,
            ),
            isMultiRecipientHint = false,
        )

        assertStateCount(OWN_POST_STATE, 1)
    }

    @Test
    fun `EgoPost and EgoQuote mark the session author's message in a DT`() {
        setThreadContent(
            state = contentState(
                messages = listOf(
                    message(101, author = OTHER_AUTHOR, body = "Premier participant"),
                    message(102, author = "Troisième", body = "Deuxième participant"),
                    ownMessageWithAutoQuote(numreponse = 103),
                ),
                connectedPseudo = SESSION_PSEUDO,
                isMultiRecipient = true,
            ),
            isMultiRecipientHint = true,
        )

        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 1)
        compose.onNode(
            stateMatcher(OWN_POST_STATE).and(hasAnyDescendant(hasText(SESSION_PSEUDO))),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun setThreadContent(
        state: PrivateMessageThreadUiState,
        isMultiRecipientHint: Boolean = false,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = isMultiRecipientHint,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }
    }

    private fun stateMatcher(description: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    private fun assertStateCount(description: String, expected: Int) {
        compose.onAllNodes(stateMatcher(description), useUnmergedTree = true)
            .assertCountEquals(expected)
    }

    private fun contentState(
        messages: List<Post>,
        connectedPseudo: String?,
        isMultiRecipient: Boolean = false,
    ): PrivateMessageThreadUiState {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = 1)
        return PrivateMessageThreadUiState(
            request = request,
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet",
                    correspondent = OTHER_AUTHOR,
                    messages = messages,
                    page = 1,
                    totalPages = 1,
                    canReply = false,
                    isMultiRecipient = isMultiRecipient,
                ),
            ),
            page = 1,
            totalPages = 1,
            connectedPseudo = connectedPseudo,
        )
    }

    private fun ownMessageWithAutoQuote(
        numreponse: Int = 101,
        isOwnPost: Boolean = false,
    ): Post = message(
        numreponse = numreponse,
        author = SESSION_PSEUDO,
        body = "Je me cite",
        isOwnPost = isOwnPost,
        content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = SESSION_PSEUDO,
                    numreponse = 42,
                    page = 1,
                    content = paragraph("mon ancien message"),
                ),
                PostBlock.Paragraph(inlines = listOf(PostInline.Text("Je me cite"))),
            ),
        ),
    )

    private fun message(
        numreponse: Int,
        author: String,
        body: String,
        isOwnPost: Boolean = false,
        content: PostContent = paragraph(body),
    ): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.EPOCH.plusSeconds(numreponse.toLong()),
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = isOwnPost,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))),
    )

    private companion object {
        const val THREAD_ID = 42
        const val SESSION_PSEUDO = "XaTriX"
        const val OTHER_AUTHOR = "Correspondant"
        // Same feature-owned labels as the topic's (#874 P1/Q4): « Votre message » is the MP
        // string added by this PR, « Citation de votre message » comes from the shared renderer.
        const val OWN_POST_STATE = "Votre message"
        const val OWN_QUOTE_STATE = "Citation de votre message"

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
