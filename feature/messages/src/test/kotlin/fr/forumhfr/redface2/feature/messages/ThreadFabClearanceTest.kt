package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1046 — the MP thread's end-of-list clearance, measured on the MOUNTED surface: with the full
 * [PrivateMessageThreadContent] composition (Scaffold + « Répondre » ExtendedFAB, #301) scrolled to
 * the absolute end of an overflowing page, the trailing pager row — the item closest to the FAB —
 * must sit entirely ABOVE the FAB, not under it. At the pre-#1046 16.dp bottom inset the pager
 * settled squarely under the FAB; the 88.dp clearance (pinned exactly, list-side, in
 * [ThreadListLayoutTest]) keeps it clear. The 360.dp-wide qualifier matches the project's narrow
 * reference device (S10e).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ThreadFabClearanceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `scrolled to the end of the list, the pager row is not covered by the reply FAB`() {
        mountOverflowingThread()

        // Guard against a trivially green run: the fixture page must overflow the viewport, so the
        // trailing pager item is NOT composed yet — otherwise the whole list fits above the FAB and
        // the measurement below would pass with ANY bottom inset, 16.dp included.
        assertEquals(
            "fixture must overflow the viewport (grow the message bodies if this fails)",
            0,
            compose.onAllNodesWithText(PAGER_POSITION).fetchSemanticsNodes().size,
        )

        // Scroll to the ABSOLUTE end: the lazy list's semantics ScrollBy clamps at the content
        // bounds, and the end of the list is where the defect lived (the last items settled under
        // the FAB). The huge delta guarantees the clamp is what stops the scroll.
        compose.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()

        // The M3 ExtendedFAB label matches in the UNMERGED tree only under this harness (the
        // merged-tree text query finds no node), so locate the FAB as the clickable ancestor of
        // its label — which also measures the full FAB envelope rather than the label's bounds.
        val fabBounds = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Répondre")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        // The pager row is the LAST list item — the deepest content at end-of-scroll, so proving IT
        // clears the FAB proves every message above it does too. Its row container carries no
        // semantics: measure its three visible children and take the deepest edge.
        val pagerBottom = listOf("Précédent", PAGER_POSITION, "Suivant").maxOf { text ->
            compose.onNodeWithText(text).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.bottom
        }
        val clearanceDp = with(compose.density) { (fabBounds.top - pagerBottom).toDp().value }
        assertTrue(
            "the trailing pager row must clear the reply FAB at end of scroll " +
                "(pager bottom ${pagerBottom}px vs FAB top ${fabBounds.top}px = ${clearanceDp}dp)",
            clearanceDp >= 0f,
        )
    }

    /**
     * Full thread surface, `canReply = true` (the FAB is in the composition) and a multi-page
     * thread (the pager row exists and trails the list). Message bodies are long on purpose:
     * the page MUST overflow the 780.dp viewport for the end-of-scroll measurement to mean
     * anything (cf. the guard in the test).
     */
    private fun mountOverflowingThread() {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = 2)
        val state = PrivateMessageThreadUiState(
            request = request,
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Dégagement FAB",
                    correspondent = "Correspondant",
                    messages = (1..MESSAGE_COUNT).map { index -> tallMessage(index) },
                    page = 2,
                    totalPages = 4,
                    canReply = true,
                ),
            ),
            page = 2,
            totalPages = 4,
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

    private fun tallMessage(numreponse: Int): Post = Post(
        numreponse = numreponse,
        author = "Auteur $numreponse",
        date = Instant.EPOCH.plusSeconds(numreponse.toLong()),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text(
                            buildString {
                                append("Message $numreponse — ")
                                repeat(6) { append("du texte de remplissage pour donner de la hauteur. ") }
                            },
                        ),
                    ),
                ),
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
        const val MESSAGE_COUNT = 8
        const val PAGER_POSITION = "Page 2 / 4"

        val NO_OP_CALLBACKS = PrivateMessageThreadCallbacks(
            onBack = {},
            onReply = {},
            onRetry = {},
            onRefresh = {},
            onSelectPage = { _, _ -> },
            onOpenRoster = {},
            onDismissRoster = {},
            onRetryRoster = {},
            onManageRecipients = {},
        )
    }
}
