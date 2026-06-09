package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
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
class PrivateMessageReplyViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val request = PrivateMessageReplyRequest(threadId = 3195237, page = 1)

    private val previewParser = BbcodePreviewParser { PostContent(blocks = emptyList()) }

    private fun form(
        hashCheck: String = "abc",
        hiddenFields: Map<String, String> = mapOf(
            "cat" to "prive",
            "post" to "3195237",
            "numrep" to "1980677227",
            "signature" to "1",
        ),
        isAnonymous: Boolean = false,
    ): ReplyForm = ReplyForm(
        hashCheck = hashCheck,
        sujet = "Sujet prive de test",
        hiddenFields = hiddenFields,
        isAnonymous = isAnonymous,
    )

    @Test
    fun `loads the form on init and hydrates signature from the hidden field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)

        val state = viewModel.state.value
        assertFalse(state.isLoadingForm)
        assertTrue(state.formAvailable)
        assertFalse(state.formError)
        // The quick-reply form carries signature as a hidden `=1`, so the toggle defaults on.
        assertTrue("signature default should be hydrated from the hidden field", state.signatureEnabled)
    }

    @Test
    fun `submit success raises SubmitSucceeded for the conversation`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))

        viewModel.effects.test {
            viewModel.onSubmit()
            assertEquals(
                PrivateMessageReplyEffect.SubmitSucceeded(threadId = 3195237, page = 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.state.value.isSubmitting)
        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `empty-message failure shows the banner and keeps the draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)
        viewModel.onContentChanged(TextFieldValue("non-blank"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(PrivateMessageReplyError.Empty, state.submitError)
        assertEquals("non-blank", state.draft.text)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `invalid hash check refetches the form silently`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()

        assertEquals(PrivateMessageReplyError.InvalidHashCheck, viewModel.state.value.submitError)
        // init fetch + the silent refetch after the expired hash_check.
        coVerify(exactly = 2) { repository.fetchReplyForm(any()) }
    }

    @Test
    fun `invalid hash check refetch preserves the user option toggles`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)
        // First load hydrates signature ON (hidden `signature=1`); the user turns it OFF.
        viewModel.onToggleSignature(false)
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()

        // The silent refetch after the expired hash_check must NOT re-hydrate the toggle back to
        // HFR's default — the user's choice survives into the second submit.
        assertFalse(
            "InvalidHashCheck refetch must preserve the user's signature toggle",
            viewModel.state.value.signatureEnabled,
        )
        coVerify(exactly = 2) { repository.fetchReplyForm(any()) }
    }

    @Test
    fun `unknown response maps to the non-destructive unexpected banner`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)
        viewModel.onContentChanged(TextFieldValue("hello"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(PrivateMessageReplyError.Unexpected, state.submitError)
        // Non-destructive: the draft survives so the user can verify and retry.
        assertEquals("hello", state.draft.text)
    }

    @Test
    fun `form fetch failure shows the retry state`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } throws IOException("network down")

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)

        val state = viewModel.state.value
        assertTrue(state.formError)
        assertFalse(state.formAvailable)
        assertFalse(state.isLoadingForm)
    }

    @Test
    fun `anonymous form is treated as a form error`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form(isAnonymous = true)

        val viewModel = PrivateMessageReplyViewModel(request, repository, previewParser)

        assertTrue(viewModel.state.value.formError)
        assertFalse(viewModel.state.value.formAvailable)
    }

    @Test
    fun `context carries the request thread and page`() {
        // Guards the route → context mapping the repository relies on.
        val context = PrivateMessageReplyContext(threadId = request.threadId, page = request.page)
        assertEquals(3195237, context.threadId)
        assertEquals(1, context.page)
    }
}
