package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.ExperimentalFoundationApi
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
import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerContract
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
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
import java.io.IOException
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
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PrivateMessageReplyViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private var submissionClock = DiagnosticsClock { mainDispatcher.scheduler.currentTime * 1_000_000L }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    private val viewModels = mutableListOf<PrivateMessageReplyViewModel>()

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private val request = PrivateMessageReplyRequest(threadId = 3195237, page = 1)
    private val quote = PrivateMessageQuote(numreponse = 1_980_000_004, ref = 4)
    private val quoteRequest = PrivateMessageReplyRequest(
        threadId = 3_000_001,
        page = 1,
        quote = quote,
    )

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
    private var draftStore = VersionedEditorDraftStore()
    private val favoriteRepository = FakeHfrFavoriteSmileyRepository()
    private val localCollections = FakeSmileyLocalCollections()

    private fun userPreferences(confirmBeforePosting: Boolean = false): UserPreferencesRepository =
        mockk {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
            // #459 — the composer now mirrors the image-insert preference on init.
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
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
        initialContent: String = "",
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplyForm = ReplyForm(
        hashCheck = hashCheck,
        sujet = "Sujet prive de test",
        hiddenFields = hiddenFields,
        isAnonymous = isAnonymous,
        initialContent = initialContent,
        options = options,
    )

    /** #606 — owner DT/MultiMP form: HFR serves the `newdest` CSV (members minus the owner). */
    private fun ownerForm(
        newdest: String = "alice, bob, Bébé Yoda, stitch+, Administration",
    ): ReplyForm = form(
        hiddenFields = mapOf(
            "cat" to "prive",
            "post" to "4242424",
            "numrep" to "1990000111",
            "owntopic" to "1",
            "signature" to "1",
            "newdest" to newdest,
        ),
    )

    private fun trackViewModel(vm: PrivateMessageReplyViewModel) {
        viewModels += vm
        Snapshot.sendApplyNotifications()
        mainDispatcher.scheduler.runCurrent()
    }

    @Suppress("LongParameterList") // Mirrors the injected collaborators for focused race tests.
    private fun viewModel(
        repository: PrivateMessageWriteRepository,
        confirmBeforePosting: Boolean = false,
        uploadRepository: UploadRepository = FakeUploadRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
        previewParser: BbcodePreviewParser = this.previewParser,
        defaultDispatcher: CoroutineDispatcher = mainDispatcher,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PrivateMessageReplyViewModel = PrivateMessageReplyViewModel(
        request = request,
        repository = repository,
        previewParser = previewParser,
        defaultDispatcher = defaultDispatcher,
        userPreferencesRepository = userPreferences(confirmBeforePosting),
        draftStore = draftStore,
        authRepository = authRepository,
        uploadRepository = uploadRepository,
        imageUploadReader = FakeImageUploadReader(),
        diagnostics = DiagnosticsLog(),
        clock = submissionClock,
        smileyRepository = smileyRepository(),
        hfrFavoriteSmileyRepository = favoriteRepository,
        smileyLocalCollections = localCollections,
        savedStateHandle = savedStateHandle,
    ).also { viewModels += it }

    /** Synthetic JVM user input; real typing/paste is covered by PrivateMessageReplyInputTest. */
    private fun PrivateMessageReplyViewModel.editBody(value: TextFieldValue, advanceMirror: Boolean = true) {
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(), submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()
        vm.editBody(TextFieldValue("body"))
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } throws
            EditorPreparationException(EditorPreparationException.Reason.INVALID_FORM)
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(EditorErrors.invalidForm, vm.state.value.submitError)
        vm.retryFormLoad()
        advanceUntilIdle()
        assertNull(vm.state.value.submitError)
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        vm.onSubmit()
        advanceUntilIdle()
        val uncertain = vm.state.value.submitError
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageReply), uncertain)
        vm.retryFormLoad()
        advanceUntilIdle()
        assertEquals(uncertain, vm.state.value.submitError)
    }

    @Test
    fun `new upload clears the old failure only after passing the upload guard`() = runTest {
        val uploads = FakeUploadRepository().apply { uploadException = UploadException.TooLarge(1) }
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, FakeImageUploadReader(), DiagnosticsLog(), submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, FakeImageUploadReader(), DiagnosticsLog(), submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
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
    fun `ED131 reply errors survive typing selection recipients options and insertion`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val vm = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(), submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        vm.editBody(TextFieldValue("body"))
        vm.onSubmit()
        advanceUntilIdle()
        vm.onImagePickerEvent(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        )
        listOf<() -> Unit>(
            { vm.editBody(TextFieldValue("changed")) },
            { vm.editBody(TextFieldValue("changed", TextRange(1, 3))) },
            { vm.onAddRecipient("extra") },
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()

        viewModel.onImagesPicked(listOf("content://pick/1", "content://pick/2"))
        advanceUntilIdle()

        assertEquals(listOf("content://pick/1", "content://pick/2"), reader.readUris)
        assertEquals(2, uploads.uploadCalls)
        assertEquals(2, Regex("\\[img]").findAll(viewModel.bodyFieldState.text.toString()).count())
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `empty picker result shows a banner without starting an upload`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, diagnostics, submissionClock, smileyRepository(),
            favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()
        viewModel.editBody(
            TextFieldValue(text = "draft", selection = TextRange(1, 4)),
        )
        advanceUntilIdle()
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            viewModel.onImagePickerEvent(
                ImagePickerEvent.Result(
                    contract = ImagePickerContract.OPEN_MULTIPLE_DOCUMENTS,
                    uris = emptyList(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                stateBefore.copy(uploadError = EditorErrors.noImageReceived),
                viewModel.state.value,
            )
            assertFalse(viewModel.state.value.isUploading)
            assertTrue(reader.readUris.isEmpty())
            assertEquals(0, uploads.uploadCalls)
            assertEquals(
                listOf(
                    "result contract=OpenMultipleDocuments count=0 sources=[]",
                    "onImagesPicked count=0",
                ),
                diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `picker result logs a safe source then triggers the existing upload`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val diagnostics = DiagnosticsLog()
        val uploads = FakeUploadRepository()
        val reader = FakeImageUploadReader()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), uploads, reader, diagnostics, submissionClock, smileyRepository(),
            favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()
        val uri = "content://media/picker/private/photo-alice.jpg"

        viewModel.onImagePickerEvent(
            ImagePickerEvent.Result(
                contract = ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA,
                uris = listOf(uri),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(uri), reader.readUris)
        assertEquals(1, uploads.uploadCalls)
        assertNull(viewModel.state.value.uploadError)
        assertFalse(viewModel.state.value.isUploading)
        assertEquals(
            listOf(
                "result contract=PickMultipleVisualMedia count=1 sources=[content://media]",
                "onImagesPicked count=1",
            ),
            diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
        )
        assertFalse(diagnostics.entries.value.joinToString { it.message }.contains("photo-alice"))
    }

    @Test
    fun `loads the form on init and hydrates signature from the hidden field`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        val state = viewModel.state.value
        assertFalse(state.isLoadingForm)
        assertTrue(state.formAvailable)
        assertFalse(state.formError)
        // The quick-reply form carries signature as a hidden `=1`, so the toggle defaults on.
        assertTrue("signature default should be hydrated from the hidden field", state.signatureEnabled)
    }

    @Test
    fun `quote form hydrates verbatim and quote-only submit preserves options and newdest`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        val quoteForm = form(
            hiddenFields = mapOf(
                "cat" to "prive",
                "post" to "3000001",
                "numrep" to "1980000004",
                "numreponse" to "",
                "newdest" to "alice, bob",
            ),
            initialContent = prefill,
            options = ReplyFormOptions(signatureEnabled = true),
        )
        coEvery { repository.fetchReplyForm(any(), any()) } returns quoteForm
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        val state = viewModel.state.value
        assertEquals("the server prefill must stay byte-for-byte in the editor", prefill, state.bodyText)
        assertTrue("a quote with no added body is submit-ready", state.canSubmit)
        assertTrue("checkbox defaults still hydrate on a quote", state.signatureEnabled)
        assertEquals(listOf("alice", "bob"), state.recipients)

        viewModel.onSubmit()

        coVerify {
            repository.submitReply(
                context = match { it.quote == quote },
                form = quoteForm,
                bbcodeContent = prefill,
                options = ReplyFormOptions(signatureEnabled = true),
                // No member edit: the repository forwards the original `newdest` verbatim.
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `multi quote hydrates every server prefill and submits with the first locator`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val firstPrefill = "[quotemsg=303,2,1]Trois[/quotemsg]\n"
        val secondPrefill = "[quotemsg=101,9,1]Un[/quotemsg]\n"
        val multiQuoteRequest = PrivateMessageReplyRequest(
            threadId = 3_000_001,
            page = 6,
            initialQuotes = listOf(
                QuoteSelection(QuoteLocator(page = 8, numreponse = 303, ref = 2), "Carol", "Trois"),
                QuoteSelection(QuoteLocator(page = 3, numreponse = 101, ref = 9), "Alice", "Un"),
            ),
        )
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            val context = firstArg<PrivateMessageReplyContext>()
            val quote = requireNotNull(context.quote)
            form(
                hashCheck = "hash-${quote.numreponse}",
                hiddenFields = mapOf("numrep" to quote.numreponse.toString()),
                initialContent = if (quote.numreponse == 303) firstPrefill else secondPrefill,
            )
        }
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            multiQuoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        val expected = firstPrefill + "\n" + secondPrefill
        assertEquals(expected, viewModel.bodyFieldState.text.toString())
        viewModel.onSubmit()

        coVerify {
            repository.submitReply(
                context = match { submitContext ->
                    submitContext.page == 8 &&
                        submitContext.quote == PrivateMessageQuote(numreponse = 303, ref = 2)
                },
                form = match { submittedForm -> submittedForm.hashCheck == "hash-303" },
                bbcodeContent = expected,
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `late quote form prefixes the prefill once without clobbering typed text`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.editBody(TextFieldValue("Texte saisi pendant le GET"))
        formGate.complete(
            form(
                hiddenFields = mapOf("numrep" to "1980000004", "numreponse" to ""),
                initialContent = prefill,
            ),
        )
        advanceUntilIdle()

        val expected = prefill + "\nTexte saisi pendant le GET"
        assertEquals(expected, viewModel.bodyFieldState.text.toString())

        // A retry/re-subscription to the same server form refreshes the token only; it cannot append
        // a second quote block because draftHydratedFromForm is already true.
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertEquals(expected, viewModel.bodyFieldState.text.toString())
        assertEquals(1, Regex("\\[quotemsg=").findAll(viewModel.bodyFieldState.text.toString()).count())
    }

    @Test
    fun `stored draft is offered alongside an untouched quote prefill`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = prefill)
        draftStore.preload(
            EditorDraftKey.mpReply(quoteRequest.threadId),
            EditorDraftStore.Draft(body = "Ancien brouillon", isPrivate = true),
        )

        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()

        assertEquals(prefill, viewModel.bodyFieldState.text.toString())
        assertEquals("Ancien brouillon", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `invalid hash refetch stays on the quote context and never duplicates its prefill`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val prefill = "[quotemsg=1980000004,4,990001]Citation serveur[/quotemsg]\n"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = prefill)
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        val viewModel = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        assertEquals(prefill, viewModel.bodyFieldState.text.toString())
        coVerify(exactly = 2) {
            repository.fetchReplyForm(
                match { it.quote == quote },
                allowEmbeddedFallback = true,
            )
        }
    }

    @Test
    fun `picker invokes the injected HFR add and remove without reloading the form`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val wiki = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
        favoriteRepository.addResult = HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
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
        coVerify(exactly = 1) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `late reply form cannot authorize favorites after an account switch`() = runTest {
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val favorite = EditorSmiley("[:alice]", "https://example.invalid/a.gif", EditorSmileySource.HFR_FAVORITE)
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isLoadingForm)
        localCollections.snapshot.value = SmileyLocalCollectionSnapshot("bob")
        advanceUntilIdle()
        gate.complete(form().copy(favoriteSmileys = listOf(favorite)))
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
    fun `reply favorites insert the perso token and follow a reloaded form while open`() = runTest {
        val favorite = EditorSmiley(
            "[:fictif espace:3]", "https://example.invalid/fictif.gif", EditorSmileySource.HFR_FAVORITE,
        )
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(favoriteSmileys = listOf(favorite))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.smileyPicker.open()
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.onSmileySelected(open.favoriteSmileys.single().token)
        assertEquals("hello [:fictif espace:3] ", viewModel.bodyFieldState.text.toString())
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)

        viewModel.smileyPicker.open()
        val replacement = favorite.copy(token = "[:fictif_b]", imageUrl = "https://example.invalid/fictif_b.gif")
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(favoriteSmileys = listOf(replacement))
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertEquals(
            listOf(replacement),
            (viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys,
        )

        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        viewModel.retryFormLoad()
        advanceUntilIdle()
        assertTrue((viewModel.smileyPicker.state.value as SmileyPickerState.Open).favoriteSmileys.isEmpty())
    }

    @Test
    fun `the wiki search carries the loaded form's userId (#440)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form().copy(userId = 54596)
        val smileys = smileyRepository()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileys, favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.smileyPicker.open()
        viewModel.smileyPicker.onQueryChanged("jap")
        advanceUntilIdle()

        coVerify(exactly = 1) { smileys.searchWiki(54596, "jap") }
    }

    @Test
    fun `submit success raises SubmitSucceeded for the conversation`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))

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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("non-blank"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(EditorErrors.empty, state.submitError)
        assertEquals("non-blank", state.bodyText)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `unknown recipient in a reply is a generic certain refusal without a recipient field instruction`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.UnknownRecipient)
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Private reply"))
        viewModel.effects.test {
            viewModel.onSubmit()
            runCurrent()
            expectNoEvents()
        }

        val state = viewModel.state.value
        assertEquals(EditorErrors.refused, state.submitError)
        assertEquals(
            fr.forumhfr.redface2.core.ui.R.string.messages_reply_error_refused, state.submitError?.detail?.resourceId,
        )
        assertEquals("Private reply", state.bodyText)
        assertNull(draftStore.entries.values.single().attempt)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `invalid hash check refetches the form silently`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        assertEquals(EditorErrors.invalidHashCheck, viewModel.state.value.submitError)
        // init fetch + the silent refetch after the expired hash_check.
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `invalid hash check refetch preserves the user option toggles`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        // First load hydrates signature ON (hidden `signature=1`); the user turns it OFF.
        viewModel.onToggleSignature(false)
        viewModel.editBody(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        // The silent refetch after the expired hash_check must NOT re-hydrate the toggle back to
        // HFR's default — the user's choice survives into the second submit.
        assertFalse(
            "InvalidHashCheck refetch must preserve the user's signature toggle",
            viewModel.state.value.signatureEnabled,
        )
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `unknown response maps to uncertainty with conversation verification`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("hello"))
        viewModel.onSubmit()

        val state = viewModel.state.value
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageReply), state.submitError)
        // Non-destructive: the draft survives so the user can verify and retry.
        assertEquals("hello", state.bodyText)
    }

    @Test
    fun `form fetch failure shows the retry state`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } throws IOException("network down")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        val state = viewModel.state.value
        assertTrue(state.formError)
        assertFalse(state.formAvailable)
        assertFalse(state.isLoadingForm)
    }

    @Test
    fun `anonymous form is treated as a form error`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(isAnonymous = true)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON parks the submit behind the confirmation dialog`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON onSubmitConfirmed executes the real submission without re-confirming`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }

        viewModel.effects.test {
            val armToken = viewModel.state.value.armToken
            advanceTimeBy(400) // ED-166: a deliberate confirmation.
            viewModel.onSubmitConfirmed(armToken)
            assertEquals(
                PrivateMessageReplyEffect.SubmitSucceeded(threadId = 3195237, page = 1),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        // Confirm bypasses the preference re-check : exactly one POST, no dialog re-arm.
        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
    }

    @Test
    fun `confirm-before-posting ON dismissing the dialog sends nothing and keeps the draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()

        viewModel.onSubmitConfirmationDismissed()

        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertEquals("the draft survives the dismissal", "Coucou en privé.", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.submitError)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON never confirms an empty draft`() = runTest {
        // The confirmation slots in AFTER validation : a blank draft fails `canSubmit`, so no
        // dialog may appear (confirming an unsendable form would be a lie).
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.onSubmit()

        assertFalse("invalid form must not raise the dialog", viewModel.state.value.showSubmitConfirmation)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON re-submitting while the dialog is up keeps it armed without posting`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        // Second tap while the dialog is up (double-tap race) : idempotent — re-raising
        // `showSubmitConfirmation = true` is a no-op, and no POST may slip through.
        viewModel.onSubmit()

        assertTrue("the dialog must stay armed", viewModel.state.value.showSubmitConfirmation)
        assertFalse("nothing is in flight while the dialog is up", viewModel.state.value.isSubmitting)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirm-before-posting ON rapid double onSubmitConfirmed posts exactly once`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        // Hold the first confirmed submit in flight : with UnconfinedTestDispatcher a
        // non-suspending mock would complete synchronously and the second confirm would
        // legitimately re-fire. The gate keeps the same submission permit active across both confirms.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }

        val viewModel = PrivateMessageReplyViewModel(
            request,
            repository,
            previewParser,
            mainDispatcher,
            userPreferences(confirmBeforePosting = true),
            draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))
        viewModel.onSubmit()
        assertTrue("the confirmation dialog must be armed", viewModel.state.value.showSubmitConfirmation)

        val armToken = viewModel.state.value.armToken
        advanceTimeBy(400) // ED-166: a deliberate confirmation.

        viewModel.onSubmitConfirmed(armToken) // launches the POST ; suspends on gate
        viewModel.onSubmitConfirmed(armToken) // must be a no-op (same permit / terminal guards)

        gate.complete(Unit)

        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.showSubmitConfirmation)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    // ----- #405 : draft autosave / restore -----------------------------------

    @Test
    fun `autosave persists the private body under the mpReply key after the debounce`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.editBody(TextFieldValue("private draft"))
        advanceTimeBy(800L)

        val key = EditorDraftKey.mpReply(request.threadId)
        assertEquals("private draft", draftStore.saved[key]?.body)
        assertTrue("MP drafts must be flagged private for the logout purge", draftStore.saved[key]?.isPrivate == true)
    }

    @Test
    fun `a stored MP draft is surfaced as restorable on init`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        assertEquals("rescued MP", viewModel.state.value.restorableDraft)
        assertEquals("draft is not auto-applied", "", viewModel.bodyFieldState.text.toString())

        viewModel.onDraftRestoreRequested()
        assertEquals("rescued MP", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `reply recreation reoffers until decision and typing still autosaves`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpReply(request.threadId)
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            key,
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val first = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertEquals("rescued MP", first.state.value.restorableDraft)
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))

        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertEquals("rescued MP", recreated.state.value.restorableDraft)

        recreated.editBody(TextFieldValue("new live MP"))
        advanceTimeBy(800L)
        assertEquals("new live MP", draftStore.saved[key]?.body)
        assertEquals("tester", draftStore.lastSavedOwner)

        recreated.onDraftRestoreRequested()
        advanceUntilIdle()
        assertNull(savedStateHandle.get<String>("draft_restore_offer_fingerprint"))
        assertEquals("new live MP\n\nrescued MP", draftStore.saved[key]?.body)

        val afterRestore = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertEquals("new live MP\n\nrescued MP", afterRestore.state.value.restorableDraft)
    }

    @Test
    fun `reply process recreation offers a newer autosaved draft after live state was lost`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        val key = EditorDraftKey.mpReply(request.threadId)
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(key, EditorDraftStore.Draft(body = "first MP", isPrivate = true))
        val first = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertEquals("first MP", first.state.value.restorableDraft)

        draftStore.preload(key, EditorDraftStore.Draft(body = "new MP", isPrivate = true))
        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)

        assertEquals("new MP", recreated.state.value.restorableDraft)
    }

    @Test
    fun `private reply hydration removes an identical cached draft offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val body = "server body"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = body)
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = body, isPrivate = true),
        )

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        assertEquals(body, viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `restoring appends the offered private reply after live text`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "rescued MP", isPrivate = true),
        )
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.editBody(TextFieldValue("fresh MP"))
        viewModel.onDraftRestoreRequested()

        assertEquals("fresh MP\n\nrescued MP", viewModel.bodyFieldState.text.toString())
        assertNull(viewModel.state.value.restorableDraft)
    }

    @Test
    fun `moving selection in a hydrated private reply keeps the restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val key = EditorDraftKey.mpReply(request.threadId)
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = "server body")
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued MP", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        val hydrated = TextFieldValue(viewModel.bodyFieldState.text.toString(), viewModel.bodyFieldState.selection)

        viewModel.editBody(hydrated.copy(selection = TextRange(3)))
        advanceTimeBy(800L)

        assertEquals("rescued MP", viewModel.state.value.restorableDraft)
        assertEquals("rescued MP", draftStore.saved[key]?.body)
    }

    @Test
    fun `discarding deletes the cached MP draft`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val savedStateHandle = SavedStateHandle()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "rescued MP", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)

        viewModel.onDraftDiscardRequested()
        advanceUntilIdle()

        assertEquals("", draftStore.saved[key]?.body)
        assertNull(viewModel.state.value.restorableDraft)
        assertTrue(savedStateHandle.get<String>("draft_restore_offer_fingerprint") != null)

        val recreated = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertNull(recreated.state.value.restorableDraft)
    }

    @Test
    fun `a successful MP reply clears content and retains the published marker`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("Coucou en privé."))

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
        assertEquals(
            EditorDraftStore.AttemptStatus.PUBLISHED,
            draftStore.entries[EditorDraftKey.mpReply(request.threadId)]?.attempt?.status,
        )
    }

    // ----- #803 pattern : dirty close (flush before pop), state-hygiene audit 2026-07-05 -----

    @Test
    fun `onCloseRequested flushes the pending debounce before CloseCommitted`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        // Type, then close IMMEDIATELY — well inside the 750 ms debounce window. The flush must
        // persist the state at close time, not the snapshot the debounce captured at scheduling.
        viewModel.editBody(TextFieldValue("dernier mot"))
        viewModel.onCloseRequested()

        val effect = viewModel.effects.first()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, effect)
        val saved = draftStore.saved[EditorDraftKey.mpReply(request.threadId)]
        assertEquals("the tail of the draft must reach the row before the pop", "dernier mot", saved?.body)
        assertTrue("MP drafts must stay flagged private", saved?.isPrivate == true)
    }

    @Test
    fun `onCloseRequested with a blank live body preserves a pending restore offer`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "stale", isPrivate = true))
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onCloseRequested()

        val effect = viewModel.effects.first()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, effect)
        assertNull("closing must not rewrite a pending offer", draftStore.lastSavedOwner)
        assertFalse(draftStore.deletedKeys.contains(key))
        assertEquals("stale", draftStore.saved[key]?.body)
        assertEquals("stale", viewModel.state.value.restorableDraft)
    }

    @Test
    fun `onCloseRequested with a blank body and no pending offer deletes the stale row`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        val key = EditorDraftKey.mpReply(request.threadId)

        viewModel.editBody(TextFieldValue("temporary body"))
        advanceTimeBy(800L)
        viewModel.editBody(TextFieldValue(""))
        viewModel.onCloseRequested()

        assertEquals(PrivateMessageReplyEffect.CloseCommitted, viewModel.effects.first())
        assertEquals("", draftStore.saved[key]?.body)
    }

    @Test
    fun `onCloseRequested during an in-flight submit is ignored (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        // Hold the POST in flight so the close lands while isSubmitting is true.
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        }
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("en vol"))

        viewModel.onSubmit()
        // Close requested while the POST is in flight — must be inert (gate #803).
        viewModel.onCloseRequested()
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.effects.test {
            assertTrue(
                "the submit outcome must be the ONLY effect — no CloseCommitted",
                awaitItem() is PrivateMessageReplyEffect.SubmitSucceeded,
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a second onCloseRequested is a no-op (gate #803)`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onCloseRequested()
        viewModel.onCloseRequested()
        advanceUntilIdle()

        viewModel.effects.test {
            assertEquals(PrivateMessageReplyEffect.CloseCommitted, awaitItem())
            // A double close must never yield a second pop (it would remove the screen BELOW).
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- #606 : owner manages DT/MultiMP members --------------------------------

    @Test
    fun `a non-owner form exposes no member editor and never overrides newdest`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form() // no newdest
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        assertFalse(viewModel.state.value.canManageRecipients)
        assertTrue(viewModel.state.value.recipients.isEmpty())

        // Even if add/remove are called on a participant form, they no-op.
        viewModel.onAddRecipient("intruder")
        viewModel.onRemoveRecipient("anyone")
        assertTrue(viewModel.state.value.recipients.isEmpty())

        viewModel.editBody(TextFieldValue("hello"))
        viewModel.onSubmit()
        // A participant never overrides the member list — recipientsOverride stays null.
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `an owner form hydrates the members CSV preserving order, accents, plus and spaces`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm()

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        assertTrue(viewModel.state.value.canManageRecipients)
        assertEquals(
            listOf("alice", "bob", "Bébé Yoda", "stitch+", "Administration"),
            viewModel.state.value.recipients,
        )
    }

    @Test
    fun `owner submit without edits reposts the member CSV verbatim`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("hello"))

        viewModel.onSubmit()
        // No edit was made, so the VM passes `recipientsOverride = null`: the repository then
        // forwards HFR's original `newdest` hidden field VERBATIM (covered by the repo test), never
        // round-tripping the list through parse → join. This is the loss-less invariant for a normal
        // owner reply — the member list must not change just because the owner replied.
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = null,
            )
        }
    }

    @Test
    fun `owner adds a member appended at the end`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onAddRecipient("  charlie  ") // trimmed before insertion
        assertEquals(listOf("alice", "bob", "charlie"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner add refuses an exact-trimmed duplicate`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onAddRecipient(" alice ")
        assertEquals("no duplicate appended", listOf("alice", "bob"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner remove is exact-match so bob does not delete bob2`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob, bob2")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onRemoveRecipient("bob")
        assertEquals(listOf("alice", "bob2"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner can remove down to one but never the last member`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)

        viewModel.onRemoveRecipient("alice")
        assertEquals("down to one is allowed", listOf("bob"), viewModel.state.value.recipients)

        viewModel.onRemoveRecipient("bob")
        assertEquals("the last member can't be removed", listOf("bob"), viewModel.state.value.recipients)
    }

    @Test
    fun `owner edits travel to the repository as the recomposed CSV`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.onRemoveRecipient("alice")
        viewModel.onAddRecipient("Bébé Yoda")
        viewModel.editBody(TextFieldValue("hello"))

        viewModel.onSubmit()
        coVerify {
            repository.submitReply(
                context = any(),
                form = any(),
                bbcodeContent = any(),
                options = any(),
                recipientsOverride = "bob, Bébé Yoda",
            )
        }
    }

    @Test
    fun `the member list survives a silent hash-check refetch`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.onRemoveRecipient("alice") // user edit before the failed submit
        viewModel.editBody(TextFieldValue("hello"))
        viewModel.onSubmit()
        runCurrent() // Wait for the form refresh after the certain failure.

        // The silent refetch after the expired hash_check must NOT re-hydrate the member list back
        // to HFR's prefill — the user's removal survives.
        assertEquals(listOf("bob"), viewModel.state.value.recipients)
        coVerify(exactly = 2) { repository.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `an unedited list re-hydrates from a fresher form on a silent refetch`() = runTest {
        val outcomeGate = CompletableDeferred<Unit>()
        draftStore.finishGate = outcomeGate
        val repository = mockk<PrivateMessageWriteRepository>()
        // 1st load: alice, bob. The silent refetch after InvalidHashCheck returns a fresher form
        // where a member changed elsewhere (charlie added). With no local edit (recipientsDirty
        // false), the VM must pick up the fresher list rather than keep the stale prefill.
        coEvery { repository.fetchReplyForm(any(), any()) } returnsMany listOf(
            ownerForm(newdest = "alice, bob"),
            ownerForm(newdest = "alice, bob, charlie"),
        )
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

        val viewModel = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        viewModel.editBody(TextFieldValue("hello")) // no member edit
        viewModel.onSubmit()
        runCurrent()
        // The GET must not adopt a new form while the durable outcome still holds the body lock.
        coVerify(exactly = 1) { repository.fetchReplyForm(any(), any()) }
        assertEquals(listOf("alice", "bob"), viewModel.state.value.recipients)
        outcomeGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("alice", "bob", "charlie"), viewModel.state.value.recipients)
    }

    // ----- #606 : RecipientCsv codec ----------------------------------------------

    @Test
    fun `RecipientCsv parse trims each element, drops empties and preserves order`() {
        assertEquals(
            listOf("alice", "bob", "Bébé Yoda", "stitch+"),
            RecipientCsv.parse(" alice ,bob,  Bébé Yoda , stitch+ , "),
        )
        assertTrue(RecipientCsv.parse(null).isEmpty())
        assertTrue(RecipientCsv.parse("").isEmpty())
    }

    @Test
    fun `RecipientCsv join uses HFR's comma-space separator`() {
        assertEquals("alice, bob, stitch+", RecipientCsv.join(listOf("alice", "bob", "stitch+")))
    }

    private fun replyWithRestoreOffer(seed: String = "seed", draft: String = "draft"): PrivateMessageReplyViewModel {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = seed)
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = draft, isPrivate = true),
        )
        return viewModel(repository)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun assertRestoreAndSingleUndo(vm: PrivateMessageReplyViewModel, before: TextFieldValue, expected: String) {
        vm.editBody(before)
        val seed = vm.state.value.draftHydratedContent
        vm.onDraftRestoreRequested()
        assertEquals(expected, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(expected.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals(before.text, vm.bodyFieldState.text.toString())
        assertEquals(before.selection, vm.bodyFieldState.selection)
    }

    @Test
    fun restoreReplacesBlankField() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue(" \n", TextRange(1)), "draft")
    }

    @Test
    fun restoreReplacesUntouchedSeed() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seed", TextRange(1, 3)), "draft")
    }

    @Test
    fun restoreKeepsTextTypedAfterSeed() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(), TextFieldValue("seed \n\n suffix  \n", TextRange(2, 6)), "draft\n\nsuffix  \n",
        )
    }

    @Test
    fun restoreAppendsAfterTypedTextWhenSeedEmpty() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(seed = ""),
            TextFieldValue("typed", TextRange(1, 3)),
            "typed\n\ndraft",
        )
    }

    @Test
    fun restoreAppendsWhenSeedEditedInside() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seXd", TextRange(2)), "seXd\n\ndraft")
    }

    @Test
    fun restoreTrimsTrailingWhitespaceBeforeSeparator() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(seed = ""), TextFieldValue("typed \n\n", TextRange(2)), "typed\n\ndraft",
        )
    }

    @Test
    fun restoreTreatsBlankSuffixAsUntouchedSeed() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seed \n\t", TextRange(2, 5)), "draft")
    }

    @Test
    fun restoreIdenticalLiveBodyDoesNotDuplicateIt() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("draft", TextRange(1, 3)), "draft")
    }

    @Test
    fun restorePreservesDraftWhitespaceVerbatim() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(draft = "  draft  \n"), TextFieldValue("seed", TextRange(1, 3)), "  draft  \n",
        )
    }

    @Test
    fun editReopenRestoreYieldsSingleCopy() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = "seed")
        val original = viewModel(repository)
        original.editBody(TextFieldValue("saved revision"))
        original.onCloseRequested()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, original.effects.first())
        original.viewModelScope.cancel()
        val reopened = viewModel(repository)
        assertEquals("saved revision", reopened.state.value.restorableDraft)
        assertRestoreAndSingleUndo(reopened, TextFieldValue("seed", TextRange(1, 3)), "saved revision")
    }

    @Test
    fun seedStaysStableAfterRefetchAndRestore() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returnsMany listOf(
            form(initialContent = "seed"), form(initialContent = "different seed"),
        )
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true),
        )
        val vm = viewModel(repository)
        vm.retryFormLoad()
        runCurrent()
        assertEquals("seed", vm.state.value.draftHydratedContent)
        assertRestoreAndSingleUndo(vm, TextFieldValue("seed", TextRange(2)), "draft")
        assertEquals("seed", vm.state.value.draftHydratedContent)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun lateHydrationKeepsExactSuffixAndExistingUndoHistory() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        val typed = "  typed while loading  \n"
        vm.editBody(TextFieldValue(typed, TextRange(2, 6)))
        gate.complete(form(initialContent = "seed\n"))
        runCurrent()
        assertEquals("seed\n\n" + typed, vm.bodyFieldState.text.toString())
        assertEquals(TextRange("seed\n\n".length + typed.length), vm.bodyFieldState.selection)
        assertEquals("seed\n", vm.state.value.draftHydratedContent)
        vm.bodyFieldState.undoState.undo()
        assertEquals(typed, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(2, 6), vm.bodyFieldState.selection)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun selectionAloneBeforeHydrationDoesNotMarkBodyEdited() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("", TextRange.Zero))
        gate.complete(form(initialContent = "seed"))
        runCurrent()
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertFalse(vm.bodyFieldState.undoState.canUndo)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun deletedTypingBeforeHydrationKeepsUndoHistory() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("typed"))
        vm.editBody(TextFieldValue(""))
        gate.complete(form(initialContent = "seed"))
        runCurrent()
        vm.bodyFieldState.undoState.undo()
        assertEquals("", vm.bodyFieldState.text.toString())
        vm.bodyFieldState.undoState.undo()
        assertEquals("typed", vm.bodyFieldState.text.toString())
    }

    @Test
    fun restoreBeforeFormConsumesHydrationWithoutChangingSeed() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true),
        )
        val vm = viewModel(repository)
        assertRestoreAndSingleUndo(vm, TextFieldValue("typed", TextRange(1, 3)), "typed\n\ndraft")
        gate.complete(form(initialContent = "late quote"))
        runCurrent()
        assertTrue(vm.state.value.draftHydratedFromForm)
        assertEquals("", vm.state.value.draftHydratedContent)
        assertEquals("typed", vm.bodyFieldState.text.toString())
    }

    @Test
    fun untouchedSeedDoesNotOverwriteOfferedDraftAtAutosave() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        draftStore.preload(key, EditorDraftStore.Draft(body = "draft", isPrivate = true))
        val vm = viewModel(repository)
        // Keep a real debounce pending when automatic quote hydration lands.
        vm.editBody(TextFieldValue("temporary"))
        vm.editBody(TextFieldValue(""))
        gate.complete(form(initialContent = "seed"))
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("draft", draftStore.saved[key]?.body)
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    fun untouchedSeedDoesNotOverwriteOfferedDraftOnImmediateClose() = runTest {
        val vm = replyWithRestoreOffer()
        vm.onCloseRequested()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, vm.effects.first())
        assertEquals("draft", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
        assertEquals(0, draftStore.saveCount)
    }

    @Test
    fun hydrationDuringUploadAllowsOnlyProgrammaticBodyInsertion() = runTest {
        val formGate = CompletableDeferred<ReplyForm>()
        val uploadGate = CompletableDeferred<Unit>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        val uploads = FakeUploadRepository().apply { this.uploadGate = uploadGate }
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true),
        )
        val vm = viewModel(repository, uploadRepository = uploads)
        vm.onImagesPicked(listOf("content://pick/1"))
        assertTrue(vm.state.value.isUploading)
        formGate.complete(ownerForm(newdest = "alice, bob").copy(initialContent = "seed"))
        runCurrent()
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertEquals("seed", vm.state.value.draftHydratedContent)
        vm.editBody(TextFieldValue("rejected"))
        vm.onToolbarAction(BbcodeAction.Bold)
        vm.smileyPicker.open()
        vm.onSmileySelected(":jap:")
        vm.onDraftRestoreRequested()
        assertEquals("seed", vm.bodyFieldState.text.toString())
        assertEquals("draft", vm.state.value.restorableDraft)
        assertTrue(vm.smileyPicker.state.value is SmileyPickerState.Open)
        vm.onToggleSignature(false)
        vm.onToggleSmileyDisabled(true)
        vm.onToggleEmailNotification(true)
        vm.onAddRecipient("charlie")
        vm.onRemoveRecipient("bob")
        vm.onTogglePreview()
        assertFalse(vm.state.value.signatureEnabled)
        assertTrue(vm.state.value.smileyDisabled)
        assertTrue(vm.state.value.emailNotificationEnabled)
        assertEquals(listOf("alice", "charlie"), vm.state.value.recipients)
        assertTrue(vm.state.value.isPreviewVisible)
        vm.onDraftDiscardRequested()
        assertNull(vm.state.value.restorableDraft)
        uploadGate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isUploading)
        assertEquals(
            "seed[url=https://rehost.diberie.com/Picture/Get/f/1]" +
                "[img]https://rehost.diberie.com/Picture/Get/f/1[/img][/url]",
            vm.bodyFieldState.text.toString(),
        )
    }

    @Test
    fun draftRestoreWaitsUntilUploadCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val uploads = FakeUploadRepository().apply { uploadGate = gate }
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = "seed")
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true),
        )
        val vm = viewModel(repository, uploadRepository = uploads)
        vm.onImagesPicked(listOf("content://pick/1"))
        assertTrue(vm.state.value.isUploading)
        vm.onDraftRestoreRequested()
        assertEquals("draft", vm.state.value.restorableDraft)
        assertEquals("seed", vm.bodyFieldState.text.toString())
        gate.complete(Unit)
        runCurrent()
        val uploaded = vm.bodyFieldState.text.toString()
        assertTrue(uploaded.startsWith("seed[url="))
        assertRestoreAndSingleUndo(
            vm, TextFieldValue(uploaded, TextRange(1, 3)), "draft\n\n" + uploaded.removePrefix("seed"),
        )
    }

    @Test
    fun accountSwitchRejectsLateHydrationAndUploadWithoutReassigningSeed() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val authRepository = object : AuthRepository by FakeAuthRepository() {
            override fun observeAuthState(): Flow<AuthState> = auth
        }
        val gate = CompletableDeferred<ReplyForm>()
        var fetches = 0
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            if (fetches++ == 0) form(initialContent = "alice seed") else gate.await()
        }
        val uploads = FakeUploadRepository().apply { uploadGate = CompletableDeferred() }
        val vm = viewModel(repository, uploadRepository = uploads, authRepository = authRepository)
        vm.editBody(TextFieldValue("alice seed typed"))
        vm.retryFormLoad()
        vm.onImagesPicked(listOf("content://pick/1"))
        draftStore.activeOwner = "bob"
        auth.value = AuthState.Authenticated("bob")
        gate.complete(form(initialContent = "bob seed"))
        uploads.uploadGate?.complete(Unit)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        vm.onDraftRestoreRequested()
        vm.onCloseRequested()
        runCurrent()
        assertEquals("alice seed typed", vm.bodyFieldState.text.toString())
        assertEquals("alice seed", vm.state.value.draftHydratedContent)
        assertFalse(vm.state.value.isUploading)
        vm.editBody(TextFieldValue("bob body"))
        assertEquals("alice seed typed", vm.bodyFieldState.text.toString())
        assertTrue(draftStore.saved.isEmpty())
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun submitUsesLiveBodyBeforeMirrorEmission() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository)
        vm.bodyFieldState.edit { append("live body") }
        assertEquals("", vm.state.value.bodyText)
        assertFalse(vm.state.value.canSubmit)

        vm.onSubmit()
        runCurrent()

        coVerify(exactly = 1) { repository.submitReply(any(), any(), "live body", any(), any()) }
        assertEquals("live body", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
    }

    @Test
    fun submitRejectsBlankLiveBodyWhenMirrorStillAllowsIt() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("body"))
        assertTrue(vm.state.value.canSubmit)
        vm.bodyFieldState.edit { replace(0, length, "") }
        assertEquals("body", vm.state.value.bodyText)
        vm.onSubmit()
        runCurrent()
        assertEquals(0, draftStore.beginCalls)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun submitFreezesBodyOptionsAndRecipients() = runTest {
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm(newdest = "alice, bob")
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository)
        vm.onToggleSignature(false)
        vm.onToggleSmileyDisabled(true)
        vm.onToggleEmailNotification(true)
        vm.bodyFieldState.edit { append("frozen body") }
        assertEquals("", vm.state.value.bodyText)
        vm.onSubmit()
        vm.onAddRecipient("mallory")
        vm.onRemoveRecipient("bob")
        vm.editBody(TextFieldValue("late input"))
        vm.onToggleSignature(true)
        vm.onToggleSmileyDisabled(false)
        vm.onToggleEmailNotification(false)
        gate.complete(Unit)
        runCurrent()

        val saved = draftStore.saved.getValue(EditorDraftKey.mpReply(request.threadId))
        assertEquals("frozen body", saved.body)
        assertEquals(listOf("alice", "bob"), vm.state.value.recipients)
        assertFalse(vm.state.value.recipientsDirty)
        assertTrue(saved.isPrivate)
        coVerify(exactly = 1) {
            repository.submitReply(any(), any(), "frozen body", match {
                !it.signatureEnabled && it.smileyDisabled && it.emailNotificationEnabled
            }, null)
        }
        assertEquals("frozen body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun armingAndConfirmingValidateTheLiveBody() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val vm = viewModel(repository, confirmBeforePosting = true)
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
        coVerify(exactly = 1) { repository.submitReply(any(), any(), "live body", any(), any()) }
    }

    @Test
    fun confirmationRejectsBlankLiveBodyBeforeMirrorEmission() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = viewModel(repository, confirmBeforePosting = true)
        vm.editBody(TextFieldValue("body"))
        vm.onSubmit()
        val token = requireNotNull(vm.state.value.armToken)
        advanceTimeBy(500)
        vm.bodyFieldState.edit { replace(0, length, "") }
        assertEquals("body", vm.state.value.bodyText)
        vm.onSubmitConfirmed(token)
        runCurrent()
        assertEquals(0, draftStore.beginCalls)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun selectionDisarmsWithoutAutosave() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = viewModel(repository, confirmBeforePosting = true)
        vm.editBody(TextFieldValue("body", TextRange(4)))
        advanceTimeBy(800)
        runCurrent()
        val writes = draftStore.saveCount
        vm.onSubmit()
        assertTrue(vm.state.value.armToken != null)
        vm.editBody(TextFieldValue("body", TextRange(1, 3)))
        assertNull(vm.state.value.armToken)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(writes, draftStore.saveCount)
        assertEquals("body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun restoreOfferUsesLiveBody() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        draftStore.preload(EditorDraftKey.mpReply(request.threadId), EditorDraftStore.Draft(
            body = "rescued body", isPrivate = true,
        ))
        val vm = viewModel(repository)
        assertEquals("rescued body", vm.state.value.restorableDraft)
        vm.bodyFieldState.edit { append("rescued body") }
        assertEquals("", vm.state.value.bodyText)
        formGate.complete(form())
        runCurrent()
        assertNull(vm.state.value.restorableDraft)
        assertEquals("rescued body", vm.bodyFieldState.text.toString())
    }

    @Test
    fun draftOfferAndRequestedRestoreReadBodyBeforeSnapshotNotification() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "stored", isPrivate = true),
        )
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
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
        assertEquals("latest", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
    }

    @Test
    fun selectionChangeDoesNotAutosave() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = viewModel(repository)
        vm.bodyFieldState.edit { append("last keystroke") }
        assertEquals("", vm.state.value.bodyText)
        vm.onCloseRequested()
        runCurrent()
        assertEquals("last keystroke", draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
    }

    @Test
    fun smileyDismissesOnlyAfterAcceptedInsertion() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val gate = CompletableDeferred<Unit>()
        draftStore.beginGate = gate
        val vm = viewModel(repository)
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
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
        assertEquals(expected, draftStore.saved[EditorDraftKey.mpReply(request.threadId)]?.body)
        assertEquals(2, uploads.uploadCalls)
        assertEquals(EditorErrors.stopped(EditorErrors.tooLarge, UploadProgress(1, 2)), vm.state.value.uploadError)
    }

    @Test
    fun uncertainAttemptRestoresBodyIntoStableBuffer() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        val original = viewModel(repository)
        original.editBody(TextFieldValue("uncertain body"))
        original.onSubmit()
        advanceUntilIdle()
        original.viewModelScope.cancel()
        val writesBeforeRestore = draftStore.saveCount
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = "ignored quote")
        val restored = viewModel(repository)
        val field = restored.bodyFieldState
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(field === restored.bodyFieldState)
        assertEquals("uncertain body", field.text.toString())
        assertEquals(TextRange(14), field.selection)
        assertEquals(EditorErrors.uncertain(EditorErrorSurface.MessageReply), restored.state.value.submitError)
        assertNull(restored.state.value.restorableDraft)
        assertEquals("", restored.state.value.draftHydratedContent)
        advanceTimeBy(800)
        runCurrent()
        assertEquals("automatic hydration must not save an identical draft", writesBeforeRestore, draftStore.saveCount)
    }

    @Test
    fun previewIsClearedWhenHiddenAndBeforeReopening() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val oldPreview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("old")))))
        val newPreview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("new")))))
        val vm = viewModel(
            repository,
            previewParser = BbcodePreviewParser { if (it == "old") oldPreview else newPreview },
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.editBody(TextFieldValue("old"))
        vm.onTogglePreview()
        runCurrent()
        assertEquals(oldPreview, vm.state.value.preview)

        vm.onTogglePreview()
        assertFalse(vm.state.value.isPreviewVisible)
        assertEquals(PostContent(emptyList()), vm.state.value.preview)
        vm.editBody(TextFieldValue("new"))
        vm.onTogglePreview()
        assertTrue(vm.state.value.isPreviewVisible)
        assertEquals(PostContent(emptyList()), vm.state.value.preview)
        runCurrent()
        assertEquals(newPreview, vm.state.value.preview)
    }

    @Test
    fun previewIsRestoredWhenReopenedWithoutEditingBody() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val parsedPreview = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("body")))))
        val vm = viewModel(
            repository,
            previewParser = BbcodePreviewParser { parsedPreview },
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.editBody(TextFieldValue("body"))
        vm.onTogglePreview()
        runCurrent()
        val publishedPreview = vm.state.value.preview
        assertEquals(parsedPreview, publishedPreview)

        vm.onTogglePreview()
        assertFalse(vm.state.value.isPreviewVisible)
        assertEquals(PostContent(emptyList()), vm.state.value.preview)
        vm.onTogglePreview()
        assertTrue(vm.state.value.isPreviewVisible)
        runCurrent()
        assertEquals(publishedPreview, vm.state.value.preview)
        assertTrue(vm.state.value.preview.blocks.isNotEmpty())
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
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
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
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "stored draft"),
        )
        var formFetches = 0
        var submissionCalls = 0
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            formFetches++
            form()
        }
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
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
        fun createViewModel() = PrivateMessageReplyViewModel(
            request = request,
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
            smileyRepository = smileyRepository(),
            hfrFavoriteSmileyRepository = favoriteRepository,
            smileyLocalCollections = localCollections,
        )
        var viewModel = createViewModel()
        val effects = mutableListOf<PrivateMessageReplyEffect>()
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
            override val successEffects get() = effects.count { it is PrivateMessageReplyEffect.SubmitSucceeded }
            override val closeEffects get() = effects.count { it is PrivateMessageReplyEffect.CloseCommitted }

            override fun editBody(body: String) {
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
                val insert = PrivateMessageReplyViewModel::class.java.getDeclaredMethod(
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
                        EditorErrors.uncertain(EditorErrorSurface.MessageReply) -> SubmissionErrorProbe.UNCERTAIN
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
