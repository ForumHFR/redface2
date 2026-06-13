package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.input.TextFieldValue
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

    /**
     * #312 — preferences mock. `observeConfirmBeforePosting` is the only member the reply
     * ViewModel consumes; a hot [MutableStateFlow] mirrors the DataStore observe shape.
     */
    private fun smileyRepository(): SmileyRepository = mockk(relaxed = true)

    /**
     * #405 — in-memory fake [EditorDraftStore]. Records save / delete and serves preloaded drafts
     * for the restore-on-init test. A fresh instance per test (held on the class) keeps assertions
     * isolated ; the existing tests ignore it (no draft preloaded → restore is a no-op).
     */
    private val draftStore = FakeEditorDraftStore()

    private fun userPreferences(confirmBeforePosting: Boolean = false): UserPreferencesRepository =
        mockk {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
        }

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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

        val state = viewModel.state.value
        assertFalse(state.isLoadingForm)
        assertTrue(state.formAvailable)
        assertFalse(state.formError)
        // The quick-reply form carries signature as a hidden `=1`, so the toggle defaults on.
        assertTrue("signature default should be hydrated from the hidden field", state.signatureEnabled)
    }

    @Test
    fun `the wiki search carries the loaded form's userId (#440)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form().copy(userId = 54596)
        val smileys = smileyRepository()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileys,
        )

        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        advanceUntilIdle()

        coVerify(exactly = 1) { smileys.searchWiki(54596, "jap") }
    }

    @Test
    fun `submit success raises SubmitSucceeded for the conversation`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
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

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

        val state = viewModel.state.value
        assertTrue(state.formError)
        assertFalse(state.formAvailable)
        assertFalse(state.isLoadingForm)
    }

    @Test
    fun `anonymous form is treated as a form error`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form(isAnonymous = true)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

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

    // ----- #312 : confirmation avant publication ------------------------------

    @Test
    fun `confirm-before-posting OFF keeps the one-tap submit unchanged`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the submit behind the confirmation dialog`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON onSubmitConfirmed executes the real submission without re-confirming`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }

        viewModel.effects.test {
            viewModel.onSubmitConfirmed()
            assertEquals(
                PrivateMessageReplyEffect.SubmitSucceeded(threadId = 3195237, page = 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        // Confirm bypasses the preference re-check : exactly one POST, no dialog re-arm.
        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON dismissing the dialog sends nothing and keeps the draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        viewModel.onSubmitConfirmationDismissed()

        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertEquals("the draft survives the dismissal", "Coucou en privé.", viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.submitError)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON never confirms an empty draft`() = runTest {
        // The confirmation slots in AFTER validation : a blank draft fails `canSubmit`, so no
        // dialog may appear (confirming an unsendable form would be a lie).
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onSubmit()

        assertFalse("invalid form must not raise the dialog", viewModel.state.value.showSubmitConfirmation)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON re-submitting while the dialog is up keeps it armed without posting`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.onSubmit()

        assertTrue("the dialog must stay armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON rapid double onSubmitConfirmed posts exactly once`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        // Hold the first confirmed submit in flight : with UnconfinedTestDispatcher a
        // non-suspending mock would complete synchronously and the second confirm would
        // legitimately re-fire. The gate keeps `submitJob` active across both confirms.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitReply(any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        viewModel.onSubmitConfirmed() // launches the POST ; suspends on gate
        viewModel.onSubmitConfirmed() // must be a no-op (canSubmit / submitJob guards)

        gate.complete(Unit)

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `autosave persists the private body under the mpReply key after the debounce`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

        viewModel.onContentChanged(TextFieldValue("private draft"))
        advanceTimeBy(800L)

        val key = EditorDraftKey.mpReply(request.threadId)
        assertEquals("private draft", draftStore.saved[key]?.body)
        assertTrue("MP drafts must be flagged private for the logout purge", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `a stored MP draft is surfaced as restorable on init`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

        assertEquals("rescued MP", viewModel.state.value.restorableDraft)
        assertEquals("draft is not auto-applied", "", viewModel.state.value.draft.text)

        viewModel.onDraftRestoreRequested()
        assertEquals("rescued MP", viewModel.state.value.draft.text)
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `discarding deletes the cached MP draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued MP", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )

        viewModel.onDraftDiscardRequested()
        advanceUntilIdle()

        assertTrue(draftStore.deletedKeys.contains(key))
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `a successful MP reply submit deletes the cached draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, userPreferences(), draftStore, smileyRepository(),
        )
        viewModel.onContentChanged(TextFieldValue("Coucou en privé."))

        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(draftStore.deletedKeys.contains(EditorDraftKey.mpReply(request.threadId)))
    }

    /** #405 — in-memory fake [EditorDraftStore], same shape as the one in `PostEditorViewModelTest`. */
    private class FakeEditorDraftStore : EditorDraftStore {
        val saved: MutableMap<String, EditorDraftStore.Draft> = mutableMapOf()
        val deletedKeys: MutableList<String> = mutableListOf()

        fun preload(key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun load(key: String): EditorDraftStore.Draft? = saved[key]

        override suspend fun save(key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun delete(key: String) {
            deletedKeys += key
            saved.remove(key)
        }
    }
}
