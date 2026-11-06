package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

    private val mainDispatcher = UnconfinedTestDispatcher()
    private var submissionClock = DiagnosticsClock { mainDispatcher.scheduler.currentTime * 1_000_000L }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
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

    private var draftStore = VersionedEditorDraftStore()
    private val favoriteRepository = FakeHfrFavoriteSmileyRepository()
    private val localCollections = FakeSmileyLocalCollections()

    @Suppress("LongParameterList") // test factory mirroring the ViewModel's injected dependencies.
    private fun viewModel(
        repository: PrivateMessageWriteRepository,
        initialRecipient: String? = null,
        confirmBeforePosting: Boolean = false,
        smileyRepository: SmileyRepository = mockk(relaxed = true),
        uploadRepository: UploadRepository = FakeUploadRepository(),
        imageUploadReader: ImageUploadReader = FakeImageUploadReader(),
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
        clock: DiagnosticsClock = submissionClock,
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
        clock = clock,
        smileyRepository = smileyRepository,
        hfrFavoriteSmileyRepository = favoriteRepository,
        smileyLocalCollections = localCollections,
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
            clock = submissionClock,
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
            clock = submissionClock,
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
    fun `local collections and gestures use the injected shared component`() = runTest {
        val smiley = BUILTIN_HFR_SMILEYS.first()
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", top = listOf(SmileyUsageEntry(smiley.token, 2, 1)),
        )
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val viewModel = viewModel(repository)
        testScheduler.advanceUntilIdle()
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(smiley), open.mostUsedSmileys)
        requireNotNull(open.onLocalFavoriteChange)(smiley)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(Triple("alice", smiley.token, smiley.imageUrl)), localCollections.added)
        coVerify(exactly = 1) { repository.fetchComposeForm(any()) }
    }

    @Test
    fun `picker invokes the injected HFR add and remove without reloading the form`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val wiki = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
        favoriteRepository.addResult = HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val viewModel = viewModel(repository)
        testScheduler.advanceUntilIdle()
        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onFavoriteClicked(wiki)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.added)
        assertEquals(listOf(favorite), (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        viewModel.smileyPicker.onFavoriteClicked(wiki)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(wiki.token), favoriteRepository.removed)
        assertEquals(listOf("alice", "alice"), favoriteRepository.owners)
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
        coVerify(exactly = 1) { repository.fetchComposeForm(any()) }
    }

    @Test
    fun `late compose form cannot authorize favorites after an account switch`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val favorite = EditorSmiley("[:alice]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers { gate.await() }
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isLoadingForm)
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("bob")
        advanceUntilIdle()
        gate.complete(composeForm().copy(favoriteSmileys = listOf(favorite)))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoadingForm)
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertTrue(open.favoriteSmileys.isEmpty())
        assertNull(open.onFavoriteChange)
        viewModel.smileyPicker.onFavoriteClicked(favorite)
        advanceUntilIdle()
        assertTrue(favoriteRepository.owners.isEmpty())
    }

    @Test
    fun `compose favorites insert the perso token and follow a reloaded form while open`() = runTest {
        val favorite = EditorSmiley(
            "[:fictif espace:3]", "https://example.invalid/fictif.gif", EditorSmileySource.HFR_FAVORITE,
        )
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm().copy(favoriteSmileys = listOf(favorite))
        val vm = viewModel(repository)
        vm.smileyPicker.open()
        val open = vm.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        vm.onContentChanged(TextFieldValue("hello", TextRange(5)))
        vm.onSmileySelected(open.favoriteSmileys.single().token)
        assertEquals("hello [:fictif espace:3] ", vm.state.value.draft.text)
        assertEquals(SmileyPickerState.Hidden, vm.smileyPicker.state.value)

        vm.smileyPicker.open()
        val replacement = favorite.copy(token = "[:fictif_b]", imageUrl = "https://example.invalid/fictif_b.gif")
        coEvery { repository.fetchComposeForm(any()) } returns composeForm().copy(favoriteSmileys = listOf(replacement))
        vm.retryFormLoad()
        advanceUntilIdle()
        assertEquals(listOf(replacement), (vm.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys)

        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        vm.retryFormLoad()
        advanceUntilIdle()
        assertTrue((vm.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
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
    fun `unknown recipient points to recipients and keeps every field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery {
            repository.submitNewMessage(any(), any(), any(), any(), any())
        } returns ReplySubmitResult.Failure(ReplyFailureReason.UnknownRecipient)
        val vm = viewModel(repository)
        vm.onRecipientsChanged("missing-account")
        vm.onSubjectChanged("Sujet")
        vm.onContentChanged(TextFieldValue("Bonjour."))
        vm.effects.test {
            vm.onSubmit()
            expectNoEvents()
        }
        assertEquals(PrivateMessageReplyError.UnknownRecipient, vm.state.value.submitError)
        assertEquals("missing-account", vm.state.value.recipients)
        assertEquals("Sujet", vm.state.value.subject)
        assertEquals("Bonjour.", vm.state.value.draft.text)
    }

    @Test
    fun `unknown response maps to uncertainty and keeps every field`() = runTest {
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
        assertEquals(PrivateMessageReplyError.Uncertain, state.submitError)
        assertEquals("bozoleclown", state.recipients)
        assertEquals("Sujet", state.subject)
        assertEquals("Bonjour.", state.draft.text)
    }

    @Test
    fun `unknown response emits NO navigation effect — the composer must not pop`() = runTest {
        // Even with the captured success marker, an unrecognised answer cannot prove rejection.
        // It must keep the user IN the composer (banner only) — popping
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
        runCurrent() // Wait for the form refresh after the certain failure.

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
        runCurrent() // Assert after the deferred refetch, not before it starts.

        coVerify(exactly = 2) { repository.fetchComposeForm(any()) }
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
    fun `recipient hydration removes an offer identical to every live field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers { formGate.await() }
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(
                body = "rescued body",
                subject = "rescued subject",
                recipients = "alice",
                isPrivate = true,
            ),
        )
        val viewModel = viewModel(repository)
        assertEquals("rescued body", viewModel.state.value.restorableDraft)

        viewModel.onContentChanged(TextFieldValue("rescued body"))
        viewModel.onSubjectChanged("rescued subject")
        formGate.complete(composeForm(dest = "alice"))
        advanceUntilIdle()

        assertEquals("alice", viewModel.state.value.recipients)
        assertNull(viewModel.state.value.restorableDraft)
        assertNull(viewModel.state.value.restorableSubject)
        assertNull(viewModel.state.value.restorableRecipients)
    }

    @Test
    fun `composer recreation reoffers until decision and typing still autosaves`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpCompose()
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val first = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("rescued MP", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("rescued MP", recreated.state.value.restorableDraft)

        recreated.onContentChanged(TextFieldValue("new live MP"))
        advanceTimeBy(800L)
        assertEquals("new live MP", draftStore.saved[key]?.body)
        assertEquals("tester", draftStore.lastSavedOwner)

        recreated.onDraftRestoreRequested()
        advanceUntilIdle()
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))
        assertEquals("new live MP\n\nrescued MP", draftStore.saved[key]?.body)

        val afterRestore = viewModel(repository, savedStateHandle = savedStateHandle)
        advanceUntilIdle()
        assertEquals("new live MP\n\nrescued MP", afterRestore.state.value.restorableDraft)
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
    fun `typing recipients and subject leaves the pending MP row untouched`() = runTest {
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

        assertNull("no hybrid row may be written while the visible body is blank", draftStore.lastSavedOwner)
        assertEquals("rescued body", draftStore.saved[key]?.body)
        assertEquals("old subject", draftStore.saved[key]?.subject)
        assertEquals("old recipient", draftStore.saved[key]?.recipients)
        assertEquals("rescued body", viewModel.state.value.restorableDraft)

        viewModel.onDraftRestoreRequested()
        advanceUntilIdle()
        assertEquals("rescued body", viewModel.state.value.draft.text)
        assertEquals("new subject", viewModel.state.value.subject)
        assertEquals("new recipient", viewModel.state.value.recipients)
    }

    @Test
    fun `restoring appends the offered MP body and keeps live routing fields`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(
                body = "rescued body",
                subject = "rescued subject",
                recipients = "alice",
                isPrivate = true,
            ),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onRecipientsChanged("bob")
        viewModel.onSubjectChanged("fresh subject")
        viewModel.onContentChanged(TextFieldValue("fresh body"))
        viewModel.onDraftRestoreRequested()

        assertEquals("fresh body\n\nrescued body", viewModel.state.value.draft.text)
        assertEquals("fresh subject", viewModel.state.value.subject)
        assertEquals("bob", viewModel.state.value.recipients)
        assertNull(viewModel.state.value.restorableDraft)
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
    fun `a successful new-conversation submit clears content and retains the published marker`() = runTest {
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

        assertEquals("", draftStore.saved[EditorDraftKey.mpCompose()]?.body)
        assertEquals(
            EditorDraftStore.AttemptStatus.PUBLISHED, draftStore.entries[EditorDraftKey.mpCompose()]?.attempt?.status,
        )
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
        assertNull("closing must not rewrite a pending offer", draftStore.lastSavedOwner)
        assertFalse(draftStore.deletedKeys.contains(key))
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", vm.state.value.restorableDraft)
    }

    @Test
    fun `route recipients with a pending offer do not create a hybrid row on close`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpCompose()
        coEvery { repository.fetchComposeForm("bob") } returns composeForm(dest = "bob")
        draftStore.preload(
            key,
            EditorDraftStore.Draft(
                body = "private text for alice",
                subject = "alice subject",
                recipients = "alice",
                isPrivate = true,
            ),
        )
        val viewModel = viewModel(repository, initialRecipient = "bob")
        advanceUntilIdle()
        assertEquals("bob", viewModel.state.value.recipients)
        assertEquals("private text for alice", viewModel.state.value.restorableDraft)

        viewModel.onCloseRequested()

        assertEquals(PrivateMessageComposeEffect.CloseCommitted, viewModel.effects.first())
        assertNull("close must not write offered text under route recipients", draftStore.lastSavedOwner)
        assertEquals("private text for alice", draftStore.saved[key]?.body)
        assertEquals("alice", draftStore.saved[key]?.recipients)
    }

    @Test
    fun `onCloseRequested with blank fields and no pending offer deletes the stale row`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val key = EditorDraftKey.mpCompose()

        viewModel.onRecipientsChanged("alice")
        viewModel.onSubjectChanged("temporary subject")
        viewModel.onContentChanged(TextFieldValue("temporary body"))
        advanceTimeBy(800L)
        viewModel.onRecipientsChanged("")
        viewModel.onSubjectChanged("")
        viewModel.onContentChanged(TextFieldValue(""))
        viewModel.onCloseRequested()

        assertEquals(PrivateMessageComposeEffect.CloseCommitted, viewModel.effects.first())
        assertEquals("", draftStore.saved[key]?.body)
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

    @Test
    fun `headers and body are frozen while the durable attempt is pending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("original subject")
        vm.onContentChanged(TextFieldValue("original body"))
        vm.onSubmit()
        vm.onRecipientsChanged("bob")
        vm.onSubjectChanged("late subject")
        vm.onContentChanged(TextFieldValue("late body"))
        assertEquals("alice", vm.state.value.recipients)
        assertEquals("original subject", vm.state.value.subject)
        assertEquals("original body", vm.state.value.draft.text)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) {
            repository.submitNewMessage(any(), "alice", "original subject", "original body", any())
        }
    }

    /** #1301-ST — adaptateur utilisant les mêmes mocks de formulaire et fakes de brouillon/upload. */
    internal fun submissionAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        scenario: EditorSubmissionScenario,
    ): EditorSubmissionAdapter {
        draftStore = scenario.store
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
        val authRepository = object : AuthRepository by FakeAuthRepository() {
            override fun observeAuthState(): Flow<AuthState> = auth
        }
        submissionClock = DiagnosticsClock { scope.testScheduler.currentTime * 1_000_000L }
        scenario.changeAccount = {
            scenario.store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
        }
        val postGate = CompletableDeferred<Unit>()
        val uploadCompletion = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = uploadCompletion }
        draftStore.preload(
            EditorDraftKey.mpCompose(),
            EditorDraftStore.Draft(body = "stored draft", subject = "stored title", recipients = "alice"),
        )
        var formFetches = 0
        var submissionCalls = 0
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers {
            formFetches++
            composeForm()
        }
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } coAnswers {
            scenario.prepare()
            submissionCalls += 1
            scenario.awaitPost(postGate)
            scenario.postFailure?.let { throw it }
            scenario.postRejection?.let { ReplySubmitResult.Failure(it) }
                ?: ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }
        val userPreferences = object : UserPreferencesRepository by userPreferences() {
            override fun observeConfirmBeforePosting(): Flow<Boolean> = preferences
        }
        fun createViewModel() = PrivateMessageComposeViewModel(
            initialRecipient = "alice",
            repository = repository,
            previewParser = previewParser,
            userPreferencesRepository = userPreferences,
            draftStore = draftStore,
            authRepository = authRepository,
            uploadRepository = uploads,
            imageUploadReader = FakeImageUploadReader(),
            diagnostics = DiagnosticsLog(),
            clock = submissionClock,
            smileyRepository = mockk(relaxed = true),
            hfrFavoriteSmileyRepository = favoriteRepository,
            smileyLocalCollections = localCollections,
        )
        var viewModel = createViewModel()
        val effects = mutableListOf<PrivateMessageComposeEffect>()
        fun collectEffects() = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        var collector = collectEffects()
        scenario.recreate = {
            collector.cancel()
            viewModel.viewModelScope.cancel()
            viewModel = createViewModel()
            collector = collectEffects()
        }
        scenario.replaceCollector = {
            collector.cancel()
            collector = collectEffects()
        }
        val arms = mutableMapOf<Long, ArmToken>()
        return object : EditorSubmissionAdapter {
            override val unsupportedMutations = mapOf(
                SubmissionBodyMutation.IMAGE_URL to "aucune entrée URL d'image dans cet éditeur MP",
                SubmissionBodyMutation.SHARED_RESTORE to "aucune entrée resumeSharedDraft pour les MP",
            )
            override val uploadUnsupportedReason: String? = null
            override val postCalls get() = submissionCalls
            override val formFetchCalls get() = formFetches
            override val uploadCalls get() = uploads.uploadCalls
            override val draftSaves get() = draftStore.saveCount
            override val draftWrites get() = draftStore.writes
            override val successEffects get() = effects.count { it is PrivateMessageComposeEffect.SubmitSucceeded }
            override val closeEffects get() = effects.count { it is PrivateMessageComposeEffect.CloseCommitted }

            override fun editBody(body: String) {
                viewModel.onRecipientsChanged("alice")
                viewModel.onSubjectChanged("Contract title")
                viewModel.onContentChanged(TextFieldValue(body, TextRange(body.length)))
            }

            override fun clickSubmit() = viewModel.onSubmit()
            override fun confirm() = viewModel.onSubmitConfirmed(viewModel.state.value.armToken)
            override fun confirmIdentity(id: Long?) = viewModel.onSubmitConfirmed(arms[id])
            override fun dismissConfirmation() = viewModel.onSubmitConfirmationDismissed()
            override fun close() = viewModel.onCloseRequested()
            override fun completePost() { postGate.complete(Unit) }
            override fun completeUpload() { uploadCompletion.complete(Unit) }
            override fun deliverLateUploadCallback() {
                val insert = PrivateMessageComposeViewModel::class.java.getDeclaredMethod(
                    "insertImageBbcodeAtCaret", String::class.java, Boolean::class.javaPrimitiveType,
                )
                insert.isAccessible = true
                insert.invoke(viewModel, "[img]https://example.test/late.png[/img]", false)
            }
            override fun dispose() = viewModel.viewModelScope.cancel()
            override fun startUpload() = viewModel.onImagesPicked(listOf("content://pick/1"))

            override fun snapshot(): SubmissionProbe = viewModel.state.value.let {
                it.armToken?.let { token -> arms[token.id] = token }
                SubmissionProbe(
                    it.draft.text, it.canSubmit, it.isSubmitting, it.isUploading, it.showSubmitConfirmation,
                    armId = it.armToken?.id,
                    error = when (it.submitError) {
                        PrivateMessageReplyError.Network -> SubmissionErrorProbe.NETWORK
                        PrivateMessageReplyError.SessionExpired -> SubmissionErrorProbe.SESSION_EXPIRED
                        PrivateMessageReplyError.Uncertain -> SubmissionErrorProbe.UNCERTAIN
                        PrivateMessageReplyError.DraftWriteFailed -> SubmissionErrorProbe.DRAFT_WRITE_FAILED
                        PrivateMessageReplyError.Unexpected -> SubmissionErrorProbe.PREPARATION_FAILED
                        else -> null
                    },
                    restoreOffered = it.restorableDraft != null,
                    blocksClose = it.submission.blocksBodyMutation,
                )
            }

            override fun mutateBody(mutation: SubmissionBodyMutation) {
                when (mutation) {
                    SubmissionBodyMutation.TYPING -> editBody("changed during POST")
                    SubmissionBodyMutation.SMILEY -> viewModel.onSmileySelected(":jap:")
                    SubmissionBodyMutation.FORMAT -> viewModel.onToolbarAction(BbcodeAction.Bold)
                    SubmissionBodyMutation.RESTORE -> {
                        checkNotNull(viewModel.state.value.restorableDraft) { "l'offre #1415 doit être présente" }
                        viewModel.onDraftRestoreRequested()
                    }
                    SubmissionBodyMutation.IMAGE_URL, SubmissionBodyMutation.SHARED_RESTORE ->
                        error("cas explicitement désactivé")
                }
            }
        }
    }

}
