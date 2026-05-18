package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostEditorViewModelTest {

    private val previewParser = FakePreviewParser()
    private val replyRepository = FakeReplyRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state mirrors the request and starts with an empty draft`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(PostEditorMode.Reply, initial.mode)
            assertEquals(SAMPLE_CAT, initial.cat)
            assertEquals(SAMPLE_TOPIC_ID, initial.topicId)
            assertEquals(SAMPLE_PAGE, initial.page)
            assertEquals(SAMPLE_SUBCAT, initial.subcat)
            assertEquals("", initial.draft.text)
            assertFalse(initial.isPreviewVisible)
            assertEquals(PostContent(blocks = emptyList()), initial.preview)
            assertEquals(BbcodeValidation.Idle, initial.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content changes update the draft and clear the empty-validation hint`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(5))))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("hello", state.draft.text)
            assertEquals(BbcodeValidation.Idle, state.validation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toolbar action wraps the current selection`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(0, 5))))
        viewModel.submit(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold))
        viewModel.state.test {
            val state = awaitItem()
            assertEquals("[b]hello[/b]", state.draft.text)
            assertEquals(3, state.draft.selection.start)
            assertEquals(8, state.draft.selection.end)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling preview parses the current draft and shows it`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("[b]hi[/b]", TextRange(9))))
        viewModel.submit(PostEditorIntent.TogglePreview)
        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isPreviewVisible)
            assertEquals(previewParser.contentFor("[b]hi[/b]"), state.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preview hidden mode does not re-parse on every keystroke`() = runTest {
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("hello", TextRange(5))))
        assertEquals(0, previewParser.callCount)
    }

    @Test
    fun `reply VM fetches the form on init and clears the loading flag`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            // Skip the transient `isLoadingForm = true` state if any, settle on final.
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertNull(settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, replyRepository.formFetches)
    }

    @Test
    fun `reply VM surfaces login required when form is anonymous`() = runTest {
        replyRepository.formResult = Result.success(anonymousForm())
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(
                SubmitError.Hfr(ReplyFailureReason.LoginRequired),
                settled.submitError,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit blank content does not POST`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.SubmitClicked)
        assertEquals(0, replyRepository.submitCalls)
    }

    @Test
    fun `submit happy path emits SubmitSucceeded with targetPage`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Success(
            refreshUrl = "/hfr/.../sujet_X_20.htm#bas",
            targetPage = 20,
        )
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.effects.test {
            viewModel.submit(PostEditorIntent.SubmitClicked)
            val effect = awaitItem()
            assertEquals(PostEditorEffect.SubmitSucceeded(targetPage = 20), effect)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `submit anti-flood preserves the draft and exposes the error`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitResult = ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(
                SubmitError.Hfr(ReplyFailureReason.AntiFlood),
                settled.submitError,
            )
            // Draft survives.
            assertEquals("Hello!", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit network error keeps the draft and surfaces Network error`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        replyRepository.submitException = IOException("disconnected")
        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))
        viewModel.submit(PostEditorIntent.SubmitClicked)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isSubmitting)
            assertEquals(SubmitError.Network, settled.submitError)
            assertEquals("Hello!", settled.draft.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `double submit only triggers one POST when the first call is still in flight`() = runTest {
        replyRepository.formResult = Result.success(authenticatedForm())
        // Hold the first submit pending until the test releases it. This is the only
        // realistic shape of "double submit": with UnconfinedTestDispatcher every
        // launch completes synchronously, so without a suspension point the second
        // click would observe submitJob as already-done and re-fire.
        val gate = CompletableDeferred<Unit>()
        replyRepository.submitGate = gate
        replyRepository.submitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = newReplyViewModel()
        viewModel.submit(PostEditorIntent.ContentChanged(TextFieldValue("Hello!", TextRange(6))))

        viewModel.submit(PostEditorIntent.SubmitClicked) // launches the first submit; suspends on gate
        viewModel.submit(PostEditorIntent.SubmitClicked) // must be a no-op (job already active)

        gate.complete(Unit)
        assertEquals(1, replyRepository.submitCalls)
    }

    @Test
    fun `submit refuses when subcat is null and surfaces MissingSubcat`() = runTest {
        val viewModel = newReplyViewModel(subcat = null)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        // No fetch attempted because we never had a valid ReplyContext.
        assertEquals(0, replyRepository.formFetches)
    }

    @Test
    fun `submit refuses when subcat is the SUBCAT_UNKNOWN sentinel`() = runTest {
        // Distinct from `subcat = null` : a v3-cache row carries the -1 sentinel,
        // which is non-null but must be treated as "unknown, refresh required".
        val viewModel = newReplyViewModel(subcat = -1)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, replyRepository.formFetches)
    }

    @Test
    fun `submit refuses when subcat is zero (HFR moderator-space wire shape)`() = runTest {
        // 0 is HFR's wire shape for the moderator-only space (cf. cat=0 family).
        // No fixture exercises subcat=0 on a user topic, so write flows refuse it
        // to avoid sending a malformed POST.
        val viewModel = newReplyViewModel(subcat = 0)
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertEquals(SubmitError.MissingSubcat, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, replyRepository.formFetches)
    }

    @Test
    fun `init form fetch surfaces SessionExpired when HFR redirects to login`() = runTest {
        replyRepository.formException = fr.forumhfr.redface2.core.domain.auth.SessionExpiredException(
            "https://forum.hardware.fr/login.php",
        )
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(SubmitError.SessionExpired, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init form fetch surfaces Network error on IOException`() = runTest {
        replyRepository.formException = java.io.IOException("disconnected")
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(SubmitError.Network, settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init form fetch maps unexpected exceptions to Hfr(Unknown) instead of crashing`() = runTest {
        replyRepository.formException = IllegalStateException("HFR returned a 500 page we don't know")
        val viewModel = newReplyViewModel()
        viewModel.state.test {
            val settled = expectMostRecentItem()
            assertFalse(settled.isLoadingForm)
            assertEquals(SubmitError.Hfr(ReplyFailureReason.Unknown), settled.submitError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun newReplyViewModel(subcat: Int? = SAMPLE_SUBCAT): PostEditorViewModel =
        PostEditorViewModel(
            request = PostEditorRequest(
                mode = PostEditorMode.Reply,
                cat = SAMPLE_CAT,
                topicId = SAMPLE_TOPIC_ID,
                numreponse = null,
                page = SAMPLE_PAGE,
                subcat = subcat,
            ),
            previewParser = previewParser,
            replyRepository = replyRepository,
            diagnostics = fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog(),
        )

    private fun authenticatedForm(): ReplyForm = ReplyForm(
        hashCheck = "FAKE_HASH",
        sujet = "Fake Topic",
        hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
        isAnonymous = false,
    )

    private fun anonymousForm(): ReplyForm = ReplyForm(
        hashCheck = "FAKE_HASH",
        sujet = "Fake Topic",
        hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
        isAnonymous = true,
    )

    private class FakePreviewParser : BbcodePreviewParser {
        var callCount: Int = 0
            private set

        override fun parsePreview(bbcode: String): PostContent {
            callCount += 1
            return contentFor(bbcode)
        }

        fun contentFor(bbcode: String): PostContent = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(bbcode)))),
        )
    }

    private class FakeReplyRepository : ReplyRepository {
        var formResult: Result<ReplyForm> = Result.success(
            ReplyForm(
                hashCheck = "FAKE_HASH",
                sujet = "Fake Topic",
                hiddenFields = mapOf("cat" to "23", "subcat" to "550", "post" to "35395", "page" to "20"),
                isAnonymous = false,
            ),
        )
        var submitResult: ReplySubmitResult? = null
        var submitException: Throwable? = null
        var submitGate: CompletableDeferred<Unit>? = null
        var formException: Throwable? = null

        var formFetches: Int = 0
            private set
        var submitCalls: Int = 0
            private set

        override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
            formFetches += 1
            formException?.let { throw it }
            return formResult.getOrThrow()
        }

        override suspend fun submitReply(
            context: ReplyContext,
            form: ReplyForm,
            bbcodeContent: String,
        ): ReplySubmitResult {
            submitCalls += 1
            submitGate?.await()
            submitException?.let { throw it }
            return submitResult ?: error("submitResult not set")
        }
    }

    private companion object {
        const val SAMPLE_CAT = 23
        const val SAMPLE_TOPIC_ID = 35_395
        const val SAMPLE_PAGE = 20
        const val SAMPLE_SUBCAT = 550
    }
}
