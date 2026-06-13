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
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageComposeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val previewParser = BbcodePreviewParser { PostContent(blocks = emptyList()) }

    private fun userPreferences(confirmBeforePosting: Boolean = false): UserPreferencesRepository =
        mockk {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
        }

    /** Mirrors the standalone composer's parsed shape (fixture `mp_compose_form.html`). */
    private fun composeForm(
        hashCheck: String = "abc",
        dest: String = "",
        isAnonymous: Boolean = false,
    ): ReplyForm = ReplyForm(
        hashCheck = hashCheck,
        sujet = "",
        hiddenFields = mapOf(
            "cat" to "prive",
            "post" to "",
            "numrep" to "",
            "numreponse" to "",
            "dest" to dest,
            "sujet" to "",
            "parents" to "",
            "stickold" to "",
            "pseudo" to "TestUser",
            "signature" to "1",
        ),
        isAnonymous = isAnonymous,
    )

    private val draftStore = FakeEditorDraftStore()

    private fun viewModel(
        repository: PrivateMessageWriteRepository,
        initialRecipient: String? = null,
        confirmBeforePosting: Boolean = false,
        smileyRepository: SmileyRepository = mockk(relaxed = true),
    ): PrivateMessageComposeViewModel = PrivateMessageComposeViewModel(
        initialRecipient = initialRecipient,
        repository = repository,
        previewParser = previewParser,
        userPreferencesRepository = userPreferences(confirmBeforePosting),
        draftStore = draftStore,
        smileyRepository = smileyRepository,
    )

    @Test
    fun `the wiki search carries the loaded form's userId (#440)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm().copy(userId = 54596)
        val smileys = mockk<SmileyRepository>(relaxed = true)

        val vm = viewModel(repository, smileyRepository = smileys)

        vm.smileyPicker.open()
        vm.smileyPicker.onQueryChanged("jap")
        advanceUntilIdle()

        coVerify(exactly = 1) { smileys.searchWiki(54596, "jap") }
    }

    @Test
    fun `loads the composer form on init and hydrates the signature default`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()

        val vm = viewModel(repository)

        val state = vm.state.value
        assertFalse(state.isLoadingForm)
        assertTrue(state.formAvailable)
        assertTrue("signature default should be hydrated", state.signatureEnabled)
    }

    @Test
    fun `initialRecipient rides the form GET and seeds the recipients field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm("bozoleclown") } returns composeForm(dest = "bozoleclown")

        val vm = viewModel(repository, initialRecipient = "bozoleclown")

        coVerify(exactly = 1) { repository.fetchComposeForm("bozoleclown") }
        assertEquals("bozoleclown", vm.state.value.recipients)
    }

    @Test
    fun `a server-side dest prefill seeds recipients when the field is still blank`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm(dest = "bozoleclown")

        val vm = viewModel(repository)

        assertEquals("bozoleclown", vm.state.value.recipients)
    }

    @Test
    fun `canSubmit requires recipients, subject and body`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()

        val vm = viewModel(repository)
        assertFalse(vm.state.value.canSubmit)

        vm.onRecipientsChanged("bozoleclown")
        assertFalse(vm.state.value.canSubmit)
        vm.onSubjectChanged("Hello")
        assertFalse(vm.state.value.canSubmit)
        vm.onContentChanged(TextFieldValue("corps"))
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `subject is truncated to HFR's 70-char maxlength`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()

        val vm = viewModel(repository)
        vm.onSubjectChanged("x".repeat(120))

        assertEquals(PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH, vm.state.value.subject.length)
    }

    @Test
    fun `submit success sends trimmed fields and raises SubmitSucceeded`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val vm = viewModel(repository)
        vm.onRecipientsChanged(" bozoleclown, Lt Ripley ")
        vm.onSubjectChanged("  Sujet  ")
        vm.onContentChanged(TextFieldValue("Bonjour."))

        vm.effects.test {
            vm.onSubmit()
            assertEquals(PrivateMessageComposeEffect.SubmitSucceeded, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            repository.submitNewMessage(
                form = any(),
                recipients = "bozoleclown, Lt Ripley",
                subject = "Sujet",
                bbcodeContent = "Bonjour.",
                options = any(),
            )
        }
        assertFalse(vm.state.value.isSubmitting)
    }

    @Test
    fun `unknown response maps to the non-destructive Unexpected banner and keeps every field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Failure(ReplyFailureReason.Unknown)

        val vm = viewModel(repository)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        vm.onSubmit()

        val state = vm.state.value
        assertEquals(PrivateMessageReplyError.Unexpected, state.submitError)
        assertEquals("bozoleclown", state.recipients)
        assertEquals("Sujet", state.subject)
        assertEquals("Bonjour.", state.draft.text)
    }

    @Test
    fun `unknown response emits NO navigation effect — the composer must not pop`() = runTest {
        // Codex review of #404 : the new-MP success response is not pinned by a live fixture.
        // An unrecognised answer must keep the user IN the composer (banner only) — popping
        // would discard the visible draft on an unproven outcome.
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Failure(ReplyFailureReason.Unknown)

        val vm = viewModel(repository)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))

        vm.effects.test {
            vm.onSubmit()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid hash_check refetches the form silently so the user can re-submit`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val vm = viewModel(repository)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        vm.onSubmit()

        assertEquals(PrivateMessageReplyError.InvalidHashCheck, vm.state.value.submitError)
        // init + the silent refetch triggered by the stale hash.
        coVerify(exactly = 2) { repository.fetchComposeForm(any()) }
    }

    @Test
    fun `a silent refetch never clobbers a recipients edit made in between`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm(dest = "bozoleclown")
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val vm = viewModel(repository)
        vm.onRecipientsChanged("quelqu'un d'autre")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        vm.onSubmit() // stale hash → silent refetch returns dest=bozoleclown again

        assertEquals(
            "the hydration guard must protect the user's recipients edit",
            "quelqu'un d'autre",
            vm.state.value.recipients,
        )
    }

    @Test
    fun `an anonymous composer form surfaces the form error state`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm(isAnonymous = true)

        val vm = viewModel(repository)

        assertTrue(vm.state.value.formError)
        assertFalse(vm.state.value.formAvailable)
    }

    @Test
    fun `confirm-before-posting arms the confirmation instead of submitting`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()

        val vm = viewModel(repository, confirmBeforePosting = true)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        vm.onSubmit()

        assertTrue(vm.state.value.showSubmitConfirmation)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `autosave persists the private recipients subject and body under the mpCompose key`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)

        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Salut")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        advanceTimeBy(800L)

        val key = EditorDraftKey.mpCompose()
        val saved = draftStore.saved[key]
        assertEquals("Bonjour.", saved?.body)
        assertEquals("Salut", saved?.subject)
        assertEquals("bozoleclown", saved?.recipients)
        assertTrue("MP drafts must be flagged private for the logout purge", saved?.isPrivate == true)
    }

    @Test
    fun `a stored compose draft is surfaced as restorable on init`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(
                body = "rescued body",
                subject = "rescued subject",
                recipients = "rescued dest",
                isPrivate = true,
            ),
        )
        val vm = viewModel(repository)

        assertEquals("rescued body", vm.state.value.restorableDraft)
        assertEquals("rescued subject", vm.state.value.restorableSubject)
        assertEquals("rescued dest", vm.state.value.restorableRecipients)
        assertEquals("draft is not auto-applied", "", vm.state.value.draft.text)

        vm.onDraftRestoreRequested()
        assertEquals("rescued body", vm.state.value.draft.text)
        assertEquals("rescued subject", vm.state.value.subject)
        assertEquals("rescued dest", vm.state.value.recipients)
        assertEquals(null, vm.state.value.restorableDraft)
    }

    @Test
    fun `a successful new-conversation submit deletes the cached draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val vm = viewModel(repository)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))

        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(draftStore.deletedKeys.contains(EditorDraftKey.mpCompose()))
    }

    /** #405 — in-memory fake [EditorDraftStore], same shape as the one in `PostEditorViewModelTest`. */
    private class FakeEditorDraftStore : EditorDraftStore {
        val saved: MutableMap<String, EditorDraftStore.Draft> = mutableMapOf()
        val deletedKeys: MutableList<String> = mutableListOf()

        fun preload(key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun currentOwner(): String? = "tester"

        override suspend fun load(owner: String?, key: String): EditorDraftStore.Draft? = saved[key]

        override suspend fun save(owner: String?, key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun delete(owner: String?, key: String) {
            deletedKeys += key
            saved.remove(key)
        }
    }
}
