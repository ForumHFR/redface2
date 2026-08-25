package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode

/**
 * Record-only visual review of the complete MP thread chrome at the narrow 360.dp reference width.
 * Four synthetic captures complement the interaction assertions in [PrivateMessageThreadContentTest]:
 * settled page pill/FAB cluster, open picker, keep-content spinner with disarmed controls, and the
 * zoomed reader with its reset chip. No real private subject, correspondent or excerpt is embedded.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:messages:testDebugUnitTest \
 *         --tests '*PrivateMessageThreadChromeRoborazziTest*' --console=plain --no-daemon
 *
 * Output: `feature/messages/build/outputs/roborazzi/private_message_thread_*.png` (gitignored).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PrivateMessageThreadChromeRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settledChrome() {
        mount()

        capture("private_message_thread_chrome")
    }

    @Test
    fun openPagePicker() {
        mount()
        compose.onNodeWithContentDescription("Page 2 sur 4").performClick()
        compose.waitForIdle()

        capture("private_message_thread_page_picker")
    }

    @Test
    fun keepContentSwitch() {
        mount(isRefreshing = true)

        capture("private_message_thread_switch")
    }

    @Test
    fun zoomResetChrome() {
        mount()
        compose.onNodeWithTag(PRIVATE_MESSAGE_THREAD_READER_TAG).performTouchInput {
            down(0, center - Offset(0f, 120f))
            down(1, center + Offset(0f, 120f))
            repeat(8) { index ->
                val halfGap = 120f + 24f * (index + 1)
                updatePointerTo(0, center - Offset(0f, halfGap))
                updatePointerTo(1, center + Offset(0f, halfGap))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        capture("private_message_thread_zoom")
    }

    private fun mount(isRefreshing: Boolean = false) {
        val state = threadState(isRefreshing)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    private fun threadState(isRefreshing: Boolean): PrivateMessageThreadUiState =
        PrivateMessageThreadUiState(
            request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = 2),
            mode = PrivateMessageThreadUiState.Mode.Content(
                thread = PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Conversation de démonstration",
                    correspondent = "Correspondant synthétique au nom long",
                    messages = listOf(
                        message(201, "PlumeBleue", "Premier message de démonstration."),
                        message(202, "MonCompteTest", "Réponse synthétique sans donnée privée."),
                    ),
                    page = 2,
                    totalPages = 4,
                    canReply = true,
                ),
            ),
            page = 2,
            totalPages = 4,
            isRefreshing = isRefreshing,
        )

    private fun message(numreponse: Int, author: String, body: String): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.parse("2026-08-25T06:00:00Z").plusSeconds(numreponse.toLong()),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(body))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = author == "MonCompteTest",
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
            onSelectPage = { _, _ -> },
            onOpenRoster = {},
            onDismissRoster = {},
            onRetryRoster = {},
            onManageRecipients = {},
        )
    }
}
