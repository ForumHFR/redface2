package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.snapshots.Snapshot
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
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
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
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.UploadProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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

    private val viewModels = mutableListOf<PrivateMessageComposeViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
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
        previewParser: BbcodePreviewParser = this.previewParser,
        defaultDispatcher: CoroutineDispatcher = mainDispatcher,
    ): PrivateMessageComposeViewModel = PrivateMessageComposeViewModel(
        initialRecipient = initialRecipient,
        repository = repository,
        previewParser = previewParser,
        defaultDispatcher = defaultDispatcher,
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
    ).also { viewModels += it }

    /** Synthetic JVM user edit; the Compose tests separately exercise real typing/paste. */
    private fun PrivateMessageComposeViewModel.editBody(
        value: TextFieldValue,
        advanceMirror: Boolean = true,
    ) {
        bodyFieldState.edit {
            if (asCharSequence().toString() != value.text) replace(0, length, value.text)
            selection = value.selection
            with(bodyInputTransformation) { transformInput() }
        }
        Snapshot.sendApplyNotifications()
        if (advanceMirror) mainDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `successful form retry clears preparation errors and preserves uncertain delivery`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("title")
        advanceUntilIdle()
        vm.editBody(TextFieldValue("body"))
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } throws
            EditorPreparationException(EditorPreparationException.Reason.INVALID_FORM)
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(EditorErrors.invalidForm, vm.state.value.submitError)
        vm.retryFormLoad()
        advanceUntilIdle()
        assertNull(vm.state.value.submitError)
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        vm.onSubmit()
        advanceUntilIdle()
        val uncertain = vm.state.value.submitError
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageCompose), uncertain)
        vm.retryFormLoad()
        advanceUntilIdle()
        assertEquals(uncertain, vm.state.value.submitError)
    }

    @Test
    fun `new upload clears the old failure only after passing the upload guard`() = runTest {
        val uploads = FakeUploadRepository().apply { uploadException = UploadException.TooLarge(1) }
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository, uploadRepository = uploads)
        advanceUntilIdle()
        vm.onImagesPicked(listOf("content://first"))
        advanceUntilIdle()
        assertEquals(EditorErrors.tooLarge, vm.state.value.uploadError)
        uploads.uploadException = null
        val gate = CompletableDeferred<Unit>()
        uploads.uploadGate = gate
        vm.onImagesPicked(listOf("content://second"))
        assertNull(vm.state.value.uploadError)
        vm.onImagePickerEvent(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        )
        vm.onImagesPicked(List(11) { "content://ignored/$it" })
        assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        gate.complete(Unit)
        advanceUntilIdle()
        vm.onImagesPicked(listOf("content://third"))
        advanceUntilIdle()
        assertNull(vm.state.value.uploadError)
        assertEquals(3, uploads.uploadCalls)
    }

    @Test
    fun `partial upload retains count failure and inserted image after typing`() = runTest {
        val uploads = FakeUploadRepository().apply {
            uploadException = UploadException.TooLarge(1)
            failOnCall = 2
        }
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository, uploadRepository = uploads)
        advanceUntilIdle()
        vm.onImagesPicked(listOf("content://pick/1", "content://pick/2", "content://pick/3"))
        advanceUntilIdle()
        assertEquals(2, uploads.uploadCalls)
        assertEquals(1, Regex("\\[img]").findAll(vm.bodyFieldState.text.toString()).count())
        val expected = EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(1, 3))
        assertEquals(expected, vm.state.value.uploadError)
        vm.editBody(TextFieldValue(vm.bodyFieldState.text.toString() + "typed"))
        assertEquals(expected, vm.state.value.uploadError)
        assertFalse(vm.state.value.isUploading)
    }

    @Test
    fun `ED131 compose errors survive typing selection recipients subject options and insertion`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("title")
        vm.editBody(TextFieldValue("body"))
        vm.onSubmit()
        advanceUntilIdle()
        vm.onImagePickerEvent(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        )
        listOf<() -> Unit>(
            { vm.editBody(TextFieldValue("changed")) },
            { vm.editBody(TextFieldValue("changed", TextRange(1, 3))) },
            { vm.onRecipientsChanged("bob") },
            { vm.onSubjectChanged("changed") },
            { vm.onToggleSignature(true) },
            { vm.onToolbarAction(BbcodeAction.Bold) },
        ).forEach { mutate ->
            mutate()
            assertEquals(EditorErrors.empty, vm.state.value.submitError)
            assertEquals(EditorErrors.pickerAbsent, vm.state.value.uploadError)
        }
    }

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
        assertEquals(2, Regex("\\[img]").findAll(vm.bodyFieldState.text.toString()).count())
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
        vm.editBody(
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
                stateBefore.copy(uploadError = EditorErrors.noImageReceived),
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
        assertEquals(EditorErrors.imageLimit, vm.state.value.uploadError)
        assertEquals(
            listOf(
                "result contract=GetMultipleContents count=11 " +
                    "sources=[content://com.google.android.apps.photos.contentprovider]",
                "onImagesPicked count=11",
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
        vm.editBody(TextFieldValue("hello", TextRange(5)))
        vm.onSmileySelected(open.favoriteSmileys.single().token)
        assertEquals("hello [:fictif espace:3] ", vm.bodyFieldState.text.toString())
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
        vm.editBody(TextFieldValue("corps"))
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
        vm.editBody(TextFieldValue("Bonjour."))

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
        vm.editBody(TextFieldValue("Bonjour."))
        vm.effects.test {
            vm.onSubmit()
            expectNoEvents()
        }
        assertEquals(EditorErrors.unknownRecipient, vm.state.value.submitError)
        assertEquals("missing-account", vm.state.value.recipients)
        assertEquals("Sujet", vm.state.value.subject)
        assertEquals("Bonjour.", vm.bodyFieldState.text.toString())
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
        vm.editBody(TextFieldValue("Bonjour."))
        vm.onSubmit()

        val state = vm.state.value
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageCompose), state.submitError)
        assertEquals("bozoleclown", state.recipients)
        assertEquals("Sujet", state.subject)
        assertEquals("Bonjour.", state.bodyText)
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
        vm.editBody(TextFieldValue("Bonjour."))

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
        vm.editBody(TextFieldValue("Bonjour."))
        vm.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        assertEquals(EditorErrors.invalidHashCheck, vm.state.value.submitError)
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
        vm.editBody(TextFieldValue("Bonjour."))
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
        vm.editBody(TextFieldValue("Bonjour."))
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
        vm.editBody(TextFieldValue("Bonjour."))
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
        assertEquals("draft is not auto-applied", "", vm.bodyFieldState.text.toString())

        vm.onDraftRestoreRequested()
        assertEquals("rescued body", vm.bodyFieldState.text.toString())
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

        viewModel.editBody(TextFieldValue("rescued body"))
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

        recreated.editBody(TextFieldValue("new live MP"))
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
        assertEquals("rescued body", viewModel.bodyFieldState.text.toString())
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
        viewModel.editBody(TextFieldValue("fresh body"))
        viewModel.onDraftRestoreRequested()

        assertEquals("fresh body\n\nrescued body", viewModel.bodyFieldState.text.toString())
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
        viewModel.editBody(TextFieldValue("live body", selection = TextRange(9)))
        assertEquals("rescued body", viewModel.state.value.restorableDraft)

        viewModel.editBody(TextFieldValue("live body", selection = TextRange(2)))

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
        vm.editBody(TextFieldValue("Bonjour."))

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
        vm.editBody(TextFieldValue("dernier mot"))
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
        viewModel.editBody(TextFieldValue("temporary body"))
        advanceTimeBy(800L)
        viewModel.onRecipientsChanged("")
        viewModel.onSubjectChanged("")
        viewModel.editBody(TextFieldValue(""))
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
        vm.editBody(TextFieldValue("en vol"))

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
        vm.editBody(TextFieldValue("original body"))
        vm.onSubmit()
        vm.onRecipientsChanged("bob")
        vm.onSubjectChanged("late subject")
        vm.editBody(TextFieldValue("late body"))
        assertEquals("alice", vm.state.value.recipients)
        assertEquals("original subject", vm.state.value.subject)
        assertEquals("original body", vm.bodyFieldState.text.toString())
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) {
            repository.submitNewMessage(any(), "alice", "original subject", "original body", any())
        }
    }

    @Test
    fun submitUsesLiveBodyBeforeMirrorEmission() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("title")
        vm.bodyFieldState.edit { append("live body") }
        assertEquals("", vm.state.value.bodyText)
        assertFalse(vm.state.value.canSubmit)

        vm.onSubmit()
        runCurrent()

        coVerify(exactly = 1) { repository.submitNewMessage(any(), "alice", "title", "live body", any()) }
        assertEquals("live body", draftStore.saved[EditorDraftKey.mpCompose()]?.body)
    }

    @Test
    fun submitRejectsBlankLiveBodyWhenMirrorStillAllowsIt() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("title")
        vm.editBody(TextFieldValue("body"))
        assertTrue(vm.state.value.canSubmit)
        vm.bodyFieldState.edit { replace(0, length, "") }
        assertEquals("body", vm.state.value.bodyText)
        vm.onSubmit()
        runCurrent()
        assertEquals(0, draftStore.beginCalls)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun submitFreezesBodySubjectAndRecipients() = runTest {
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository)
        vm.onRecipientsChanged(" alice, bob ")
        vm.onSubjectChanged(" title ")
        vm.onToggleSignature(false)
        vm.onToggleSmileyDisabled(true)
        vm.onToggleEmailNotification(true)
        vm.bodyFieldState.edit { append("frozen body") }
        assertEquals("", vm.state.value.bodyText)
        vm.onSubmit()
        vm.onRecipientsChanged("mallory")
        vm.onSubjectChanged("changed")
        vm.editBody(TextFieldValue("late input"))
        vm.onToggleSignature(true)
        vm.onToggleSmileyDisabled(false)
        vm.onToggleEmailNotification(false)
        gate.complete(Unit)
        runCurrent()

        val saved = draftStore.saved.getValue(EditorDraftKey.mpCompose())
        assertEquals("frozen body", saved.body)
        assertEquals(" title ", saved.subject)
        assertEquals(" alice, bob ", saved.recipients)
        assertTrue(saved.isPrivate)
        coVerify(exactly = 1) {
            repository.submitNewMessage(any(), "alice, bob", "title", "frozen body", match {
                !it.signatureEnabled && it.smileyDisabled && it.emailNotificationEnabled
            })
        }
        assertEquals("frozen body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun armingAndConfirmingValidateTheLiveBody() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository, confirmBeforePosting = true)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("title")
        vm.bodyFieldState.edit { append("live body") }
        vm.onSubmit()
        val token = vm.state.value.armToken
        assertTrue(token != null)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals("the mirror must never disarm", token, vm.state.value.armToken)
        advanceTimeBy(500)
        vm.onSubmitConfirmed(token)
        runCurrent()
        coVerify(exactly = 1) { repository.submitNewMessage(any(), "alice", "title", "live body", any()) }
    }

    @Test
    fun restoreOfferUsesLiveBody() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchComposeForm(any()) } coAnswers { formGate.await() }
        draftStore.preload(EditorDraftKey.mpCompose(), EditorDraftStore.Draft(
            body = "rescued body", subject = "subject", recipients = "alice", isPrivate = true,
        ))
        val vm = viewModel(repository)
        assertEquals("rescued body", vm.state.value.restorableDraft)
        vm.onSubjectChanged("subject")
        vm.bodyFieldState.edit { append("rescued body") }
        assertEquals("", vm.state.value.bodyText)
        formGate.complete(composeForm(dest = "alice"))
        runCurrent()
        assertNull(vm.state.value.restorableDraft)
        assertEquals("alice", vm.state.value.recipients)
        assertEquals("rescued body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun draftOfferAndRequestedRestoreReadBodyBeforeSnapshotNotification() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val key = EditorDraftKey.mpCompose()
        draftStore.preload(key, EditorDraftStore.Draft(body = "stored", isPrivate = true))
        val vm = viewModel(repository)
        vm.bodyFieldState.edit { append("typed") }
        assertEquals("", vm.state.value.bodyText)
        vm.onDraftRestoreRequested()
        assertEquals("typed\n\nstored", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(13), vm.bodyFieldState.selection)
        assertNull(vm.state.value.restorableDraft)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals("typed\n\nstored", draftStore.saved[key]?.body)
    }

    @Test
    fun delayedDraftOfferDoesNotOverwriteLiveTyping() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        draftStore.preload(EditorDraftKey.mpCompose(), EditorDraftStore.Draft(body = "stored", isPrivate = true))
        val loadGate = CompletableDeferred<Unit>()
        draftStore.loadGate = loadGate
        val vm = viewModel(repository)
        vm.bodyFieldState.edit { append("typed before load") }
        assertEquals("", vm.state.value.bodyText)
        loadGate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.restorableDraft)
        assertEquals("typed before load", vm.bodyFieldState.text.toString())
    }

    @Test
    fun autosaveReadsLiveBodyAtDeadline() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)
        advanceTimeBy(800)
        runCurrent()
        assertEquals("initial snapshot must not save an empty draft", 0, draftStore.saveCount)
        vm.editBody(TextFieldValue("first"))
        advanceTimeBy(749)
        vm.bodyFieldState.edit { replace(0, length, "latest") }
        assertEquals("first", vm.state.value.bodyText)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("latest", draftStore.saved[EditorDraftKey.mpCompose()]?.body)
    }

    @Test
    fun selectionChangeDoesNotAutosave() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("body"))
        advanceUntilIdle()
        val writes = draftStore.saveCount
        vm.bodyFieldState.edit { selection = TextRange(1, 3) }
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(writes, draftStore.saveCount)
        assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
    }

    @Test
    fun closeFlushesLiveBodyBeforeMirrorEmission() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository)
        vm.bodyFieldState.edit { append("last keystroke") }
        assertEquals("", vm.state.value.bodyText)
        vm.onCloseRequested()
        runCurrent()
        assertEquals("last keystroke", draftStore.saved[EditorDraftKey.mpCompose()]?.body)
    }

    @Test
    fun smileyDismissesOnlyAfterAcceptedInsertion() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val vm = viewModel(repository)
        vm.onRecipientsChanged("alice")
        vm.onSubjectChanged("subject")
        vm.editBody(TextFieldValue("body", TextRange(4)))
        vm.smileyPicker.open()
        vm.onSubmit()
        vm.onSmileySelected(":jap:")
        assertEquals("body", vm.bodyFieldState.text.toString())
        assertTrue(vm.smileyPicker.state.value is SmileyPickerState.Open)
        gate.complete(Unit)
        runCurrent()
        vm.onSmileySelected(":jap:")
        assertEquals("body :jap: ", vm.bodyFieldState.text.toString())
        assertEquals(SmileyPickerState.Hidden, vm.smileyPicker.state.value)
    }

    @Test
    fun uploadInsertionPreservesAcceptedImages() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val uploads = FakeUploadRepository().apply {
            uploadException = UploadException.TooLarge(1)
            failOnCall = 2
        }
        val vm = viewModel(repository, uploadRepository = uploads)
        vm.editBody(TextFieldValue("before after", TextRange(7)))
        vm.onImagesPicked(listOf("content://first", "content://second"))
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        val expected = "before [url=https://rehost.diberie.com/Picture/Get/f/1]" +
            "[img]https://rehost.diberie.com/Picture/Get/f/1[/img][/url]after"
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(expected.length - "after".length), vm.bodyFieldState.selection)
        assertEquals(expected, draftStore.saved[EditorDraftKey.mpCompose()]?.body)
        assertEquals(2, uploads.uploadCalls)
        assertEquals(EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(1, 2)), vm.state.value.uploadError)
    }

    @Test
    fun uncertainAttemptRestoresAllFieldsIntoStableBuffer() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val original = viewModel(repository)
        original.onRecipientsChanged("alice")
        original.onSubjectChanged("subject")
        original.editBody(TextFieldValue("uncertain body"))
        original.onSubmit()
        advanceUntilIdle()
        original.viewModelScope.cancel()
        val restored = viewModel(repository)
        val field = restored.bodyFieldState
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(field === restored.bodyFieldState)
        assertEquals("uncertain body", field.text.toString())
        assertEquals(TextRange(14), field.selection)
        assertEquals("subject", restored.state.value.subject)
        assertEquals("alice", restored.state.value.recipients)
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageCompose), restored.state.value.submitError)
        assertNull(restored.state.value.restorableDraft)
    }

    @Test
    fun previewDiscardsResultAfterEdit() = previewDiscardsStaleResult(hide = false)

    @Test
    fun previewDiscardsResultAfterHide() = previewDiscardsStaleResult(hide = true)

    private fun previewDiscardsStaleResult(hide: Boolean) = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val oldPreview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("old")))))
        val newPreview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("new")))))
        val parser = BbcodePreviewParser { text ->
            if (text == "old") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "preview barrier timed out" }
                oldPreview
            } else {
                newPreview
            }
        }
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchComposeForm(any()) } returns composeForm()
        val vm = viewModel(repository, previewParser = parser, defaultDispatcher = dispatcher)
        val published = mutableListOf<PostContent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { published += it.preview }
        }
        try {
            vm.editBody(TextFieldValue("old"))
            vm.onTogglePreview()
            runCurrent()
            assertTrue("synchronous parse must be held off Main", started.await(5, TimeUnit.SECONDS))
            if (hide) {
                vm.onTogglePreview()
            } else {
                // Intentionally omit notifications: the final live-buffer check must still reject old.
                vm.bodyFieldState.edit { replace(0, length, "new") }
            }
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            runCurrent()
            assertFalse("old parse must never publish", oldPreview in published)
            if (!hide) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                executor.submit {}.get(5, TimeUnit.SECONDS)
                runCurrent()
                assertEquals(newPreview, vm.state.value.preview)
                assertTrue(newPreview in published)
            } else {
                assertFalse(vm.state.value.isPreviewVisible)
            }
        } finally {
            release.countDown()
            vm.viewModelScope.cancel()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
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
            defaultDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
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
                viewModel.editBody(TextFieldValue(body, TextRange(body.length)), advanceMirror = false)
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
                    viewModel.bodyFieldState.text.toString(),
                    it.copy(bodyText = viewModel.bodyFieldState.text.toString()).canSubmit,
                    it.isSubmitting, it.isUploading, it.showSubmitConfirmation,
                    armId = it.armToken?.id,
                    error = when (it.submitError) {
                        EditorErrors.network -> SubmissionErrorProbe.NETWORK
                        EditorErrors.sessionExpired -> SubmissionErrorProbe.SESSION_EXPIRED
                        EditorErrors.uncertain(EditorErrorSurface.MessageCompose) -> SubmissionErrorProbe.UNCERTAIN
                        EditorErrors.draftWriteFailed -> SubmissionErrorProbe.DRAFT_WRITE_FAILED
                        EditorErrors.invalidForm -> SubmissionErrorProbe.PREPARATION_FAILED
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
