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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
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
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
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
import fr.forumhfr.redface2.core.ui.editor.EditorEventAction
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageReplyViewModelTest {

    private val shortcutPreference = MutableStateFlow(EditorShortcut.NONE)
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

    private suspend fun enqueueQuoteLaunch(source: PrivateMessageReplyRequest) {
        val quotes = source.quote?.let { listOf(PendingInlineQuoteRequest.Quote(it.numreponse, source.page, it.ref)) }
            ?: source.initialQuotes.map { PendingInlineQuoteRequest.Quote(it.numreponse, it.locator.page,
                requireNotNull(it.locator.ref), it.truncate) }
        val pending = PendingInlineQuoteRequest.privateMessage(source.threadId, quotes)
        val drafts = fr.forumhfr.redface2.core.domain.editor.EditorDraftSession(draftStore, pending.key)
        drafts.load()
        assertTrue(drafts.enqueueInlineQuoteRequest(pending) is EditorDraftStore.StoreWriteResult.Written)
    }

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
            every { observeEditorShortcut() } returns shortcutPreference
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

    // v0.7.8 ED-89/132: Room owns Q; restoring during its GET must not replace the quote.
    @Test
    fun `legacy MP quote coordinates without Room Q never authorize a quote GET`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = PrivateMessageReplyViewModel(
            quoteRequest, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock, smileyRepository(), favoriteRepository, localCollections,
        ).also(::trackViewModel)
        assertEquals("", vm.bodyFieldState.text.toString())
        coVerify(exactly = 1) { repository.fetchReplyForm(match { it.quote == null }, any()) }
        coVerify(exactly = 0) { repository.fetchReplyForm(match { it.quote != null }, any()) }
    }

    @Test
    fun `MP Reload retries GET alone and ignoring after two failures preserves C and D`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            if (firstArg<PrivateMessageReplyContext>().quote != null) throw IOException("offline")
            form()
        }
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(pending.key, EditorDraftStore.Draft("D", isPrivate = true,
            pendingInlineQuoteRequest = pending))
        val vm = viewModel(repository)
        assertFalse(vm.state.value.canIgnorePendingQuote)
        vm.editBody(TextFieldValue("typed C"))
        vm.retryFormLoad()
        runCurrent()
        assertTrue(vm.state.value.canIgnorePendingQuote)
        vm.onPendingQuoteIgnored()
        runCurrent()
        assertEquals("typed C", vm.bodyFieldState.text.toString())
        assertEquals("D", vm.state.value.restorableDraft)
        assertNull(draftStore.saved[pending.key]?.pendingInlineQuoteRequest)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun `MP Restore retains a modified delivered block and is one Undo Redo step`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val quote = "[quotemsg=101]original[/quotemsg]"
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = quote)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(pending.key, EditorDraftStore.Draft(quote, isPrivate = true,
            pendingInlineQuoteRequest = pending))
        val vm = viewModel(repository)
        val modified = quote.replace("original", "edited")
        vm.editBody(TextFieldValue("$modified\n\ntyped", TextRange(2, 6)))
        val before = vm.bodyFieldState.text.toString()
        vm.onDraftRestoreRequested()
        val merged = "$quote\n\n$modified\n\ntyped"
        assertEquals(merged, vm.bodyFieldState.text.toString())
        vm.bodyFieldState.undoState.undo()
        assertEquals(before, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(2, 6), vm.bodyFieldState.selection)
        vm.bodyFieldState.undoState.redo()
        assertEquals(merged, vm.bodyFieldState.text.toString())
    }

    @Test
    fun `durable MP request delivers after restored D and preserves typing during GET`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true, pendingInlineQuoteRequest = pending))
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("typed"))
        vm.onDraftRestoreRequested()
        runCurrent()
        assertEquals("D\n\ntyped", vm.bodyFieldState.text.toString())
        val quote = "[quotemsg=101]hello[/quotemsg]"
        gate.complete(form(initialContent = quote))
        runCurrent()
        assertEquals("D\n\n$quote\n\ntyped", vm.bodyFieldState.text.toString())
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertTrue(checkNotNull(draftStore.saved[key]).isPrivate)
        coVerify(exactly = 1) { repository.fetchReplyForm(match {
            it.page == 2 && it.quote == PrivateMessageQuote(101, 7)
        }, any()) }
    }

    @Test
    fun `MP close after a failed quote consumption settles it and saves the typing made since - ED132`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true, pendingInlineQuoteRequest = pending))
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        val quote = "[quotemsg=101]hello[/quotemsg]"
        draftStore.saveFailure = IOException("disk full")
        gate.complete(form(initialContent = quote))
        runCurrent()
        assertEquals(pending, draftStore.saved[key]?.pendingInlineQuoteRequest)
        val typed = "$quote\n\ntyped after the delivery"
        vm.editBody(TextFieldValue(typed))
        draftStore.saveFailure = null
        vm.onCloseRequested()
        runCurrent()
        // The pop is only allowed once C and Q are settled and the live field is durable.
        assertEquals(typed, draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `MP close is refused once while a delivered quote cannot be made durable then abandons - ED132`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true, pendingInlineQuoteRequest = pending))
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        val effects = mutableListOf<PrivateMessageReplyEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        val quote = "[quotemsg=101]hello[/quotemsg]"
        draftStore.saveFailure = IOException("disk full")
        gate.complete(form(initialContent = quote))
        runCurrent()
        vm.editBody(TextFieldValue("$quote\n\ntyped after the delivery"))
        vm.onCloseRequested()
        runCurrent()
        // The typing cannot be saved: the editor stays open and nothing is half-written.
        assertFalse(PrivateMessageReplyEffect.CloseCommitted in effects)
        assertEquals("D", draftStore.saved[key]?.body)
        assertEquals(pending, draftStore.saved[key]?.pendingInlineQuoteRequest)
        // A second close is the explicit abandonment.
        vm.onCloseRequested()
        runCurrent()
        assertTrue(PrivateMessageReplyEffect.CloseCommitted in effects)
    }

    @Test
    fun `MP Ignore after a conflict releases the reply when the request was consumed elsewhere - ED132`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            if (firstArg<PrivateMessageReplyContext>().quote != null) throw IOException("offline")
            form()
        }
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(pending.key, EditorDraftStore.Draft("D", isPrivate = true,
            pendingInlineQuoteRequest = pending))
        val vm = viewModel(repository)
        vm.editBody(TextFieldValue("typed C"))
        vm.retryFormLoad()
        runCurrent()
        assertTrue(vm.state.value.canIgnorePendingQuote)
        // Another editor consumes the request and moves the row on.
        val entry = checkNotNull(draftStore.entries[pending.key])
        draftStore.entries[pending.key] = entry.copy(
            draft = entry.draft.copy(pendingInlineQuoteRequest = null), version = entry.version + 1,
        )
        vm.onPendingQuoteIgnored()
        runCurrent()
        // Nothing is left to ignore: the reply is released, loads its form and keeps the field.
        assertFalse(vm.state.value.quotePending)
        assertFalse(vm.state.value.canIgnorePendingQuote)
        assertFalse(vm.state.value.formError)
        assertEquals("typed C", vm.bodyFieldState.text.toString())
        assertNull(draftStore.saved[pending.key]?.pendingInlineQuoteRequest)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `MP quote delivered after a failed Restore write consumes the merged D with C - ED121`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true, pendingInlineQuoteRequest = pending))
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        draftStore.saveFailure = IOException("disk full")
        vm.onDraftRestoreRequested()
        runCurrent()
        assertEquals("D", vm.bodyFieldState.text.toString())
        draftStore.saveFailure = null
        val quote = "[quotemsg=101]hello[/quotemsg]"
        gate.complete(form(initialContent = quote))
        runCurrent()
        assertEquals("D\n\n$quote", vm.bodyFieldState.text.toString())
        assertEquals("D\n\n$quote", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        // A recreation must not find D offered again: restoring it would duplicate it in C.
        assertTrue(draftStore.loadOffers("tester", key).none { it.draft.body == "D" })
        assertNull(vm.state.value.restorableDraft)
    }

    @Test
    fun `MP send after a failed Restore write consumes the merged D it publishes - ED121`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true))
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val vm = viewModel(repository)
        runCurrent()
        draftStore.saveFailure = IOException("disk full")
        vm.onDraftRestoreRequested()
        runCurrent()
        assertEquals("D", vm.bodyFieldState.text.toString())
        draftStore.saveFailure = null
        // Send before the autosave debounce: the attempt itself must carry the pending resolution.
        Snapshot.sendApplyNotifications()
        runCurrent()
        vm.onSubmit()
        runCurrent()
        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, draftStore.entries[key]?.attempt?.status)
        assertTrue(draftStore.loadOffers("tester", key).none { it.draft.body == "D" })
    }

    @Test
    fun `MP Reload after a storage conflict rereads the row and delivers the quote keeping C - ED132`() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 7)))
        draftStore.preload(key, EditorDraftStore.Draft("", isPrivate = true, pendingInlineQuoteRequest = pending))
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        runCurrent()
        // Another writer moves the row on while the GET is in flight: the delivery CAS conflicts.
        val entry = checkNotNull(draftStore.entries[key])
        draftStore.entries[key] = entry.copy(version = entry.version + 1)
        val quote = "[quotemsg=101]hello[/quotemsg]"
        gate.complete(form(initialContent = quote))
        runCurrent()
        assertEquals(pending, draftStore.saved[key]?.pendingInlineQuoteRequest)
        val delivered = vm.bodyFieldState.text.toString()
        assertTrue(delivered.startsWith(quote))
        vm.retryFormLoad()
        runCurrent()
        // The reread version lets the same delivery commit; the field was never replaced.
        assertEquals(delivered, vm.bodyFieldState.text.toString())
        assertEquals(delivered, draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.pendingInlineQuoteRequest)
        assertFalse(vm.state.value.formError)
    }

    @Test
    fun `fresh quote-only opening discards private C before another death and preserves offered D`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val key = EditorDraftKey.mpReply(request.threadId)
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 1, 0)))
        val quote = "[quotemsg=101]one[/quotemsg]"
        draftStore.preload(key, EditorDraftStore.Draft("D", isPrivate = true, updatedAt = 1234))
        val previous = EditorDraftSession(draftStore, key)
        previous.load()
        previous.enqueueInlineQuoteRequest(pending)
        previous.consumeInlineQuoteRequest(
            pending, EditorDraftStore.Draft(quote, isPrivate = true), deliveredBody = quote,
        )
        val offers = draftStore.loadOffers("tester", key)
        val saved = SavedStateHandle()
        val fresh = viewModel(repository, savedStateHandle = saved)
        runCurrent()
        assertEquals("", fresh.bodyFieldState.text.toString())
        assertEquals("D", fresh.state.value.restorableDraft)
        assertEquals("", draftStore.saved[key]?.body)
        assertNull(draftStore.saved[key]?.bodyProvenance)
        fresh.viewModelScope.cancel()
        val frozen = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val afterDeath = viewModel(repository, savedStateHandle = frozen)
        runCurrent()
        assertEquals("", afterDeath.bodyFieldState.text.toString())
        assertEquals("D", afterDeath.state.value.restorableDraft)
        assertEquals(offers, draftStore.loadOffers("tester", key))
    }

    @Test
    fun `editor shortcut observes persisted choice and live changes without a draft mutation`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        shortcutPreference.value = EditorShortcut.BLUE
        val vm = viewModel(repository)
        assertEquals(EditorShortcut.BLUE, vm.state.value.editorShortcut)
        val body = vm.bodyFieldState.text.toString()
        shortcutPreference.value = EditorShortcut.PERSONAL_SMILEY
        assertEquals(EditorShortcut.PERSONAL_SMILEY, vm.state.value.editorShortcut)
        shortcutPreference.value = EditorShortcut.NONE
        assertEquals(EditorShortcut.NONE, vm.state.value.editorShortcut)
        assertEquals(body, vm.bodyFieldState.text.toString())
    }

    @Test
    fun `successful form retry keeps preparation and uncertain errors aligned with their events`() = runTest {
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
        val preparationEvent = vm.editorEvents.visibleEvent
        vm.retryFormLoad()
        advanceUntilIdle()
        assertEquals(EditorErrors.invalidForm, vm.state.value.submitError)
        assertEquals(preparationEvent, vm.editorEvents.visibleEvent)
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
    fun `conversation title comes only from a successful loaded form and is cleared on owner change`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val gate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val authRepository = mockk<AuthRepository> { every { observeAuthState() } returns auth }
        val vm = viewModel(repository = repository, authRepository = authRepository)
        runCurrent()
        assertEquals("", vm.state.value.conversationTitle)
        gate.complete(ownerForm().copy(sujet = "Titre privé du formulaire"))
        advanceUntilIdle()
        assertEquals("Titre privé du formulaire", vm.state.value.conversationTitle)
        vm.chromeMemory.finishRecipientEntry()
        vm.chromeMemory.host.focus.imeBeforeSurface = true
        coEvery { repository.fetchReplyForm(any(), any()) } returns ownerForm().copy(sujet = "Titre actualisé")
        vm.retryFormLoad()
        advanceUntilIdle()
        assertEquals("Titre actualisé", vm.state.value.conversationTitle)
        assertTrue(vm.state.value.canManageRecipients)
        assertEquals(listOf("alice", "bob", "Bébé Yoda", "stitch+", "Administration"), vm.state.value.recipients)
        auth.value = AuthState.Anonymous
        advanceUntilIdle()
        assertEquals("", vm.state.value.conversationTitle)
        assertFalse(vm.state.value.canManageRecipients)
        assertTrue(vm.state.value.recipients.isEmpty())
        assertFalse(vm.chromeMemory.recipientEntryHandled)
        assertFalse(vm.chromeMemory.host.focus.imeBeforeSurface)
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
    fun `cancelled picker preserves the previous error body selection and effects without upload`() = runTest {
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
        viewModel.editBody(TextFieldValue(text = "draft", selection = TextRange(1, 4)))
        advanceUntilIdle()
        viewModel.onImagePickerEvent(
            ImagePickerEvent.Unavailable(ImagePickerMode.PHOTO_PICKER, ImagePickerContract.PICK_MULTIPLE_VISUAL_MEDIA),
        )
        advanceUntilIdle()
        assertEquals(EditorErrors.pickerAbsent, viewModel.state.value.uploadError)
        val stateBefore = viewModel.state.value

        viewModel.effects.test {
            ImagePickerContract.entries.forEach { contract ->
                diagnostics.clear()
                viewModel.onImagePickerEvent(ImagePickerEvent.Cancelled(contract, resultCode = 0))
                advanceUntilIdle()

                assertEquals(stateBefore, viewModel.state.value)
                assertEquals("draft", viewModel.bodyFieldState.text.toString())
                assertEquals(TextRange(1, 4), viewModel.bodyFieldState.selection)
                assertTrue(reader.readUris.isEmpty())
                assertEquals(0, uploads.uploadCalls)
                assertEquals(
                    listOf("cancelled contract=${contract.diagnosticName} resultCode=0"),
                    diagnostics.entries.value.filter { it.tag == "ImagePicker" }.map { it.message },
                )
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
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

        enqueueQuoteLaunch(quoteRequest)
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

        enqueueQuoteLaunch(multiQuoteRequest)
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
        enqueueQuoteLaunch(quoteRequest)
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

        enqueueQuoteLaunch(quoteRequest)
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
        enqueueQuoteLaunch(quoteRequest)
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
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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
        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        val open = viewModel.smileyPicker.state.value as SmileyPickerState.Open
        assertEquals(listOf(favorite), open.favoriteSmileys)
        viewModel.editBody(TextFieldValue("hello", TextRange(5)))
        viewModel.onSmileySelected(open.favoriteSmileys.single().token)
        assertEquals("hello [:fictif espace:3] ", viewModel.bodyFieldState.text.toString())
        assertEquals(SmileyPickerState.Hidden, viewModel.smileyPicker.state.value)

        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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

        viewModel.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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
        assertEquals("rescued MP\n\nnew live MP", draftStore.saved[key]?.body)

        val afterRestore = PrivateMessageReplyViewModel(
            request, repository, previewParser, mainDispatcher, userPreferences(), draftStore,
            FakeAuthRepository(), FakeUploadRepository(), FakeImageUploadReader(), DiagnosticsLog(),
            submissionClock,
            smileyRepository(), favoriteRepository, localCollections, savedStateHandle,
        ).also(::trackViewModel)
        assertNull(afterRestore.state.value.restorableDraft)
        assertEquals("rescued MP\n\nnew live MP", afterRestore.bodyFieldState.text.toString())
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
    fun `v078 ED121 hydration does not decide an identical offered draft`() = runTest {
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
        // ED-121, projection rule of the list (gate r2, point 5): an offer identical to the field has
        // nothing to restore, so it is hidden; hydration still decides nothing, the draft stays durable
        // and is offered again as soon as the field differs.
        val key = EditorDraftKey.mpReply(request.threadId)
        assertNull(viewModel.state.value.restorableDraft)
        assertTrue(viewModel.state.value.draftOffers.isEmpty())
        assertEquals(body, draftStore.saved[key]?.body)
        viewModel.editBody(TextFieldValue("$body, edited"))
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(body, viewModel.state.value.restorableDraft)
        assertEquals(listOf(body), draftStore.loadOffers("tester", key).map { it.draft.body })
    }

    @Test
    fun `restoring puts the offered private reply before live text - ED89`() = runTest {
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

        // ED-89 case 4 (v0.7.8): empty seed and typed text give the draft, then the typing.
        assertEquals("rescued MP\n\nfresh MP", viewModel.bodyFieldState.text.toString())
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
    fun `1450 ignoring an MP offer keeps the live quote and typing in durable C`() = runTest {
        val vm = replyWithRestoreOffer(seed = "seed")
        vm.editBody(TextFieldValue("seed\n\ntyped", TextRange(3)))
        vm.onDraftDiscardRequested()
        advanceUntilIdle()
        val key = EditorDraftKey.mpReply(request.threadId)
        assertEquals("seed\n\ntyped", vm.bodyFieldState.text.toString())
        assertEquals("seed\n\ntyped", draftStore.saved[key]?.body)
        assertTrue(draftStore.loadOffers(draftStore.activeOwner, key).isEmpty())
        assertNull(vm.state.value.restorableDraft)
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
    private fun assertRestoreAndSingleUndo(
        vm: PrivateMessageReplyViewModel,
        before: TextFieldValue,
        expected: String,
    ): String {
        vm.editBody(before)
        val seed = vm.state.value.draftHydratedContent
        vm.onDraftRestoreRequested()
        val restored = vm.bodyFieldState.text.toString()
        assertEquals(expected, restored)
        assertEquals(TextRange(expected.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
        if (expected == before.text) return restored
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals(before.text, vm.bodyFieldState.text.toString())
        assertEquals(before.selection, vm.bodyFieldState.selection)
        return restored
    }

    @Test
    fun restoreReplacesBlankField() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue(" \n", TextRange(1)), "draft")
    }

    @Test
    fun v078RestoreKeepsUntouchedReplyContent() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seed", TextRange(1, 3)), "draft\n\nseed")
    }

    @Test
    fun restoreKeepsTextTypedAfterSeed() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(), TextFieldValue("seed \n\n suffix  \n", TextRange(2, 6)),
            "draft\n\nseed \n\n suffix  \n",
        )
    }

    @Test
    fun restoreAppendsAfterTypedTextWhenSeedEmpty() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(seed = ""),
            TextFieldValue("typed", TextRange(1, 3)),
            "draft\n\ntyped",
        )
    }

    @Test
    fun restoreAppendsWhenSeedEditedInside() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seXd", TextRange(2)), "draft\n\nseXd")
    }

    @Test
    fun v078RestorePreservesTrailingReplyWhitespace() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(seed = ""), TextFieldValue("typed \n\n", TextRange(2)), "draft\n\ntyped \n\n",
        )
    }

    @Test
    fun v078RestorePreservesBlankSuffixOfReplyContent() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("seed \n\t", TextRange(2, 5)),
            "draft\n\nseed \n\t")
    }

    @Test
    fun restoreIdenticalLiveBodyDoesNotDuplicateIt() = runTest {
        assertRestoreAndSingleUndo(replyWithRestoreOffer(), TextFieldValue("draft", TextRange(1, 3)), "draft")
    }

    @Test
    fun restorePreservesDraftWhitespaceVerbatim() = runTest {
        assertRestoreAndSingleUndo(
            replyWithRestoreOffer(draft = "  draft  \n"), TextFieldValue("seed", TextRange(1, 3)),
            "  draft  \n\n\nseed",
        )
    }

    @Test
    fun editReopenRestoreYieldsSingleCopy() = runTest {
        val seed = "[quotemsg=101][b]alice a écrit :[/b]hello[/quotemsg]\n"
        val draft = seed + "Ma réponse"
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = seed)
        enqueueQuoteLaunch(request.copy(quote = PrivateMessageQuote(101, 1)))
        val original = viewModel(repository)
        original.editBody(TextFieldValue(draft))
        original.onCloseRequested()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, original.effects.first())
        original.viewModelScope.cancel()
        enqueueQuoteLaunch(request.copy(quote = PrivateMessageQuote(101, 1)))
        val reopened = viewModel(repository)
        assertEquals(draft, reopened.state.value.restorableDraft)
        val restored = assertRestoreAndSingleUndo(reopened, TextFieldValue(seed, TextRange(1, 3)), draft)
        assertEquals(1, restored.split("[quotemsg=").size - 1)
    }

    @Test
    fun editReopenRestoreKeepsNewTextAfterQuote() = runTest {
        val seed = "[quotemsg=101][b]alice a écrit :[/b]hello[/quotemsg]\n"
        val draft = seed + "Ma réponse"
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form(initialContent = seed)
        enqueueQuoteLaunch(request.copy(quote = PrivateMessageQuote(101, 1)))
        val original = viewModel(repository)
        original.editBody(TextFieldValue(draft))
        original.onCloseRequested()
        assertEquals(PrivateMessageReplyEffect.CloseCommitted, original.effects.first())
        original.viewModelScope.cancel()
        enqueueQuoteLaunch(request.copy(quote = PrivateMessageQuote(101, 1)))
        val reopened = viewModel(repository)
        assertEquals(draft, reopened.state.value.restorableDraft)
        val restored = assertRestoreAndSingleUndo(
            reopened, TextFieldValue(seed + "nouveau", TextRange(seed.length)), draft + "\n\nnouveau",
        )
        assertEquals(1, restored.split("[quotemsg=").size - 1)
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
        assertRestoreAndSingleUndo(vm, TextFieldValue("seed", TextRange(2)), "draft\n\nseed")
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
    fun lateHydrationAutosavesQuoteCombinedWithTypedText() = runTest {
        val key = EditorDraftKey.mpReply(request.threadId)
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val vm = viewModel(repository)
        val typed = "Ma réponse"
        vm.editBody(TextFieldValue(typed))
        advanceTimeBy(750)
        runCurrent()
        assertEquals(typed, draftStore.saved[key]?.body)
        assertEquals(1, draftStore.saveCount)

        val seed = "[quote=alice]hello[/quote]\n"
        gate.complete(form(initialContent = seed))
        Snapshot.sendApplyNotifications()
        runCurrent()
        val combined = seed + "\n" + typed
        assertEquals(combined, vm.bodyFieldState.text.toString())
        advanceTimeBy(749)
        runCurrent()
        assertEquals(typed, draftStore.saved[key]?.body)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(combined, draftStore.saved[key]?.body)
        assertTrue(draftStore.saved[key]?.isPrivate == true)
        assertEquals(2, draftStore.saveCount)
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

    // v0.7.8 ED-89: a reply has no server edit seed; Restore is allowed while its GET loads.
    @Test
    fun restoreAllowedWhileReplyFormLoads() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        draftStore.preload(EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true))
        val vm = viewModel(repository)
        assertTrue(vm.state.value.isLoadingForm)
        vm.onDraftRestoreRequested()
        assertEquals("draft", vm.bodyFieldState.text.toString())
        gate.complete(form())
        runCurrent()
        assertEquals("draft", vm.bodyFieldState.text.toString())
        assertNull(vm.state.value.restorableDraft)
    }

    @Test
    fun restoreAllowedWithEmptySeedAfterFormFailure() = runTest {
        val gate = CompletableDeferred<ReplyForm>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true),
        )
        val vm = viewModel(repository)
        assertTrue(vm.state.value.isLoadingForm)
        gate.completeExceptionally(IOException("network down"))
        runCurrent()
        assertFalse(vm.state.value.isLoadingForm)
        assertTrue(vm.state.value.formError)
        assertFalse(vm.state.value.formAvailable)
        assertFalse(vm.state.value.draftHydratedFromForm)
        assertEquals("", vm.state.value.draftHydratedContent)
        assertEquals("draft", vm.state.value.restorableDraft)
        assertRestoreAndSingleUndo(vm, TextFieldValue("", TextRange.Zero), "draft")
    }

    @Test
    fun restoreAfterFormFailureIsKeptWhenRetriedFormCarriesQuote() = runTest {
        // ED-89/132 v0.7.8: a restored body cannot suppress an explicitly pending Q.
        val pending = PendingInlineQuoteRequest.privateMessage(request.threadId,
            listOf(PendingInlineQuoteRequest.Quote(101, 1, 1)))
        val quote = "[quotemsg=101][b]alice a écrit :[/b]hello[/quotemsg]"
        var fetches = 0
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            if (fetches++ == 0) throw IOException("network down")
            form(initialContent = quote)
        }
        draftStore.preload(
            EditorDraftKey.mpReply(request.threadId),
            EditorDraftStore.Draft(body = "draft", isPrivate = true, pendingInlineQuoteRequest = pending),
        )
        val vm = viewModel(repository)
        runCurrent()
        assertTrue(vm.state.value.formError)
        vm.onDraftRestoreRequested()
        assertEquals("draft", vm.bodyFieldState.text.toString())

        // Restore decides D, while the explicit pending citation still arrives after D.
        vm.retryFormLoad()
        runCurrent()
        assertTrue(vm.state.value.formAvailable)
        assertFalse(vm.state.value.formError)
        assertEquals("draft\n\n$quote", vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.draftHydratedContent)
        assertNull(vm.state.value.restorableDraft)
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
        vm.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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
    @OptIn(ExperimentalFoundationApi::class)
    fun uploadCompletedBeforeFormKeepsImageAndPrefixesQuote() = runTest {
        val formGate = CompletableDeferred<ReplyForm>()
        val uploadGate = CompletableDeferred<Unit>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        val uploads = FakeUploadRepository().apply { this.uploadGate = uploadGate }
        val vm = viewModel(repository, uploadRepository = uploads)
        vm.onImagesPicked(listOf("content://pick/1"))
        assertTrue(vm.state.value.isUploading)
        uploadGate.complete(Unit)
        runCurrent()
        val image = "[url=https://rehost.diberie.com/Picture/Get/f/1]" +
            "[img]https://rehost.diberie.com/Picture/Get/f/1[/img][/url]"
        assertFalse(vm.state.value.isUploading)
        assertTrue(vm.state.value.isLoadingForm)
        assertEquals(image, vm.bodyFieldState.text.toString())
        assertEquals("", vm.state.value.draftHydratedContent)

        val seed = "[quote=alice]hello[/quote]\n"
        formGate.complete(form(initialContent = seed))
        runCurrent()
        val combined = seed + "\n" + image
        assertFalse(vm.state.value.isLoadingForm)
        assertEquals(combined, vm.bodyFieldState.text.toString())
        assertEquals(TextRange(combined.length), vm.bodyFieldState.selection)
        assertEquals(seed, vm.state.value.draftHydratedContent)
        assertTrue(vm.bodyFieldState.undoState.canUndo)
        vm.bodyFieldState.undoState.undo()
        assertEquals(image, vm.bodyFieldState.text.toString())
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
            // ED-89 (v0.7.8): an MP reply no longer has a seed that the draft replaces; whatever the
            // form put in the field stays after the restored draft.
            vm, TextFieldValue(uploaded, TextRange(1, 3)), "draft\n\n" + uploaded,
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
    fun `an offer equal to the field is hidden while the other offered draft stays reachable - ED121`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { formGate.await() }
        val key = EditorDraftKey.mpReply(request.threadId)
        draftStore.preload(key, EditorDraftStore.Draft(body = "same", isPrivate = true))
        draftStore.preloadOffer(key, EditorDraftStore.DraftOffer("older", 0,
            EditorDraftStore.Draft(body = "other", isPrivate = true)))
        val vm = viewModel(repository)
        assertEquals(setOf("same", "other"), vm.state.value.draftOffers.map { it.draft.body }.toSet())
        vm.bodyFieldState.edit { append("same") }
        formGate.complete(form())
        runCurrent()
        // The whole projection is filtered, not only its first line; nothing is deleted from the store.
        assertEquals(listOf("other"), vm.state.value.draftOffers.map { it.draft.body })
        assertEquals("other", vm.state.value.restorableDraft)
        assertEquals(listOf("other"), draftStore.loadOffers("tester", key).map { it.draft.body })
        assertEquals("same", draftStore.saved[key]?.body)
        vm.onDraftRestoreRequested()
        assertEquals("other\n\nsame", vm.bodyFieldState.text.toString())
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
        // ED-89 case 4 (v0.7.8): the offered draft comes first, the live typing after it.
        assertEquals("stored\n\ntyped", vm.bodyFieldState.text.toString())
        assertEquals(TextRange(13), vm.bodyFieldState.selection)
        assertNull(vm.state.value.restorableDraft)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals("stored\n\ntyped", draftStore.saved[key]?.body)
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
        // ED-121 (v0.7.8): the stored draft is durable and stays offered under the status icon instead
        // of being silently overwritten by the next autosave; the live typing is left untouched.
        assertEquals("stored", vm.state.value.restorableDraft)
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
        vm.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
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
    fun `ED37 wrapping and upload cancel the typing debounce with one checkpoint`() = runTest {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        val vm = viewModel(repository)
        runCurrent()
        val key = EditorDraftKey.mpReply(request.threadId)
        val saves = draftStore.saveCount
        val writes = draftStore.writes
        vm.editBody(TextFieldValue("nom", TextRange(3, 0)))
        mainDispatcher.scheduler.advanceTimeBy(300)
        runCurrent()
        assertEquals(saves, draftStore.saveCount)
        assertEquals(writes, draftStore.writes)
        vm.onToolbarAction(BbcodeAction.Image)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals("[img]nom[/img]", draftStore.saved[key]?.body)
        assertEquals(saves + 1, draftStore.saveCount)
        assertEquals(writes + 1, draftStore.writes)
        // The image checkpoint must cancel the typing debounce, including a delayed mirror.
        mainDispatcher.scheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(saves + 1, draftStore.saveCount)
        assertEquals(writes + 1, draftStore.writes)
        val now = mainDispatcher.scheduler.currentTime
        val beforeUpload = vm.bodyFieldState.text.toString()
        vm.onImagesPicked(listOf("content://pick/1"))
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertTrue(vm.bodyFieldState.text.toString() != beforeUpload)
        assertEquals(vm.bodyFieldState.text.toString(), draftStore.saved[key]?.body)
        assertEquals(now, mainDispatcher.scheduler.currentTime)
    }

    @Test
    fun `ED37 image actions and stop cannot checkpoint while sending`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        }
        val vm = viewModel(repository)
        runCurrent()
        vm.editBody(TextFieldValue("body", TextRange(4)))
        vm.onSubmit()
        runCurrent()
        assertTrue(vm.state.value.submission is EditorSubmissionState.Sending)
        val saves = draftStore.saveCount
        val writes = draftStore.writes
        val entry = draftStore.entries[EditorDraftKey.mpReply(request.threadId)]

        vm.onToolbarAction(BbcodeAction.Image)
        vm.onStopped()
        mainDispatcher.scheduler.advanceTimeBy(1_000)
        runCurrent()

        assertTrue(vm.state.value.submission is EditorSubmissionState.Sending)
        assertEquals("body", vm.bodyFieldState.text.toString())
        assertEquals(saves, draftStore.saveCount)
        assertEquals(writes, draftStore.writes)
        assertEquals(entry, draftStore.entries[EditorDraftKey.mpReply(request.threadId)])
        gate.complete(Unit)
        runCurrent()
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
    fun `ED45 ED170 Masquer acknowledges a restored attempt before recreation`() = runTest {
        val attempt = seedUncertainAttempt()
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        settle()
        val adapter = vm.chromeAdapter()
        val key = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        settle()
        assertNull(adapter.detail)
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        // The acknowledgement reserves PersistingAttempt: no « Envoi… » may outlive it.
        vm.assertNoSendingEvent()
        assertNull(vm.editorEvents.state.visibleEvent)
        assertNull(draftStore.entries[attempt.key]?.attempt)
        assertEquals("uncertain body", vm.bodyFieldState.text.toString())
        vm.viewModelScope.cancel()

        val recreated = viewModel(repository)
        settle()
        assertEquals(EditorSubmissionState.Ready, recreated.state.value.submission)
        assertNull(recreated.state.value.submitError)
        assertTrue(recreated.editorEvents.state.events.isEmpty())
        assertEquals("uncertain body", draftStore.saved[attempt.key]?.body)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ED45 ED170 Masquer also acknowledges a freshly failed submission`() = runTest {
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        settle()
        vm.editBody(TextFieldValue("sent body"))
        vm.onSubmit()
        settle()
        assertTrue(vm.state.value.submission is EditorSubmissionState.Uncertain)
        val adapter = vm.chromeAdapter()
        val key = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        settle()
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        vm.assertNoSendingEvent()
        assertNull(vm.editorEvents.state.visibleEvent)
        assertNull(draftStore.entries[EditorDraftKey.mpReply(request.threadId)]?.attempt)
        assertEquals("sent body", vm.bodyFieldState.text.toString())
        coVerify(exactly = 1) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ED85 ED170 Back closes uncertainty visually and recreation offers it again`() = runTest {
        val attempt = seedUncertainAttempt()
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        settle()
        val adapter = vm.chromeAdapter()
        val key = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.back()
        settle()
        assertNull(adapter.detail)
        assertNull(vm.editorEvents.state.visibleEvent)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(uncertainReplyError, vm.state.value.submitError)
        assertEquals(attempt, draftStore.entries[attempt.key]?.attempt?.token)
        vm.viewModelScope.cancel()

        val recreated = viewModel(repository)
        settle()
        assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
        assertEquals(uncertainReplyError, recreated.state.value.submitError)
        assertEquals(EditorEventContent.Problem(uncertainReplyError),
            recreated.editorEvents.state.visibleEvent?.content)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ED170 Masquer preserves uncertainty on failed durable acknowledgement and recreation`() = runTest {
        val attempt = seedUncertainAttempt()
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        settle()
        draftStore.finishFailure = IOException("disk unavailable")
        val finishCalls = draftStore.finishCalls
        val adapter = vm.chromeAdapter()
        val key = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        settle()
        assertEquals(finishCalls + 1, draftStore.finishCalls)
        assertEquals(EditorSubmissionState.Uncertain(attempt), vm.state.value.submission)
        assertEquals(uncertainReplyError, vm.state.value.submitError)
        // The kept uncertainty is signalled again in this session, without a dangling « Envoi… ».
        vm.assertNoSendingEvent()
        assertEquals(EditorEventContent.Problem(uncertainReplyError), vm.editorEvents.state.visibleEvent?.content)
        assertEquals(attempt, draftStore.entries[attempt.key]?.attempt?.token)
        assertEquals("uncertain body", vm.bodyFieldState.text.toString())
        vm.viewModelScope.cancel()

        val recreated = viewModel(repository)
        settle()
        assertEquals(EditorSubmissionState.Uncertain(attempt), recreated.state.value.submission)
        assertEquals(EditorEventContent.Problem(uncertainReplyError),
            recreated.editorEvents.state.visibleEvent?.content)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ED170 the redelivered uncertainty stays bound and acknowledges once storage recovers`() = runTest {
        val attempt = seedUncertainAttempt()
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        settle()
        draftStore.finishFailure = IOException("disk unavailable")
        val adapter = vm.chromeAdapter()
        val first = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(first, EditorEventAction.Details)
        adapter.dismissDetails(first)
        settle()
        val again = vm.editorEvents.state.visibleEvent!!.key
        assertNotEquals(first, again)
        draftStore.finishFailure = null
        val finishCalls = draftStore.finishCalls
        adapter.dismissDetails(first)
        adapter.barActions.onEvent(again, EditorEventAction.Details)
        adapter.dismissDetails(again)
        settle()
        assertEquals(finishCalls + 1, draftStore.finishCalls)
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        vm.assertNoSendingEvent()
        assertNull(vm.editorEvents.state.visibleEvent)
        assertNull(draftStore.entries[attempt.key]?.attempt)
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ED45 ED170 local Masquer in Ready allows the next failed autosave to deliver information`() = runTest {
        assertLocalMasquerReleasesAutosave(rearm = false)
    }

    @Test
    fun `ED45 ED170 local Masquer after rearming allows the next failed autosave to deliver information`() = runTest {
        assertLocalMasquerReleasesAutosave(rearm = true)
    }

    @Test
    fun `ED85 Back closes a failed attempt write visually and keeps later draft information deferred`() = runTest {
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository)
        val adapter = failAttemptWrite(vm, confirm = false)
        val key = vm.editorEvents.state.visibleEvent!!.key
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.back()
        settle()
        assertNull(adapter.detail)
        assertNull(vm.editorEvents.state.visibleEvent)
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)

        draftStore.saveFailure = IOException("still full")
        vm.editBody(TextFieldValue("continued composition"))
        settle()
        // Back is visual only: without the explicit Masquer, the failed attempt keeps deferring draft information.
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)
        assertNull(vm.editorEvents.state.visibleEvent)
        assertEquals("continued composition", vm.bodyFieldState.text.toString())
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    private fun TestScope.assertLocalMasquerReleasesAutosave(rearm: Boolean) {
        val repository = uncertainReplyRepository()
        val vm = viewModel(repository, confirmBeforePosting = rearm)
        val adapter = failAttemptWrite(vm, confirm = rearm)
        if (rearm) {
            // A deliberate new arm is not an attempt: « Brouillon non écrit » stays displayed while Armed.
            adapter.submitActions.onSubmit()
            runCurrent()
            assertTrue(vm.state.value.submission is EditorSubmissionState.Armed)
        }
        val submission = vm.state.value.submission
        val activity = listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount)
        val key = vm.editorEvents.state.visibleEvent!!.key
        assertEquals(EditorEventContent.Problem(EditorErrors.draftWriteFailed),
            vm.editorEvents.state.visibleEvent?.content)
        adapter.barActions.onEvent(key, EditorEventAction.Details)
        adapter.dismissDetails(key)
        // Synchronous and storage-free; settling here would also expire the arm.
        runCurrent()
        assertEquals(submission, vm.state.value.submission)
        assertNull(vm.state.value.submitError)
        assertNull(adapter.detail)
        assertNull(vm.editorEvents.state.visibleEvent)
        assertEquals(activity,
            listOf(draftStore.writes, draftStore.beginCalls, draftStore.finishCalls, draftStore.saveCount))
        vm.assertNoSendingEvent()

        draftStore.saveFailure = IOException("still full")
        vm.editBody(TextFieldValue("continued composition"))
        settle()
        assertEquals(EditorErrors.draftSaveFailed, vm.state.value.submitError)
        assertEquals(EditorEventContent.Problem(EditorErrors.draftSaveFailed),
            vm.editorEvents.state.visibleEvent?.content)
        assertEquals("continued composition", vm.bodyFieldState.text.toString())
        assertEquals(0, draftStore.finishCalls)
        vm.assertNoSendingEvent()
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    /** The composition is saved, then storage refuses the attempt write: « Brouillon non écrit », nothing sent. */
    private fun TestScope.failAttemptWrite(
        vm: PrivateMessageReplyViewModel,
        confirm: Boolean,
    ): PrivateMessageReplyChromeAdapter {
        settle()
        vm.editBody(TextFieldValue("unsent body"))
        settle()
        draftStore.beginFailure = IOException("disk full")
        vm.onSubmit()
        if (confirm) {
            advanceTimeBy(400) // ED-166: a deliberate confirmation.
            vm.onSubmitConfirmed(vm.state.value.armToken)
        }
        settle()
        assertEquals(EditorSubmissionState.Ready, vm.state.value.submission)
        assertEquals(EditorErrors.draftWriteFailed, vm.state.value.submitError)
        assertEquals(EditorEventContent.Problem(EditorErrors.draftWriteFailed),
            vm.editorEvents.state.visibleEvent?.content)
        assertEquals(1, draftStore.beginCalls)
        assertNull(draftStore.entries[EditorDraftKey.mpReply(request.threadId)]?.attempt)
        return vm.chromeAdapter()
    }

    private val uncertainReplyError = EditorErrors.uncertain(EditorErrorSurface.MessageReply)

    private fun uncertainReplyRepository(): PrivateMessageWriteRepository {
        val repository = mockk<PrivateMessageWriteRepository>()
        coEvery { repository.fetchReplyForm(any(), any()) } returns form()
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        return repository
    }

    private suspend fun seedUncertainAttempt(): EditorDraftStore.AttemptToken {
        val key = EditorDraftKey.mpReply(request.threadId)
        val attempt = (draftStore.beginAttempt("tester", key,
            EditorDraftStore.Draft(body = "uncertain body", isPrivate = true), 0)
            as EditorDraftStore.BeginAttemptResult.Started).token
        draftStore.finishAttempt(attempt, EditorDraftStore.AttemptOutcome.UNCERTAIN)
        return attempt
    }

    /** Drain the restore, form GET, acknowledgement and debounced autosave with their snapshot mirror. */
    private fun TestScope.settle() {
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
    }

    /** The production host wiring: [chromeActions] carries the explicit Masquer acknowledgement port. */
    private fun PrivateMessageReplyViewModel.chromeAdapter() = PrivateMessageReplyChromeAdapter(
        { state.value }, bodyFieldState, editorEvents, chromeActions(), {}, {}, chromeMemory,
    )

    private fun PrivateMessageReplyViewModel.assertNoSendingEvent() =
        assertTrue(editorEvents.state.events.none { it.content is EditorEventContent.InProgress })

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
            override val supportsIndependentDraftOffers = true
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
            override fun resolveOfferedDraft(restoring: Boolean) {
                if (restoring) viewModel.onDraftRestoreRequested() else viewModel.onDraftDiscardRequested()
            }
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
                    offeredBody = it.restorableDraft,
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
