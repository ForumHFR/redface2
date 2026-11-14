package fr.forumhfr.redface2.feature.editor

import android.os.Bundle
import android.os.Parcel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorBodyProvenance
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ED-81/84/86: freeze actual parcelled metadata on either side of the durable boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class PostEditorRestorationTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = GatedStore()
    private val replies = mockk<ReplyRepository>()
    private val edits = mockk<EditPostRepository>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val auth = mockk<AuthRepository>()
    private val uploads = mockk<UploadRepository>()
    private val reader = mockk<ImageUploadReader>()
    private val account = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val editors = mutableListOf<PostEditorViewModel>()
    private val key = EditorDraftKey.reply(23, 35421)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.observeAuthState() } returns account
        every { preferences.observeConfirmBeforePosting() } returns flowOf(true)
        every { preferences.observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
        every { preferences.observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        every { preferences.observeEditorShortcut() } returns flowOf(EditorShortcut.NONE)
        coEvery { replies.fetchReplyForm(any()) } returns form("")
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } returns form("server body")
    }

    @After
    fun tearDown() {
        editors.forEach { it.viewModelScope.cancel() }
        store.beforeSave?.complete(Unit)
        store.beforeSecondSave?.complete(Unit)
        store.saveReturnGate?.complete(Unit)
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun settingsStopThenLifecycleStopSerializeWritesAndPreserveConfirmationOnReturn() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, "before settings")
        sync()
        editor.submit(PostEditorIntent.SubmitClicked(timeoutMillis = 60_000L))
        val arm = editor.state.value.armToken
        assertNotNull(arm)
        val writes = store.saveCount
        val version = store.entries[key]?.version ?: 0L
        store.saveReturnGate = CompletableDeferred()

        editor.submit(PostEditorIntent.Stopped) // Explicit stop before opening settings.
        runCurrent()
        editor.submit(PostEditorIntent.Stopped) // Activity goes to the background from settings.
        runCurrent()
        assertEquals(writes + 1, store.saveCount) // Second write waits for the first result to be adopted.
        assertEquals(version + 1, store.entries[key]?.version)
        store.saveReturnGate?.complete(Unit)
        sync()
        assertEquals(writes + 2, store.saveCount)
        assertEquals(version + 2, store.entries[key]?.version)
        assertEquals("before settings", store.saved[key]?.body)
        assertNull(editor.state.value.submitError)
        assertEquals(arm, editor.state.value.armToken)

        editor.submit(PostEditorIntent.Started)
        assertEquals(arm, editor.state.value.armToken)
        type(editor, "after returning")
        sync()
        advanceTimeBy(751)
        sync()
        assertEquals("after returning", store.saved[key]?.body)
        assertNull(editor.state.value.submitError)
    }

    @Test
    fun freshQuoteOnlyOpeningDiscardsTheRowBeforeAnotherDeathAndKeepsDOffered() = runTest(dispatcher) {
        store.preload(key, Draft("D", updatedAt = 1234))
        val previous = EditorDraftSession(store, key)
        previous.load()
        previous.enqueueInlineQuoteRequest(pending())
        previous.consumeInlineQuoteRequest(pending(), Draft(QUOTE), deliveredBody = QUOTE)
        val offers = store.loadOffers("tester", key)
        val saved = SavedStateHandle()
        val fresh = open(saved)
        runCurrent()
        assertEquals("", fresh.bodyFieldState.text.toString())
        assertEquals("D", fresh.state.value.restorableDraft)
        assertEquals("", store.saved[key]?.body)
        assertNull(store.saved[key]?.bodyProvenance)
        val frozen = freeze(saved) // No ON_STOP or autosave after this new opening.
        fresh.viewModelScope.cancel()
        val afterDeath = open(frozen)
        runCurrent()
        assertEquals("", afterDeath.bodyFieldState.text.toString())
        assertEquals("D", afterDeath.state.value.restorableDraft)
        assertTrue(afterDeath.state.value.pendingQuotes.isEmpty())
        assertEquals(offers, store.loadOffers("tester", key))
    }

    @Test
    fun explicitSharedHandoffKeepsQuoteOnlyCInsteadOfDiscardingIt() = runTest(dispatcher) {
        store.preload(key, Draft(QUOTE, bodyProvenance = EditorBodyProvenance().delivered(pending().scope, QUOTE)))
        val shared = open(request = request().copy(resumeSharedDraft = true))
        runCurrent()
        assertResumed(shared, QUOTE)
        assertEquals(QUOTE, store.saved[key]?.body)
        assertTrue(checkNotNull(store.saved[key]?.bodyProvenance).isQuoteOnly(QUOTE))
    }

    @Test
    fun initializationWaitsForOwnerReadBeforeFieldOrNetwork() = runTest(dispatcher) {
        store.loadGate = CompletableDeferred()
        val editor = open()
        runCurrent()
        assertTrue(editor.state.value.isInitializing)
        assertFalse(editor.state.value.canSubmit)
        type(editor, "too early")
        editor.submit(PostEditorIntent.ImagePicked("content://early"))
        assertEquals("", editor.bodyFieldState.text.toString())
        coVerify(exactly = 0) { replies.fetchReplyForm(any()) }
        coVerify(exactly = 0) { reader.read(any()) }
        store.loadGate?.complete(Unit)
        runCurrent()
        assertFalse(editor.state.value.isInitializing)
        coVerify(exactly = 1) { replies.fetchReplyForm(any()) }
    }

    @Test
    fun stopCapturesLiveTypingBeforeDebounceAndRestoresOnlyDurableContentAndViewport() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        val body = "private body ".repeat(2_000)
        type(first, body, TextRange(19, 3))
        first.bodyScrollState.scrollTo(80)
        assertEquals("", first.state.value.bodyText) // Deliberately leave the mirror behind.
        first.submit(PostEditorIntent.TogglePreview)
        first.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        first.submit(PostEditorIntent.SubmitClicked())
        val arm = first.state.value.armToken
        assertNotNull(arm)
        first.submit(PostEditorIntent.Stopped)
        assertEquals(arm, first.state.value.armToken)
        val frozen = freeze(saved)
        assertEquals(body, store.saved[key]?.body)
        first.viewModelScope.cancel()
        coEvery { replies.fetchReplyForm(any()) } returns form("late server prefill")
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, body)
        assertEquals(TextRange(19, 3), restored.bodyFieldState.selection)
        restored.restoreBodyScroll()
        assertEquals(80, restored.bodyScrollState.value)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        assertFalse(restored.state.value.isPreviewVisible)
        assertNull(restored.state.value.armToken)
        assertEquals(SmileyPickerState.Hidden, restored.smileyPicker.state.value)
        assertTrue(frozen.keys().all { it in METADATA_KEYS })
        assertTrue(frozen.keys().none { frozen.get<Any?>(it) == body })
    }

    @Test
    fun bundleBeforeCommitReferencesThePendingCheckpoint() = runTest(dispatcher) {
        store.beforeSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "last keystroke")
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        assertNull(store.saved[key])
        store.beforeSave?.complete(Unit)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "last keystroke")
        assertEquals(1L, store.entries[key]?.version) // No delayed mirror autosave after ON_STOP.
    }

    @Test
    fun bundleAfterCommitBeforeDispatcherReturnAlsoResumes() = runTest(dispatcher) {
        store.saveReturnGate = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "committed but not returned")
        first.submit(PostEditorIntent.Stopped)
        assertEquals("committed but not returned", store.saved[key]?.body)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        store.saveReturnGate?.complete(Unit)
        runCurrent()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "committed but not returned")
    }

    @Test
    fun stopAccountsForAnAutosaveWhoseCommitResultIsStillInFlight() = runTest(dispatcher) {
        store.saveReturnGate = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "before last key")
        sync()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(1L, store.entries[key]?.version)
        type(first, "after last key")
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        store.saveReturnGate?.complete(Unit)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "after last key")
        assertEquals(2L, store.entries[key]?.version)
    }

    @Test
    fun successiveStopsReserveTheLatestCheckpointWhileTheFirstWriteIsInFlight() = runTest(dispatcher) {
        store.beforeSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        for (body in listOf("first", "second", "third")) {
            first.submit(PostEditorIntent.Started)
            type(first, body)
            first.submit(PostEditorIntent.Stopped)
        }
        val frozen = freeze(saved)
        store.beforeSave?.complete(Unit)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "third")
        assertEquals(3L, store.entries[key]?.version)
    }

    @Test
    fun identicalStopsKeepTheLatestReservationWhenAnOlderQueuedWriteStarts() = runTest(dispatcher) {
        store.beforeSave = CompletableDeferred()
        store.beforeSecondSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        for (stop in 1..3) {
            first.submit(PostEditorIntent.Started)
            type(first, "identical body", TextRange(stop + 3, stop))
            first.bodyScrollState.scrollTo(stop * 20)
            first.submit(PostEditorIntent.Stopped)
        }
        store.beforeSave?.complete(Unit)
        runCurrent()
        assertEquals(1L, store.entries[key]?.version) // v2 announced; v3 reserved but not started.
        val frozen = freeze(saved)
        store.beforeSecondSave?.complete(Unit)
        runCurrent()
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "identical body")
        assertEquals(TextRange(6, 3), restored.bodyFieldState.selection)
        restored.restoreBodyScroll()
        assertEquals(60, restored.bodyScrollState.value)
        assertEquals(3L, store.entries[key]?.version)
    }

    @Test
    fun failedFlushKeepsTheOrdinaryOfferAndDoesNotRecordSuccess() = runTest(dispatcher) {
        store.preload(key, Draft("older durable body"))
        store.beforeSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "unsaved body")
        store.saveFailure = IOException("disk full")
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        store.beforeSave?.complete(Unit)
        runCurrent()
        assertEquals(EditorErrors.draftSaveFailed, first.state.value.submitError)
        assertFalse(saved.contains("postEditor.resumeVersion"))
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertEquals("older durable body", restored.state.value.restorableDraft)
        assertEquals("", restored.bodyFieldState.text.toString())
    }

    @Test
    fun stopWithEmptyLiveBodyProtectsTheUndecidedOfferAndItsRequest() = runTest(dispatcher) {
        store.preload(key, Draft("offered body", pendingInlineQuoteRequest = pending()))
        val first = open()
        runCurrent()
        first.submit(PostEditorIntent.Stopped)
        runCurrent()
        assertEquals("offered body", first.state.value.restorableDraft)
        assertEquals("offered body", store.saved[key]?.body)
        assertEquals(pending(), store.saved[key]?.pendingInlineQuoteRequest)
        assertEquals(0, store.saveCount)
        // ED-132 (v0.7.8): the quote is fetched at once, without waiting for the offer decision; its
        // form also carries the ordinary metadata, so no separate ordinary GET is made.
        coVerify(exactly = 1) { replies.fetchReplyForm(match { it.isQuote }) }
        coVerify(exactly = 0) { replies.fetchReplyForm(match { !it.isQuote }) }
    }

    @Test
    fun competingCasAtThePredictedVersionCannotResumeAnotherBodyOrSelection() = runTest(dispatcher) {
        store.beforeSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "my pending body", TextRange(5, 1))
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        store.entries[key] = DraftEntry(Draft("concurrent body"), 1)
        store.beforeSave?.complete(Unit)
        runCurrent()
        assertFalse(saved.contains("postEditor.resumeVersion"))
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertEquals("concurrent body", restored.state.value.restorableDraft)
        assertEquals(TextRange.Zero, restored.bodyFieldState.selection)
        assertEquals("concurrent body", store.saved[key]?.body)
    }

    @Test
    fun missingRowCannotRecreateTheTextFromTheBundle() = runTest(dispatcher) {
        val frozen = checkpoint("lost row")
        store.entries.remove(key)
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "")
        assertEquals(TextRange.Zero, restored.bodyFieldState.selection)
    }

    @Test
    fun differentVersionOfTheSameBodyRemainsAnOrdinaryOffer() = runTest(dispatcher) {
        val frozen = checkpoint("same body")
        store.entries[key] = checkNotNull(store.entries[key]).copy(version = 99)
        val restored = open(frozen)
        runCurrent()
        assertEquals("same body", restored.state.value.restorableDraft)
        assertEquals("", restored.bodyFieldState.text.toString())
    }

    @Test
    fun checkpointForAnotherEditorKeyDoesNotAuthorizeSilentRestoration() = runTest(dispatcher) {
        val frozen = checkpoint("same body")
        store.entries[EditorDraftKey.reply(23, 35422)] = checkNotNull(store.entries[key])
        val restored = open(frozen, request().copy(topicId = 35422))
        runCurrent()
        assertEquals("same body", restored.state.value.restorableDraft)
        assertEquals("", restored.bodyFieldState.text.toString())
    }

    @Test
    fun failedInitialEditGetDoesNotCheckpointUnknownBodyOrUnlockAfterDeath() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val saved = SavedStateHandle()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } throws IOException("offline")
        val first = open(saved, request)
        runCurrent()
        assertEquals(EditorErrors.formNetwork, first.state.value.submitError)
        assertTrue(first.state.value.isAwaitingInitialBody)
        type(first, "replacement without seeing the original")
        assertEquals("", first.bodyFieldState.text.toString())
        first.submit(PostEditorIntent.Stopped)
        assertFalse(saved.contains("postEditor.resumeVersion"))
        runCurrent()
        assertFalse(saved.contains("postEditor.resumeVersion"))
        assertNull(store.entries[EditorDraftKey.editPost(23, 101)])
        assertEquals(0, store.saveCount)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()

        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } coAnswers { formGate.await() }
        val restored = open(frozen, request)
        runCurrent()
        assertTrue(restored.state.value.isLoadingForm)
        assertTrue(restored.state.value.isAwaitingInitialBody)
        assertFalse(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.state.value.canSubmit)
        type(restored, "replacement during GET")
        assertEquals("", restored.bodyFieldState.text.toString())
        assertFalse(frozen.contains("postEditor.resumeVersion"))
        formGate.complete(form("server body"))
        runCurrent()
        assertResumed(restored, "server body")
        assertTrue(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.state.value.isAwaitingInitialBody)
        type(restored, "intentional replacement after hydration")
        assertEquals("intentional replacement after hydration", restored.bodyFieldState.text.toString())
        coVerify(exactly = 2) { edits.fetchEditPostForm(any<EditPostContext>()) }
    }

    @Test
    fun clearedUncertainEditUnlocksAfterDeathOnlyWhenFormGetSucceeds() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val editKey = EditorDraftKey.editPost(23, 101)
        val attempted = Draft("possibly sent body")
        store.preload(editKey, attempted)
        val begun = store.beginAttempt("tester", editKey, attempted, 0) as BeginAttemptResult.Started
        store.finishAttempt(begun.token, AttemptOutcome.UNCERTAIN)
        val saved = SavedStateHandle()
        val first = open(saved, request)
        runCurrent()
        assertResumed(first, attempted.body)
        assertFalse(first.state.value.isAwaitingInitialBody)
        type(first, "")
        first.submit(PostEditorIntent.Stopped)
        runCurrent()
        val unresolved = checkNotNull(store.entries[editKey])
        assertEquals("", unresolved.draft.body)
        assertEquals(AttemptStatus.UNCERTAIN, unresolved.attempt?.status)
        assertEquals(begun.token, unresolved.attempt?.token) // The flush really used saveUnresolved.
        assertTrue(unresolved.version > begun.token.version)
        val frozen = freeze(saved)
        val checkpoint = checkNotNull(frozen.get<String>("postEditor.resumeVersion"))
        first.viewModelScope.cancel()

        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } throws IOException("offline")
        val restored = open(frozen, request)
        runCurrent()
        assertTrue(restored.state.value.isAwaitingInitialBody)
        assertFalse(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.state.value.canSubmit)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        val saves = store.saveCount
        type(restored, "replacement before successful GET")
        restored.submit(PostEditorIntent.Stopped)
        runCurrent()
        assertEquals("", restored.bodyFieldState.text.toString())
        assertEquals(saves, store.saveCount) // #1574: the failed GET cannot certify a new blank checkpoint.
        assertEquals(checkpoint, frozen.get<String>("postEditor.resumeVersion"))

        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } returns form("server body")
        restored.submit(PostEditorIntent.ReloadRequested)
        runCurrent()
        assertResumed(restored, "server body")
        assertTrue(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.state.value.isAwaitingInitialBody)
        assertTrue(restored.state.value.canSubmit)
        type(restored, "replacement after successful GET")
        assertEquals("replacement after successful GET", restored.bodyFieldState.text.toString())
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertTrue(restored.state.value.submission is EditorSubmissionState.Uncertain)
        coVerify(exactly = 3) { edits.fetchEditPostForm(any<EditPostContext>()) }
    }

    @Test
    fun closeFlushAfterFailedInitialEditGetDoesNotWriteOrCertifyUnknownBody() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val saved = SavedStateHandle()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } throws IOException("offline")
        val editor = open(saved, request)
        runCurrent()
        assertTrue(editor.state.value.isAwaitingInitialBody)
        editor.submit(PostEditorIntent.CloseRequested)
        runCurrent()
        assertEquals(0, store.saveCount)
        assertNull(store.entries[EditorDraftKey.editPost(23, 101)])
        assertFalse(saved.contains("postEditor.resumeVersion"))
        assertTrue(editor.state.value.isAwaitingInitialBody)
    }

    @Test
    fun discardFlushAfterFailedInitialEditGetCannotCertifyTheUnknownBody() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val editKey = EditorDraftKey.editPost(23, 101)
        store.preload(editKey, Draft("offered edit"))
        val saved = SavedStateHandle()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } throws IOException("offline")
        val first = open(saved, request)
        runCurrent()
        assertEquals("offered edit", first.state.value.restorableDraft)
        assertTrue(first.state.value.isAwaitingInitialBody)
        first.submit(PostEditorIntent.DraftDiscardRequested)
        runCurrent()
        assertNull(first.state.value.restorableDraft)
        assertEquals("", store.saved[editKey]?.body)
        assertFalse(saved.contains("postEditor.resumeVersion"))
        first.submit(PostEditorIntent.Stopped)
        runCurrent()
        assertFalse(saved.contains("postEditor.resumeVersion"))
        assertEquals(1, store.saveCount)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } returns form("server body")
        val restored = open(frozen, request)
        runCurrent()
        assertResumed(restored, "server body")
        assertFalse(restored.state.value.isAwaitingInitialBody)
    }

    @Test
    fun editBodyClearedAfterHydrationResumesEmptyBeforeAndAfterTheNextGet() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val saved = SavedStateHandle()
        val first = open(saved, request)
        runCurrent()
        assertEquals("server body", first.bodyFieldState.text.toString())
        assertFalse(first.state.value.isAwaitingInitialBody)
        type(first, "")
        first.submit(PostEditorIntent.Stopped)
        assertTrue(saved.contains("postEditor.resumeVersion"))
        assertEquals("", store.saved[EditorDraftKey.editPost(23, 101)]?.body)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()

        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { edits.fetchEditPostForm(any<EditPostContext>()) } coAnswers { formGate.await() }
        val restored = open(frozen, request)
        runCurrent()
        assertResumed(restored, "")
        assertTrue(restored.state.value.isLoadingForm)
        assertTrue(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.state.value.isAwaitingInitialBody)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        formGate.complete(form("server body must not reappear"))
        runCurrent()
        assertResumed(restored, "")
        assertFalse(restored.state.value.isAwaitingInitialBody)
    }

    @Test
    fun replyRemainsEditableAndResumesAfterFailedInitialGet() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        coEvery { replies.fetchReplyForm(any()) } throws IOException("offline")
        val first = open(saved)
        runCurrent()
        assertEquals(EditorErrors.formNetwork, first.state.value.submitError)
        assertFalse(first.state.value.isAwaitingInitialBody)
        type(first, "reply while offline")
        assertEquals("reply while offline", first.bodyFieldState.text.toString())
        first.submit(PostEditorIntent.Stopped)
        assertTrue(saved.contains("postEditor.resumeVersion"))
        val frozen = freeze(saved)
        first.viewModelScope.cancel()

        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { replies.fetchReplyForm(any()) } coAnswers { formGate.await() }
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "reply while offline")
        assertTrue(restored.state.value.isLoadingForm)
        assertFalse(restored.state.value.isAwaitingInitialBody)
        type(restored, "reply edited during GET")
        assertEquals("reply edited during GET", restored.bodyFieldState.text.toString())
        formGate.complete(form("late prefill"))
        runCurrent()
        assertResumed(restored, "reply edited during GET")
        assertFalse(restored.state.value.isAwaitingInitialBody)
    }

    @Test
    fun blankCheckpointAndBoundedSelectionAreNotOverwrittenByEditHydration() = runTest(dispatcher) {
        val request = request().copy(mode = PostEditorMode.Edit, numreponse = 101)
        val saved = SavedStateHandle()
        val first = open(saved, request)
        runCurrent()
        assertEquals("server body", first.bodyFieldState.text.toString())
        type(first, "")
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        frozen["postEditor.selectionStart"] = 100
        frozen["postEditor.selectionEnd"] = -1
        first.viewModelScope.cancel()
        val restored = open(frozen, request)
        runCurrent()
        assertResumed(restored, "")
        assertEquals(TextRange.Zero, restored.bodyFieldState.selection)
        assertTrue(restored.state.value.draftHydratedFromForm)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
    }

    @Test
    fun offeredDraftSheetClosesThroughTheRealAccountSessionChange() = runTest(dispatcher) {
        store.preload(key, Draft("offered body", pendingInlineQuoteRequest = pending()))
        val editor = open()
        runCurrent()
        val events = editor.chromeEvents
        val opening = events.state.session
        val adapter = PostEditorChromeAdapter({ editor.state.value }, editor.bodyFieldState, events,
            editor::submit, {}, {}, editor.chromeMemory)
        adapter.openDraftOffer()
        assertTrue(adapter.draftOfferOpen)

        store.activeOwner = null
        account.value = AuthState.Anonymous
        runCurrent()

        assertFalse(opening == events.state.session)
        assertNull(events.state.session.owner)
        assertNull(adapter.detail)
        assertNull(adapter.surface)
        assertFalse(adapter.draftOfferOpen)
        assertTrue(events.state.events.isEmpty())
    }

    @Test
    fun accountBDoesNotInheritAccountAsCheckpointEvenWithIdenticalVersionAndBody() = runTest(dispatcher) {
        val frozen = checkpoint("same content")
        val entryA = checkNotNull(store.entries[key])
        store.activeOwner = "other"
        account.value = AuthState.Authenticated("other")
        store.entries[key] = entryA
        val restored = open(frozen)
        runCurrent()
        assertEquals("same content", restored.state.value.restorableDraft)
        assertEquals("", restored.bodyFieldState.text.toString())
    }

    @Test
    fun accountSwitchDuringFlushInvalidatesTheCheckpointAndCannotWriteUnderB() = runTest(dispatcher) {
        store.beforeSave = CompletableDeferred()
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "account A only")
        first.submit(PostEditorIntent.Stopped)
        store.activeOwner = "other"
        account.value = AuthState.Authenticated("other")
        runCurrent()
        store.beforeSave?.complete(Unit)
        runCurrent()
        assertFalse(saved.contains("postEditor.resumeVersion"))
        assertNull(store.saved[key])
        assertEquals(0, store.saveCount)
    }

    @Test
    fun sharedDraftIsReadAgainAfterDeathAndOldRouteCardsAreDropped() = runTest(dispatcher) {
        store.preload(key, Draft("from the sheet"))
        val request = request().copy(resumeSharedDraft = true, initialQuotes = listOf(card()))
        val saved = SavedStateHandle()
        val first = open(saved, request)
        runCurrent()
        assertResumed(first, "from the sheet")
        assertEquals(1, first.state.value.quotes.size)
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        val restored = open(frozen, request)
        runCurrent()
        assertResumed(restored, "from the sheet")
        assertTrue(restored.state.value.quotes.isEmpty())
    }

    @Test
    fun oldBundleCannotResurrectPublishedContentEvenBeforeErasure() = runTest(dispatcher) {
        val frozen = checkpoint("sent body")
        // ED-81/121: this is the checkpointed live C, not an independently offered D.
        val begun = store.beginAttempt("tester", key, Draft("sent body", protectCurrent = false),
            checkNotNull(store.entries[key]).version)
        store.finishAttempt((begun as BeginAttemptResult.Started).token, AttemptOutcome.PUBLISHED)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "")
        assertEquals("sent body", store.saved[key]?.body) // Publication marker alone is sufficient.
    }

    @Test
    fun unresolvedAttemptWinsOverOldCheckpointAndKeepsItsQuoteDormant() = runTest(dispatcher) {
        val frozen = checkpoint("old body")
        val begun = store.beginAttempt(
            // Same interrupted C: a raw test write must not reclassify its old checkpoint as D.
            "tester", key, Draft("possibly sent body", pendingInlineQuoteRequest = pending(), protectCurrent = false),
            checkNotNull(store.entries[key]).version,
        )
        assertTrue(begun is BeginAttemptResult.Started)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "possibly sent body")
        assertTrue(restored.state.value.submission is EditorSubmissionState.Uncertain)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertTrue(restored.state.value.hasDormantQuote)
        assertFalse(restored.state.value.isLoadingForm)
        coVerify(exactly = 1) { replies.fetchReplyForm(any()) } // Only the original opening fetched.
    }

    @Test
    fun deathBeforeQuoteTransactionRereadsAndDeliversTheDurableRequestOnce() = runTest(dispatcher) {
        store.enqueueInlineQuoteRequest("tester", key, pending(), 0)
        val formGate = CompletableDeferred<ReplyForm>()
        coEvery { replies.fetchReplyForm(any()) } coAnswers { formGate.await() }
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        type(first, "my reply")
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        coEvery { replies.fetchReplyForm(any()) } returns form(QUOTE)
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, QUOTE + "\n\nmy reply")
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        assertEquals(1, Regex("\\[quotemsg=").findAll(restored.bodyFieldState.text).count())
    }

    @Test
    fun deathAfterQuoteCommitBeforeReturnNeverReplaysTheRemovedRequest() = runTest(dispatcher) {
        store.enqueueInlineQuoteRequest("tester", key, pending(), 0)
        store.saveReturnGate = CompletableDeferred()
        coEvery { replies.fetchReplyForm(any()) } returns form(QUOTE)
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        assertNull(store.entries[key]?.draft?.pendingInlineQuoteRequest)
        val delivered = checkNotNull(store.entries[key]).draft.body
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        store.saveReturnGate?.complete(Unit)
        runCurrent()
        coEvery { replies.fetchReplyForm(any()) } returns form("")
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, delivered)
        type(restored, "quote deliberately removed")
        restored.submit(PostEditorIntent.Stopped)
        val afterRemoval = freeze(frozen)
        restored.viewModelScope.cancel()
        val again = open(afterRemoval)
        runCurrent()
        assertResumed(again, "quote deliberately removed")
        assertTrue(again.state.value.pendingQuotes.isEmpty())
    }

    @Test
    fun rotationKeepsOneUploadButDeathRestoresOnlyImagesAlreadyInserted() = runTest(dispatcher) {
        val secondUpload = CompletableDeferred<UploadedImage>()
        coEvery { reader.read(any()) } returns ImageUpload(byteArrayOf(1), "image/png", "image.png")
        var calls = 0
        coEvery { uploads.uploadWithCurrentProvider(any(), any()) } coAnswers {
            if (++calls == 1) UploadedImage(UploadProviderId.DIBERIE, "https://h/first.png", null, null, null, null)
            else secondUpload.await()
        }
        val saved = SavedStateHandle()
        val first = open(saved)
        runCurrent()
        first.submit(PostEditorIntent.ImagesPicked(listOf("content://one", "content://two")))
        runCurrent()
        assertTrue(first.state.value.isUploading)
        assertEquals(1, first.state.value.uploadProgress?.completed)
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        first.submit(PostEditorIntent.Started) // Reattach the same ViewModel after rotation.
        runCurrent()
        assertTrue(first.state.value.isUploading)
        assertEquals(2, calls)
        first.viewModelScope.cancel()
        val restored = open(frozen)
        runCurrent()
        assertResumed(restored, "[img]https://h/first.png[/img]")
        assertFalse(restored.state.value.isUploading)
        assertNull(restored.state.value.uploadProgress)
        assertNull(restored.state.value.uploadError)
        assertEquals(2, calls)
    }

    @Test
    fun privateEditLoadsItsOwnOverloadAndRestoresBodyAndSubjectAfterProcessDeath() = runTest(dispatcher) {
        coEvery { edits.fetchEditPostForm(any<PrivateMessageEditContext>()) } returns
            form("private server body").copy(isSubjectEditable = true, msgIcon = "3")
        val saved = SavedStateHandle()
        val editor = open(saved, privateRequest())
        runCurrent()
        assertNull(editor.state.value.cat)
        assertNull(editor.state.value.subcat)
        assertTrue(editor.state.value.isSubjectEditable)
        assertTrue(editor.state.value.hasMessageTone)
        assertEquals(3, editor.state.value.msgIcon)
        editor.submit(PostEditorIntent.SubjectChanged("private edited subject"))
        type(editor, "private edited body", TextRange(3))
        sync()
        editor.submit(PostEditorIntent.Stopped)
        runCurrent()
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        val restored = open(frozen, privateRequest())
        runCurrent()
        assertEquals("private edited body", restored.bodyFieldState.text.toString())
        assertEquals(TextRange(3), restored.bodyFieldState.selection)
        assertEquals("private edited subject", restored.state.value.subject)
        assertNull(restored.state.value.restorableDraft)
        assertEquals("private edited body", store.saved[EditorDraftKey.mpEdit(101)]?.body)
        coVerify(exactly = 0) { edits.fetchEditPostForm(any<EditPostContext>()) }
        coVerify(exactly = 0) { edits.submitEditPost(any<PrivateMessageEditContext>(), any(), any(), any()) }
    }

    @Test
    fun privateEditOffersDraftAndMergesAgainstTheServerSeedOnExplicitRestore() = runTest(dispatcher) {
        val mpKey = EditorDraftKey.mpEdit(101)
        store.preload(mpKey, Draft("offered private draft"))
        coEvery { edits.fetchEditPostForm(any<PrivateMessageEditContext>()) } returns form("server seed")
        val editor = open(request = privateRequest())
        runCurrent()
        assertEquals("server seed", editor.bodyFieldState.text.toString())
        assertEquals("offered private draft", editor.state.value.restorableDraft)
        type(editor, "server seed plus typing")
        sync()
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        runCurrent()
        assertEquals("offered private draft\n\nplus typing", editor.bodyFieldState.text.toString())
        assertNull(editor.state.value.restorableDraft)
    }

    @Test
    fun privateEditPersistsAttemptBeforeSendingAndNeverResendsAnUncertainAttempt() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { edits.fetchEditPostForm(any<PrivateMessageEditContext>()) } returns
            form("server body").copy(isSubjectEditable = true, msgIcon = null)
        var calls = 0
        coEvery { edits.submitEditPost(any<PrivateMessageEditContext>(), any(), any(), any()) } answers {
            calls++
            val durable = checkNotNull(store.entries[EditorDraftKey.mpEdit(101)])
            assertEquals("private body", durable.draft.body)
            assertEquals("changed subject", durable.draft.subject)
            assertEquals(AttemptStatus.IN_PROGRESS, durable.attempt?.status)
            assertEquals("changed subject", secondArg<ReplyForm>().sujet)
            assertNull(secondArg<ReplyForm>().msgIcon)
            ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        }
        val saved = SavedStateHandle()
        val editor = open(saved, privateRequest())
        runCurrent()
        assertFalse(editor.state.value.hasMessageTone)
        editor.submit(PostEditorIntent.SubjectChanged("changed subject"))
        type(editor, "private body")
        sync()
        store.beginGate = CompletableDeferred()
        editor.submit(PostEditorIntent.SubmitClicked())
        runCurrent()
        assertEquals(0, calls)
        store.beginGate?.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(EditorErrors.uncertain, editor.state.value.submitError)
        assertEquals(AttemptStatus.UNCERTAIN, store.entries[EditorDraftKey.mpEdit(101)]?.attempt?.status)
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        val restored = open(frozen, privateRequest())
        runCurrent()
        assertEquals("private body", restored.bodyFieldState.text.toString())
        assertEquals("changed subject", restored.state.value.subject)
        assertEquals(EditorErrors.uncertain, restored.state.value.submitError)
        assertEquals(1, calls)
        restored.submit(PostEditorIntent.ReloadRequested)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun privateEditSubmitsServedOptionsAndToneThroughThePrivateOverload() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { edits.fetchEditPostForm(any<PrivateMessageEditContext>()) } returns
            form("server body").copy(msgIcon = "1")
        coEvery { edits.submitEditPost(any<PrivateMessageEditContext>(), any(), any(), any()) } returns
            ReplySubmitResult.Success(null, 3, 101, 42)
        val editor = open(request = privateRequest())
        runCurrent()
        editor.submit(PostEditorIntent.ToggleSignature(true))
        editor.submit(PostEditorIntent.ToggleSmileyDisabled(true))
        editor.submit(PostEditorIntent.ToggleEmailNotification(true))
        editor.submit(PostEditorIntent.MsgIconSelected(7))
        val effect = async { editor.effects.first() }
        editor.submit(PostEditorIntent.SubmitClicked())
        runCurrent()
        coVerify(exactly = 1) {
            edits.submitEditPost(PrivateMessageEditContext(42, 3, 101), match { it.msgIcon == "7" }, "server body",
                fr.forumhfr.redface2.core.model.write.ReplyFormOptions(true, true, true))
        }
        assertEquals(fr.forumhfr.redface2.core.model.write.PostSubmitOperation.Edit,
            (effect.await() as PostEditorEffect.SubmitSucceeded).operation)
        coVerify(exactly = 0) { edits.submitEditPost(any<EditPostContext>(), any(), any(), any()) }
    }

    @Test
    fun privateEditCloseWaitsForThePrivateDraftWrite() = runTest(dispatcher) {
        coEvery { edits.fetchEditPostForm(any<PrivateMessageEditContext>()) } returns form("server body")
        val editor = open(request = privateRequest())
        runCurrent()
        type(editor, "last private edit")
        sync()
        store.beforeSave = CompletableDeferred()
        val close = async { editor.effects.first() }
        editor.submit(PostEditorIntent.CloseRequested)
        runCurrent()
        assertFalse(close.isCompleted)
        assertNull(store.saved[EditorDraftKey.mpEdit(101)])
        store.beforeSave?.complete(Unit)
        runCurrent()
        assertEquals("last private edit", store.saved[EditorDraftKey.mpEdit(101)]?.body)
        assertEquals(PostEditorEffect.CloseCommitted, close.await())
        assertFalse(editor.state.value.showUnsavedCloseDialog)
    }

    private fun privateRequest() = PostEditorRequest(
        mode = PostEditorMode.Edit, cat = null, topicId = null, numreponse = 101, page = 3, subcat = null,
        target = PostEditorTarget.PrivateMessage(42),
    )

    private fun checkpoint(body: String): SavedStateHandle {
        val saved = SavedStateHandle()
        val first = open(saved)
        dispatcher.scheduler.runCurrent()
        type(first, body)
        first.submit(PostEditorIntent.Stopped)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        return frozen
    }

    private fun type(editor: PostEditorViewModel, body: String, range: TextRange = TextRange(body.length)) {
        editor.bodyFieldState.edit {
            replace(0, length, body)
            selection = range
            with(editor.bodyInputTransformation) { transformInput() }
        }
    }

    private fun sync() {
        Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }

    private fun assertResumed(editor: PostEditorViewModel, body: String) {
        assertFalse(editor.state.value.isInitializing)
        assertEquals(body, editor.bodyFieldState.text.toString())
        assertNull(editor.state.value.restorableDraft)
        coVerify(exactly = 0) { replies.submitReply(any(), any(), any(), any()) }
        coVerify(exactly = 0) { edits.submitEditPost(any<EditPostContext>(), any(), any(), any()) }
    }

    @Suppress("DEPRECATION") // Bundle.get is sufficient for this deliberately primitive-only contract.
    private fun freeze(saved: SavedStateHandle): SavedStateHandle {
        val bundle = Bundle()
        for (key in saved.keys()) {
            when (val value = saved.get<Any?>(key)) {
                null -> bundle.putString(key, null)
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                else -> error("Non-metadata value at $key")
            }
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            assertTrue("The Bundle must not grow with the body or undo history", parcel.dataSize() < 2_048)
            parcel.setDataPosition(0)
            val frozen = checkNotNull(parcel.readBundle(javaClass.classLoader))
            SavedStateHandle(frozen.keySet().associateWith { frozen.get(it) })
        } finally {
            parcel.recycle()
        }
    }

    private fun open(saved: SavedStateHandle = SavedStateHandle(), request: PostEditorRequest = request()) =
        PostEditorViewModel(
            request = request, previewParser = mockk(relaxed = true), defaultDispatcher = dispatcher,
            replyRepository = replies, editPostRepository = edits, smileyRepository = mockk(relaxed = true),
            userPreferencesRepository = preferences, draftStore = store, diagnostics = DiagnosticsLog(),
            uploadRepository = uploads, imageUploadReader = reader, authRepository = auth,
            quoteMaterializer = TopicReplyQuoteMaterializer(replies),
            hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
            smileyLocalCollections = FakeSmileyLocalCollections(),
            clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }, savedStateHandle = saved,
        ).also(editors::add)

    private fun request() = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0)
    private fun form(body: String) = ReplyForm("hash", "topic", emptyMap(), false, body)
    private fun card() = QuoteSelection(QuoteLocator(1, 101, null), "author", "preview", false)
    private fun pending() = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))

    private class GatedStore : VersionedEditorDraftStore() {
        var beforeSave: CompletableDeferred<Unit>? = null
        var beforeSecondSave: CompletableDeferred<Unit>? = null
        override suspend fun saveIfVersion(
            owner: String?, key: String, draft: Draft, expectedVersion: Long,
        ): StoreWriteResult {
            beforeSave?.await()
            if (expectedVersion == 1L) beforeSecondSave?.await()
            return super.saveIfVersion(owner, key, draft, expectedVersion)
        }
    }

    private companion object {
        const val QUOTE = "[quotemsg=101,35421,1]quoted body[/quotemsg]"
        val METADATA_KEYS = setOf(
            "postEditor.resumeOwner", "postEditor.resumeKey", "postEditor.resumeVersion",
            "postEditor.selectionStart", "postEditor.selectionEnd", "postEditor.scroll",
            "draft_restore_offer_fingerprint", "postEditor.quoteFailures",
        )
    }
}
