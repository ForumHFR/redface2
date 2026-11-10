package fr.forumhfr.redface2.feature.messages

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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class PrivateMessageComposeRestorationTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PrivateMessageWriteRepository>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val store = VersionedEditorDraftStore()
    private val editors = mutableListOf<PrivateMessageComposeViewModel>()
    private val key = EditorDraftKey.mpCompose()
    private val recipients = EditorRecipientState(listOf("alice", "Bob with spaces"), "bo")
    private var served = form()
    private var formGate: CompletableDeferred<ReplyForm>? = null
    private var failure: Exception? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.observeConfirmBeforePosting() } returns flowOf(true)
        every { preferences.observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
        every { preferences.observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        coEvery { repository.fetchComposeForm(any()) } coAnswers {
            failure?.let { throw it }
            formGate?.await() ?: served
        }
    }
    @After fun tearDown() {
        editors.forEach { it.viewModelScope.cancel() }
        store.saveReturnGate?.complete(Unit)
        settle()
        Dispatchers.resetMain()
    }

    @Test fun retainedRotationAndProcessDeathPreserveHeadersBodySelectionAndScroll() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = open(saved)
        settle()
        first.onSubjectChanged("private subject")
        first.onRecipientStateChanged(recipients)
        type(first, "old private body")
        type(first, "private body".repeat(2_000), TextRange(19, 3))
        first.bodyScrollState.scrollTo(80)
        first.onTogglePreview()
        val body = first.bodyFieldState.text.toString()
        assertNotEquals(body, first.state.value.bodyText)
        first.onStopped()
        mainImmediateTurn()
        assertEquals(body, store.saved[key]?.body)
        assertNotEquals(body, first.state.value.bodyText)
        val frozen = freeze(saved)
        first.onStarted()
        assertEquals(TextRange(19, 3), first.bodyFieldState.selection)
        assertTrue(first.bodyFieldState.undoState.canUndo)
        assertTrue(first.state.value.isPreviewVisible)
        first.viewModelScope.cancel()
        val restored = open(frozen, initial = "route-default")
        settle()
        assertResumed(restored, body)
        assertEquals(recipients, restored.state.value.recipientState)
        assertEquals("private subject", restored.state.value.subject)
        assertEquals(TextRange(19, 3), restored.bodyFieldState.selection)
        restored.restoreBodyScroll()
        assertEquals(80, restored.bodyScrollState.value)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        assertFalse(restored.state.value.isPreviewVisible)
        assertNull(restored.state.value.armToken)
        assertEquals(privateMessageComposeCheckpointKeys, frozen.keys())
    }

    @Test fun automaticHeaderLatchSurvivesParcelAndDoesNotReopenEvenWithoutARecipient() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = open(saved)
        settle()
        fun adapter(vm: PrivateMessageComposeViewModel) = PrivateMessageComposeChromeAdapter(
            { vm.state.value }, vm.bodyFieldState, vm.editorEvents, vm.chromeActions(), {}, {}, vm.chromeMemory)
        val initial = adapter(first)
        initial.openInitialHeader()
        assertEquals(PrivateMessageComposeChromeSurface.Header, initial.surface)
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        val recreated = adapter(open(frozen))
        settle()
        recreated.openInitialHeader()
        assertNull(recreated.surface)
        assertNull(recreated.detail)
    }

    @Test fun bundleAloneHasNoContentAndNewerOrChangedHeadersRequireAnOffer() = runTest(dispatcher) {
        val saved = checkpoint()
        val entry = checkNotNull(store.entries.remove(key))
        val empty = open(freeze(saved))
        settle()
        assertEquals("", empty.bodyFieldState.text.toString())
        assertNull(empty.state.value.restorableDraft)
        val variants = listOf(entry.copy(version = entry.version + 1),
            entry.copy(draft = entry.draft.copy(subject = "changed subject")),
            entry.copy(draft = entry.draft.copy(recipients = recipients.copy(chips = listOf("other")).encode())),
            entry.copy(draft = entry.draft.copy(recipients = recipients.copy(buffer = "different").encode())))
        variants.forEach { changed ->
            store.entries[key] = changed
            val offered = open(freeze(saved))
            settle()
            assertEquals("saved body", offered.state.value.restorableDraft)
            offered.onStopped()
            settle()
            assertEquals(changed, store.entries[key])
            offered.onDraftRestoreRequested()
            assertResumed(offered)
            offered.viewModelScope.cancel()
        }
    }

    @Test fun ownerReadProtectsRecreationFromEarlyBodyAndHeaderInput() = runTest(dispatcher) {
        val saved = checkpoint()
        store.loadGate = CompletableDeferred()
        val editor = open(saved, initial = "route-default")
        settle()
        type(editor, "too early")
        editor.onSubjectChanged("too early")
        editor.onRecipientsChanged("too early")
        assertTrue(editor.state.value.isInitializing)
        assertEquals("", editor.bodyFieldState.text.toString())
        assertEquals("", editor.state.value.subject)
        store.loadGate?.complete(Unit)
        settle()
        assertResumed(editor)
        assertEquals(recipients, editor.state.value.recipientState)
    }

    @Test fun twoDeathsBeforeGetPreserveResumedBlankAndHeadersDespiteFailureAndRoute() = runTest(dispatcher) {
        // checkpoint() writes an envelope: its separate D4 buffer must survive both deaths.
        val saved = checkpoint(body = "")
        formGate = CompletableDeferred()
        val first = open(saved, initial = "route-default")
        settle()
        assertResumed(first, "")
        assertFalse(first.state.value.formAvailable)
        first.onStopped()
        mainImmediateTurn()
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        failure = IOException("offline")
        val second = open(frozen, initial = "route-default")
        settle()
        assertResumed(second, "")
        assertTrue(second.state.value.formError)
        assertEquals(recipients, second.state.value.recipientState)
        second.onStopped()
        mainImmediateTurn()
        failure = null
        formGate = null
        second.retryFormLoad()
        settle()
        assertResumed(second, "")
        assertEquals("private subject", second.state.value.subject)
        assertEquals(recipients, second.state.value.recipientState)
        assertTrue(second.state.value.formAvailable)
    }

    @Test fun explicitEditsAndClearsDefeatLateNavigationAndGetDefaults() = runTest(dispatcher) {
        store.loadGate = CompletableDeferred()
        val editor = open(initial = "route-default")
        settle()
        editor.onRecipientsChanged("typed")
        editor.onRecipientsChanged("")
        editor.onSubjectChanged("typed")
        editor.onSubjectChanged("")
        type(editor, "typed")
        type(editor, "")
        formGate = CompletableDeferred()
        store.loadGate?.complete(Unit)
        settle()
        assertEquals("", editor.state.value.recipients)
        formGate?.complete(form(dest = "get-default", subject = "get subject", body = "get body"))
        settle()
        assertResumed(editor, "")
        assertEquals("", editor.state.value.subject)
        assertEquals(EditorRecipientState(), editor.state.value.recipientState)
    }

    @Test fun resumedClearedRecipientsDefeatInitialRecipientAndLateGetDefaults() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = open(saved, initial = "alice")
        settle()
        assertEquals(EditorRecipientState(listOf("alice")), first.state.value.recipientState)
        first.onSubjectChanged("private subject")
        type(first, "saved body")
        first.onRecipientsChanged("")
        first.onStopped()
        mainImmediateTurn()
        val frozen = freeze(saved)
        first.viewModelScope.cancel()
        formGate = CompletableDeferred()
        val restored = open(frozen, initial = "route-default")
        settle()
        assertResumed(restored)
        assertEquals(EditorRecipientState(), restored.state.value.recipientState)
        assertEquals("", restored.state.value.recipients)
        formGate?.complete(form(dest = "get-default"))
        settle()
        assertTrue(restored.state.value.formAvailable)
        assertEquals(EditorRecipientState(), restored.state.value.recipientState)
        assertEquals("", restored.state.value.recipients)
        assertFalse(restored.state.value.canSubmit)
    }

    @Test fun legacyCsvAdapterPreservesEmptySegmentsAcrossDeathAndPost() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        for (csv in listOf("alice,", "alice,,bob", ",alice")) {
            store.entries.clear()
            val saved = SavedStateHandle()
            val first = open(saved)
            settle()
            first.onSubjectChanged("private subject")
            first.onRecipientsChanged(csv)
            type(first, "saved body")
            first.onStopped()
            mainImmediateTurn()
            val frozen = freeze(saved)
            first.viewModelScope.cancel()
            val restored = open(frozen)
            settle()
            assertResumed(restored)
            assertEquals(EditorRecipientState(csv.split(',')), restored.state.value.recipientState)
            assertEquals(csv, restored.state.value.recipients)
            restored.onSubmit()
            settle()
            assertEquals(EditorErrors.antiFlood, restored.state.value.submitError)
            coVerify(exactly = 1) {
                repository.submitNewMessage(any(), csv, "private subject", "saved body", any())
            }
            restored.viewModelScope.cancel()
        }
    }

    @Test fun emptyCsvSegmentsAloneNeverEnableSubmit() = runTest(dispatcher) {
        val editor = open()
        settle()
        editor.onSubjectChanged("private subject")
        type(editor, "saved body")
        settle()
        for (csv in listOf(",", ", ,")) {
            editor.onRecipientsChanged(csv)
            assertFalse(editor.state.value.canSubmit)
            editor.onSubmit()
            assertNull(editor.state.value.armToken)
        }
        assertEquals(0, store.beginCalls)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun failedAndCancelledReadsDoNotCertifyUnknownBodyOrClaimFormLoaded() = runTest(dispatcher) {
        for (error in listOf(IOException("offline"), CancellationException("cancelled"))) {
            failure = error
            val saved = SavedStateHandle()
            val editor = open(saved)
            settle()
            editor.onSubjectChanged("header alone")
            editor.onStopped()
            mainImmediateTurn()
            assertTrue(store.entries.isEmpty())
            assertFalse(saved.keys().any { it.endsWith("resumeVersion") })
            assertFalse(editor.state.value.formAvailable)
            assertEquals(error is IOException, editor.state.value.formError)
            assertEquals(error is CancellationException, editor.state.value.isLoadingForm)
            val read = editor.editorEvents.state.currentOperations.single {
                (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad
            }
            assertEquals(error is IOException, read.finished)
            assertTrue(editor.editorEvents.state.events.isEmpty())
            editor.viewModelScope.cancel()
        }
    }

    @Test fun headerEditsWaitForBodyProofThenPersistImmediatelyWhileStopped() = runTest(dispatcher) {
        formGate = CompletableDeferred()
        val editor = open()
        settle()
        editor.onSubjectChanged("private subject")
        editor.onRecipientStateChanged(recipients)
        editor.onStopped()
        mainImmediateTurn()
        assertTrue(store.entries.isEmpty())
        formGate?.complete(served)
        settle()
        assertEquals("private subject", store.saved[key]?.subject)
        assertEquals(recipients, EditorRecipientState.decode(store.saved[key]?.recipients))
        assertTrue(editor.state.value.formAvailable)
        assertTrue(editor.editorEvents.state.currentOperations.single {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad
        }.finished)
    }

    @Test fun cancellationOfARetryLeavesItsReadOperationUnfinished() = runTest(dispatcher) {
        val editor = open()
        settle()
        failure = CancellationException("cancelled")
        editor.retryFormLoad()
        settle()
        val read = editor.editorEvents.state.currentOperations.single {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad
        }
        assertFalse(read.finished)
        assertFalse(editor.state.value.formError)
        assertTrue(editor.state.value.isLoadingForm)
    }

    @Test fun failedReadFinishesItsOperationAndRetriesKeepSubmissionErrors() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        val editor = open(initial = "alice")
        settle()
        editor.onSubjectChanged("private subject")
        type(editor, "saved body")
        editor.onSubmit()
        settle()
        val events = editor.editorEvents.state.events
        assertEquals(EditorEventContent.Problem(EditorErrors.antiFlood), events.last().content)
        failure = IOException("offline")
        editor.retryFormLoad()
        settle()
        assertTrue(editor.state.value.formError)
        assertFalse(editor.state.value.formAvailable)
        assertEquals(events, editor.editorEvents.state.events)
        val failedRead = editor.editorEvents.state.currentOperations.single {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad
        }
        assertTrue(failedRead.finished)
        failure = null
        editor.retryFormLoad()
        settle()
        assertTrue(editor.state.value.formAvailable)
        assertEquals(EditorErrors.antiFlood, editor.state.value.submitError)
        assertEquals(events, editor.editorEvents.state.events)
        assertTrue(editor.editorEvents.state.currentOperations.single {
            (it.ref as? OperationRef.Local)?.kind == EditorLocalOperation.FormLoad
        }.finished)
    }

    @Test fun legacyCsvRestoresEveryRecipientAsAChipAndAllowsArming() = runTest(dispatcher) {
        store.preload(key, Draft("saved body", "private subject", "alice, bo", isPrivate = true))
        val editor = open()
        settle()
        assertEquals("saved body", editor.state.value.restorableDraft)
        editor.onDraftRestoreRequested()
        settle()
        assertEquals("saved body", editor.bodyFieldState.text.toString())
        assertEquals("private subject", editor.state.value.subject)
        assertEquals(EditorRecipientState(listOf("alice", "bo")), editor.state.value.recipientState)
        assertTrue(editor.state.value.canSubmit)
        editor.onSubmit()
        assertNotNull(editor.state.value.armToken)
        assertEquals(0, store.beginCalls)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun envelopeRestoresAnUncommittedTailAndBlocksBothSendPaths() = runTest(dispatcher) {
        store.preload(key, Draft("saved body", "private subject", recipients.encode(), isPrivate = true))
        val editor = open()
        settle()
        editor.onDraftRestoreRequested()
        settle()
        assertEquals(recipients, editor.state.value.recipientState)
        assertFalse(editor.state.value.canSubmit)
        editor.onSubmit()
        editor.onSubmitConfirmed(null)
        assertNull(editor.state.value.armToken)
        assertEquals(0, store.beginCalls)
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun durableStatusWinsAndArmingNeverClearsRestoredUncertainty() = runTest(dispatcher) {
        val saved = checkpoint()
        val entry = checkNotNull(store.entries[key])
        val warning = EditorErrors.uncertain(EditorErrorSurface.MessageCompose)
        AttemptStatus.entries.forEach { status ->
            val token = AttemptToken("tester", key, "attempt", entry.version)
            store.entries[key] = entry.copy(attempt = Attempt(token, status))
            val editor = open(freeze(saved))
            settle()
            assertNull(editor.state.value.restorableDraft)
            if (status == AttemptStatus.PUBLISHED) {
                assertEquals("", editor.bodyFieldState.text.toString())
                assertEquals("", editor.state.value.subject)
                assertNull(editor.state.value.submitError)
            } else {
                assertResumed(editor)
                editor.onRecipientStateChanged(recipients.copy(buffer = ""))
                val events = editor.editorEvents.state.events
                editor.onSubmit()
                assertNotNull(editor.state.value.armToken)
                editor.onSubmitConfirmationDismissed()
                assertTrue(editor.state.value.submission is EditorSubmissionState.Uncertain)
                assertEquals(warning, editor.state.value.submitError)
                assertEquals(events, editor.editorEvents.state.events)
            }
            editor.viewModelScope.cancel()
        }
    }

    @Test fun twoIdenticalLocalAndCertainServerRefusalsAreSeparateEvents() = runTest(dispatcher) {
        var anonymous = false
        served = spyk(form())
        every { served.isAnonymous } answers { anonymous }
        val editor = open(initial = "alice")
        settle()
        editor.onSubjectChanged("subject")
        type(editor, "body")
        anonymous = true
        val local = List(2) {
            editor.onSubmit()
            assertNull(editor.state.value.armToken)
            assertEquals(EditorErrors.loginRequired, editor.state.value.submitError)
            editor.editorEvents.state.events.last().key
        }
        assertNotEquals(local.first(), local.last())
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        anonymous = false
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        val server = List(2) {
            editor.onSubmit()
            assertNotNull(editor.state.value.armToken)
            // ED-164 rejects confirmation before 400 ms; keep the real confirmation path.
            dispatcher.scheduler.advanceTimeBy(500)
            editor.onSubmitConfirmed(editor.state.value.armToken)
            settle()
            assertEquals(EditorErrors.antiFlood, editor.state.value.submitError)
            assertNull(store.entries[key]?.attempt)
            editor.editorEvents.state.events.last().also {
                assertEquals(EditorEventContent.Problem(EditorErrors.antiFlood), it.content)
            }.key
        }
        assertNotEquals(server.first().operation, server.last().operation)
        coVerify(exactly = 2) { repository.submitNewMessage(any(), "alice", "subject", "body", any()) }
    }

    @Test fun ignoredOfferSavesOnlyABoundedFingerprintOfAllPrivateFields() = runTest(dispatcher) {
        val draft = Draft("saved body", "private subject", recipients.encode(), isPrivate = true)
        store.preload(key, draft)
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        editor.onDraftDiscardRequested()
        settle()
        val frozen = freeze(saved)
        assertEquals(draft.restoreOfferFingerprint(),
            frozen.get<String>("messageComposeEditor.ignoredOfferFingerprint"))
        assertNull(editor.state.value.restorableDraft)
    }

    @Test fun accountChangeRevokesFormLateCallbacksEventsAndResumeMetadata() = runTest(dispatcher) {
        val saved = checkpoint()
        formGate = CompletableDeferred()
        val editor = open(saved)
        settle()
        editor.onStopped()
        store.activeOwner = "other"
        auth.value = AuthState.Authenticated("other")
        settle()
        assertFalse(editor.state.value.isLoadingForm)
        assertFalse(editor.state.value.formAvailable)
        assertTrue(editor.editorEvents.state.currentOperations.isEmpty())
        formGate?.complete(served)
        editor.retryFormLoad()
        type(editor, "foreign edit")
        editor.onStopped()
        settle()
        assertFalse(editor.state.value.isLoadingForm)
        assertFalse(editor.state.value.formAvailable)
        assertFalse(editor.state.value.canSubmit)
        assertTrue(editor.editorEvents.state.events.isEmpty())
        assertFalse(saved.keys().any { it.endsWith("resumeVersion") })
        coVerify(exactly = 2) { repository.fetchComposeForm(any()) }
        val foreign = open(freeze(saved))
        settle()
        assertEquals("", foreign.bodyFieldState.text.toString())
        assertNull(foreign.state.value.restorableDraft)
    }

    private suspend fun checkpoint(body: String = "saved body"): SavedStateHandle {
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        editor.onSubjectChanged("private subject")
        editor.onRecipientStateChanged(recipients)
        type(editor, body)
        editor.bodyScrollState.scrollTo(80)
        editor.onStopped()
        mainImmediateTurn()
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        return frozen
    }
    private fun type(editor: PrivateMessageComposeViewModel, body: String, range: TextRange = TextRange(body.length)) {
        editor.bodyFieldState.edit {
            replace(0, length, body)
            selection = range
            with(editor.bodyInputTransformation) { transformInput() }
        }
    }
    private fun settle() {
        Snapshot.sendApplyNotifications()
        dispatcher.scheduler.runCurrent()
    }
    /** Match Main.immediate's cancelled debounce completion, without advancing time or notifying the body mirror. */
    private fun mainImmediateTurn() = dispatcher.scheduler.runCurrent()
    private fun assertResumed(editor: PrivateMessageComposeViewModel, body: String = "saved body") {
        assertFalse(editor.state.value.isInitializing)
        assertEquals(body, editor.bodyFieldState.text.toString())
        assertNull(editor.state.value.restorableDraft)
    }
    @Suppress("DEPRECATION") // Real Parcel round-trip, with a closed schema before and after serialization.
    private fun freeze(saved: SavedStateHandle): SavedStateHandle {
        val fragments = listOf("private subject", "private body", "old private body", "saved body",
            "alice", "Bob with spaces", "route-default", "get-default", "secret-hash")
        assertPrivateMessageComposeSavedState(saved, fragments)
        val bundle = Bundle()
        for (key in saved.keys()) {
            when (val value = saved.get<Any?>(key)) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                else -> error("Unexpected metadata: $key")
            }
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
            SavedStateHandle(restored.keySet().associateWith { restored.get(it) }).also {
                assertPrivateMessageComposeSavedState(it, fragments)
            }
        } finally { parcel.recycle() }
    }
    private fun open(saved: SavedStateHandle = SavedStateHandle(), initial: String? = null) =
        PrivateMessageComposeViewModel(
            initialRecipient = initial, repository = repository,
            previewParser = { PostContent(emptyList()) }, defaultDispatcher = dispatcher,
            userPreferencesRepository = preferences, draftStore = store,
            authRepository = mockk<AuthRepository> { every { observeAuthState() } returns auth },
            uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
            diagnostics = DiagnosticsLog(), clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
            smileyRepository = mockk(relaxed = true), hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
            smileyLocalCollections = FakeSmileyLocalCollections(), savedStateHandle = saved,
        ).also(editors::add)
    private fun form(dest: String = "", subject: String = "", body: String = "") = ReplyForm(
        hashCheck = "secret-hash", sujet = "", initialContent = body,
        hiddenFields = mapOf("dest" to dest, "sujet" to subject), isAnonymous = false,
    )
}
