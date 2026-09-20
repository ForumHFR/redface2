package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
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
import fr.forumhfr.redface2.core.ui.editor.UploadError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
            // #459 — the composer now mirrors the image-insert preference on init.
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
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

    @Suppress("LongParameterList") // test factory mirroring the ViewModel's injected dependencies.
    private fun viewModel(
        repository: PrivateMessageWriteRepository,
        initialRecipient: String? = null,
        confirmBeforePosting: Boolean = false,
        smileyRepository: SmileyRepository = mockk(relaxed = true),
        uploadRepository: UploadRepository = FakeUploadRepository(),
        imageUploadReader: ImageUploadReader = FakeImageUploadReader(),
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PrivateMessageComposeViewModel = PrivateMessageComposeViewModel(
        initialRecipient = initialRecipient,
        repository = repository,
        previewParser = previewParser,
        userPreferencesRepository = userPreferences(confirmBeforePosting),
        draftStore = draftStore,
        authRepository = FakeAuthRepository(),
        uploadRepository = uploadRepository,
        imageUploadReader = imageUploadReader,
        diagnostics = diagnostics,
        smileyRepository = smileyRepository,
        savedStateHandle = savedStateHandle,
    )

    @Test
    fun `picked images upload and insert one img per success (#459)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val vm = viewModel(repository, uploadRepository = uploads, imageUploadReader = reader)
        advanceUntilIdle()

        vm.onImagesPicked(listOf("content://pick/1", "content://pick/2"))
        advanceUntilIdle()

        assertEquals(listOf("content://pick/1", "content://pick/2"), reader.readUris)
        assertEquals(2, uploads.uploadCalls)
        assertEquals(2, Regex("\\[img]").findAll(vm.state.value.draft.text).count())
        assertFalse(vm.state.value.isUploading)
    }

    @Test
    fun `empty picker result shows a banner without starting an upload`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val vm = viewModel(
            repository = repository,
            uploadRepository = uploads,
            imageUploadReader = reader,
            diagnostics = diagnostics,
        )
        advanceUntilIdle()
        vm.onContentChanged(
            TextFieldValue(text = "draft", selection = TextRange(1, 4)),
        )
        advanceUntilIdle()
        val stateBefore = vm.state.value

        vm.effects.test {
            vm.onImagePickerEvent(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.GET_MULTIPLE_CONTENTS,
                    uris = emptyList(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                stateBefore.copy(uploadError = UploadError.NoImageReceived),
                vm.state.value,
            )
            assertFalse(vm.state.value.isUploading)
            assertTrue(reader.readUris.isEmpty())
            assertEquals(0, uploads.uploadCalls)
            assertEquals(
                listOf(
                    "result contract=GetMultipleContents count=0 sources=[]",
                    "onImagesPicked count=0",
                ),
                diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `single picker result keeps the upload path and no empty-result error`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val vm = viewModel(
            repository = repository,
            uploadRepository = uploads,
            imageUploadReader = reader,
        )
        advanceUntilIdle()
        val uri = "content://media/picker/photo/1"

        vm.onImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                uris = listOf(uri),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(uri), reader.readUris)
        assertEquals(1, uploads.uploadCalls)
        assertNull(vm.state.value.uploadError)
        assertFalse(vm.state.value.isUploading)
    }

    @Test
    fun `get content result logs eleven raw uris then uploads the first ten`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val vm = viewModel(
            repository = repository,
            uploadRepository = uploads,
            imageUploadReader = reader,
            diagnostics = diagnostics,
        )
        advanceUntilIdle()
        val uris = (1..11).map {
            "content://com.google.android.apps.photos.contentprovider/private/photo-$it.jpg"
        }

        vm.onImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.GET_MULTIPLE_CONTENTS,
                uris = uris,
            ),
        )
        advanceUntilIdle()

        assertEquals(uris.take(10), reader.readUris)
        assertEquals(10, uploads.uploadCalls)
        assertEquals(
            listOf(
                "result contract=GetMultipleContents count=11 " +
                    "sources=[content://com.google.android.apps.photos.contentprovider]",
                "onImagesPicked count=10",
            ),
            diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
        )
    }

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
    fun `recipient prefill does not hide a different stored compose draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm("bozoleclown") } returns composeForm(dest = "bozoleclown")
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(
                body = "rescued body",
                subject = "rescued subject",
                recipients = "rescued dest",
                isPrivate = true,
            ),
        )

        val vm = viewModel(repository, initialRecipient = "bozoleclown")
        advanceUntilIdle()

        assertEquals("bozoleclown", vm.state.value.recipients)
        assertEquals("rescued body", vm.state.value.restorableDraft)
        assertEquals("rescued subject", vm.state.value.restorableSubject)
        assertEquals("rescued dest", vm.state.value.restorableRecipients)
    }

    @Test
    fun `composer recreation reoffers until restore then keeps the cached version handled`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val first = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("rescued MP", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("rescued MP", recreated.state.value.restorableDraft)

        recreated.onDraftRestoreRequested()
        advanceUntilIdle()
        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)

        val handled = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertNull(handled.state.value.restorableDraft)
    }

    @Test
    fun `composer process recreation offers a newer autosaved draft after live state was lost`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        val key = EditorDraftKey.mpCompose()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(key, EditorDraftStore.Draft(body = "first MP", isPrivate = true))
        val first = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("first MP", first.state.value.restorableDraft)

        draftStore.preload(key, EditorDraftStore.Draft(body = "new MP", isPrivate = true))
        val recreated = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()

        assertEquals("new MP", recreated.state.value.restorableDraft)
    }

    @Test
    fun `typing recipients and subject preserves the offered MP body through autosave and restore`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpCompose()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            key,
            EditorDraftStore.Draft(
                body = "rescued body",
                subject = "old subject",
                recipients = "old recipient",
                isPrivate = true,
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.onRecipientsChanged("new recipient")
        viewModel.onSubjectChanged("new subject")
        advanceTimeBy(800L)

        assertEquals("rescued body", draftStore.saved[key]?.body)
        assertEquals("new subject", draftStore.saved[key]?.subject)
        assertEquals("new recipient", draftStore.saved[key]?.recipients)
        assertEquals("rescued body", viewModel.state.value.restorableDraft)

        viewModel.onDraftRestoreRequested()
        advanceUntilIdle()
        assertEquals("rescued body", viewModel.state.value.draft.text)
        assertEquals("new subject", viewModel.state.value.subject)
        assertEquals("new recipient", viewModel.state.value.recipients)
    }

    @Test
    fun `moving selection keeps the compose restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(body = "rescued body", isPrivate = true),
        )
        val viewModel = viewModel(repository)
        viewModel.onContentChanged(TextFieldValue("live body", selection = TextRange(9)))
        assertEquals("rescued body", viewModel.state.value.restorableDraft)

        viewModel.onContentChanged(TextFieldValue("live body", selection = TextRange(2)))

        assertEquals("rescued body", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `discard records the decision and a recreation does not reoffer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = viewModel(repository, savedStateHandle = savedStateHandle)

        viewModel.onDraftDiscardRequested()
        advanceUntilIdle()

        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)
        val recreated = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertNull(recreated.state.value.restorableDraft)
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

    // ----- #803 pattern : dirty close (flush before pop), state-hygiene audit 2026-07-05 -----

    @Test
    fun `onCloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window. The flush must
        // persist the state at close time, not the snapshot the debounce captured at scheduling.
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Salut")
        vm.onContentChanged(TextFieldValue("dernier mot"))
        vm.onCloseRequested()

        val effect = vm.effects.first()
        assertEquals(PrivateMessageComposeEffect.CloseCommitted, effect)
        val saved = draftStore.saved[EditorDraftKey.mpCompose()]
        assertEquals("the tail of the draft must reach the row before the pop", "dernier mot", saved?.body)
        assertEquals("Salut", saved?.subject)
        assertEquals("bozoleclown", saved?.recipients)
        assertTrue("MP drafts must stay flagged private", saved?.isPrivate == true)
    }

    @Test
    fun `onCloseRequested with blank live fields preserves a pending restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val key = EditorDraftKey.mpCompose()
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale", isPrivate = true))
        val vm = viewModel(repository)

        vm.onCloseRequested()

        val effect = vm.effects.first()
        assertEquals(PrivateMessageComposeEffect.CloseCommitted, effect)
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", vm.state.value.restorableDraft)
    }

    @Test
    fun `onCloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        // Hold the POST in flight so the close lands while isSubmitting is true.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }
        val vm = viewModel(repository)
        vm.onRecipientsChanged("bozoleclown")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("en vol"))

        vm.onSubmit()
        // Close requested while the POST is in flight — must be inert (gate #803).
        vm.onCloseRequested()
        gate.complete(Unit)
        advanceUntilIdle()

        vm.effects.test {
            assertTrue(
                "the submit outcome must be the ONLY effect — no CloseCommitted",
                awaitItem() is PrivateMessageComposeEffect.SubmitSucceeded,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second onCloseRequested is a no-op (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)

        vm.onCloseRequested()
        vm.onCloseRequested()
        advanceUntilIdle()

        vm.effects.test {
            assertEquals(PrivateMessageComposeEffect.CloseCommitted, awaitItem())
            // A double close must never yield a second pop (it would remove the screen BELOW).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** #405 — in-memory fake [EditorDraftStore], same shape as the one in `PostEditorViewModelTest`. */
    private class FakeEditorDraftStore : EditorDraftStore {
        val saved: MutableMap<String, EditorDraftStore.Draft> = mutableMapOf()
        val deletedKeys: MutableList<String> = mutableListOf()
        var lastSavedOwner: String? = null
            private set

        fun preload(key: String, draft: EditorDraftStore.Draft) {
            saved[key] = draft
        }

        override suspend fun currentOwner(): String? = "tester"

        override suspend fun load(owner: String?, key: String): EditorDraftStore.Draft? = saved[key]

        override suspend fun save(owner: String?, key: String, draft: EditorDraftStore.Draft) {
            lastSavedOwner = owner
            saved[key] = draft
        }

        override suspend fun delete(owner: String?, key: String) {
            deletedKeys += key
            saved.remove(key)
        }
    }
}
