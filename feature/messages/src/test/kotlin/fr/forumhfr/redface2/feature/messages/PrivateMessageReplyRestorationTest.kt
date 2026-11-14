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
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorPreparationException
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
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

/** ED-81/170: only parcelled metadata survives; the owner-bound row supplies all private content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class PrivateMessageReplyRestorationTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PrivateMessageWriteRepository>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val store = VersionedEditorDraftStore()
    private val editors = mutableListOf<PrivateMessageReplyViewModel>()
    private val request = PrivateMessageReplyRequest(threadId = 3195237)
    private val key = EditorDraftKey.mpReply(request.threadId)
    private val warning = EditorErrors.uncertain(EditorErrorSurface.MessageReply)
    private var served = form()
    private var formGate: CompletableDeferred<ReplyForm>? = null
    private var fetchFailure: IOException? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.observeConfirmBeforePosting() } returns flowOf(true)
        every { preferences.observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
        every { preferences.observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        every { preferences.observeEditorShortcut() } returns flowOf(EditorShortcut.NONE)
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers {
            fetchFailure?.let { throw it }
            formGate?.await() ?: served
        }
    }
    @After fun tearDown() {
        editors.forEach { it.viewModelScope.cancel() }
        store.saveReturnGate?.complete(Unit)
        settle()
        Dispatchers.resetMain()
    }

    @Test fun settingsStopThenLifecycleStopSerializeWritesAndPreserveConfirmationOnReturn() = runTest(dispatcher) {
        val editor = open()
        settle()
        type(editor, "before settings")
        settle()
        editor.onSubmit(timeoutMillis = 60_000L)
        val arm = editor.state.value.armToken
        assertNotNull(arm)
        val writes = store.saveCount
        val version = store.entries[key]?.version ?: 0L
        store.saveReturnGate = CompletableDeferred()

        editor.onStopped() // Explicit stop before opening settings.
        settle()
        editor.onStopped() // Activity goes to the background from settings.
        settle()
        assertEquals(writes + 1, store.saveCount)
        assertEquals(version + 1, store.entries[key]?.version)
        store.saveReturnGate?.complete(Unit)
        settle()
        assertEquals(writes + 2, store.saveCount)
        assertEquals(version + 2, store.entries[key]?.version)
        assertEquals("before settings", store.saved[key]?.body)
        assertNull(editor.state.value.submitError)
        assertEquals(arm, editor.state.value.armToken)

        editor.onStarted()
        assertEquals(arm, editor.state.value.armToken)
        type(editor, "after returning")
        settle()
        dispatcher.scheduler.advanceTimeBy(751)
        settle()
        assertEquals("after returning", store.saved[key]?.body)
        assertNull(editor.state.value.submitError)
    }

    @Test fun rotationRetainsLiveStateAndDeathResumesTheImmediateStopCheckpoint() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = open(saved)
        settle()
        val body = "private reply\n".repeat(2_000)
        val previousBody = "previous private revision\n".repeat(2_000)
        type(first, previousBody)
        type(first, body, TextRange(19, 3))
        first.bodyScrollState.scrollTo(80)
        first.onTogglePreview()
        first.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        first.onSubmit()
        val arm = first.state.value.armToken
        assertNotNull(arm)
        assertNotEquals(body, first.state.value.bodyText)
        first.onStopped()
        val frozen = freeze(saved)
        first.onStarted()
        assertEquals(body, store.saved[key]?.body)
        assertEquals(arm, first.state.value.armToken)
        assertEquals(80, first.bodyScrollState.value)
        assertTrue(first.bodyFieldState.undoState.canUndo)
        assertTrue(first.state.value.isPreviewVisible)
        assertTrue(first.smileyPicker.state.value is SmileyPickerState.Open)
        first.viewModelScope.cancel()
        val restored = open(frozen)
        settle()
        assertResumed(restored, body)
        assertEquals(TextRange(19, 3), restored.bodyFieldState.selection)
        restored.restoreBodyScroll()
        assertEquals(80, restored.bodyScrollState.value)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        assertFalse(restored.state.value.isPreviewVisible)
        assertNull(restored.state.value.armToken)
        assertEquals(SmileyPickerState.Hidden, restored.smileyPicker.state.value)
        assertEquals(privateMessageReplyCheckpointKeys, frozen.keys())
        assertPrivateMessageReplySavedState(
            frozen,
            listOf(body, "private reply", previousBody, "previous private revision", served.sujet,
                served.initialContent, "private seed", "alice", served.hashCheck),
        )
    }

    @Test fun recreationWaitsForTheOwnerReadAndRejectsEarlyInput() = runTest(dispatcher) {
        val saved = checkpoint()
        store.loadGate = CompletableDeferred()
        val editor = open(saved)
        settle()
        type(editor, "too early")
        assertTrue(editor.state.value.isInitializing)
        assertFalse(editor.state.value.formAvailable)
        assertEquals("", editor.bodyFieldState.text.toString())
        store.loadGate?.complete(Unit)
        settle()
        assertResumed(editor)
    }

    @Test fun bundleAloneCannotRestoreContentAndANewerRowRemainsAnOffer() = runTest(dispatcher) {
        val saved = checkpoint()
        val entry = checkNotNull(store.entries.remove(key))
        val empty = open(freeze(saved))
        settle()
        assertEquals(served.initialContent, empty.bodyFieldState.text.toString())
        assertNull(empty.state.value.restorableDraft)
        store.entries[key] = entry.copy(version = entry.version + 1, draft = Draft("newer", isPrivate = true))
        val newer = open(freeze(saved))
        settle()
        assertEquals("newer", newer.state.value.restorableDraft)
        newer.onStopped()
        settle()
        assertEquals("newer", store.saved[key]?.body)
        newer.onDraftRestoreRequested()
        // v0.7.8 ED-89: a reply has no replaceable seed; D comes first and the field content follows.
        assertResumed(newer, "newer\n\n" + served.initialContent)
    }

    @Test fun twoDeathsBeforeSuccessfulGetKeepResumedTextOrDeliberateBlankAndScroll() = runTest(dispatcher) {
        for (body in listOf("saved body", "")) {
            val saved = checkpoint(body)
            formGate = CompletableDeferred()
            val first = open(saved)
            settle()
            assertResumed(first, body)
            assertFalse(first.state.value.formAvailable)
            first.onStopped()
            val frozen = freeze(saved)
            first.viewModelScope.cancel()
            fetchFailure = IOException("offline")
            val second = open(frozen)
            settle()
            assertResumed(second, body)
            assertTrue(second.state.value.formError)
            second.onStopped()
            fetchFailure = null
            formGate = null
            second.retryFormLoad()
            settle()
            assertResumed(second, body)
            second.restoreBodyScroll()
            assertEquals(80, second.bodyScrollState.value)
            assertTrue(second.state.value.formAvailable)
            second.viewModelScope.cancel()
        }
    }

    @Test fun failedGetDoesNotCertifyAnUnknownBlankOrLoseAnUndecidedOffer() = runTest(dispatcher) {
        fetchFailure = IOException("offline")
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        editor.onStopped()
        assertTrue(store.entries.isEmpty())
        assertTrue(saved.keys().none { it.endsWith("resumeVersion") })
        store.preload(key, Draft("draft", isPrivate = true))
        val offered = open(freeze(saved))
        settle()
        offered.onStopped()
        settle()
        assertEquals("draft", offered.state.value.restorableDraft)
        assertEquals("draft", store.saved[key]?.body)
        offered.onDraftRestoreRequested()
        fetchFailure = null
        offered.retryFormLoad()
        settle()
        assertResumed(offered, "draft")
    }

    @Test fun failedRestoreThenAutosaveConsumesMergedDraftBeforeProcessDeath() = runTest(dispatcher) {
        served = form().copy(initialContent = "")
        store.preload(key, Draft("D", isPrivate = true))
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        type(editor, "C")
        store.saveFailure = IOException("disk full")
        editor.onDraftRestoreRequested()
        settle()
        assertEquals("D", editor.state.value.restorableDraft)
        // v0.7.8 ED-89 case 4, empty seed: D, then the typing.
        assertEquals("D\n\nC", editor.bodyFieldState.text.toString())
        store.saveFailure = null
        type(editor, "D\n\nC continued")
        settle()
        dispatcher.scheduler.advanceTimeBy(751)
        settle()
        assertEquals("D\n\nC continued", store.saved[key]?.body)
        assertTrue(store.loadOffers("tester", key).isEmpty())
        editor.onStopped()
        settle()
        val frozen = freeze(saved)
        assertPrivateMessageReplySavedState(frozen, listOf("D\n\nC continued"))
        editor.viewModelScope.cancel()
        val recreated = open(frozen)
        settle()
        assertResumed(recreated, "D\n\nC continued")
        assertTrue(store.loadOffers("tester", key).isEmpty())
    }

    @Test fun canHydrateRejectsALateGetAfterEditingAndClearingAnUncertifiedAttempt() = runTest(dispatcher) {
        val token = AttemptToken("tester", key, "attempt", 1)
        store.entries[key] = DraftEntry(Draft("", isPrivate = true), 1, Attempt(token, AttemptStatus.IN_PROGRESS))
        formGate = CompletableDeferred()
        val editor = open()
        settle()
        editor.onStopped()
        assertEquals(0, store.saveCount)
        type(editor, "local edit")
        type(editor, "")
        formGate?.complete(served)
        settle()
        assertResumed(editor, "")
        assertEquals("", editor.state.value.draftHydratedContent)
        assertEquals(warning, editor.state.value.submitError)
        assertTrue(editor.state.value.formAvailable)
    }

    @Test fun durableAttemptsOverrideResumeAndPublicationIsNeverOffered() = runTest(dispatcher) {
        val saved = checkpoint()
        val entry = checkNotNull(store.entries[key])
        for (status in AttemptStatus.entries) {
            val token = AttemptToken("tester", key, "attempt", entry.version)
            store.entries[key] = entry.copy(attempt = Attempt(token, status))
            val editor = open(freeze(saved))
            settle()
            assertNull(editor.state.value.restorableDraft)
            if (status == AttemptStatus.PUBLISHED) {
                assertEquals(served.initialContent, editor.bodyFieldState.text.toString())
                assertNull(editor.state.value.submitError)
            } else {
                assertResumed(editor)
                val submission = editor.state.value.submission
                assertTrue(submission is EditorSubmissionState.Uncertain)
                val events = editor.editorEvents.state.events
                editor.onSubmit(1_000)
                assertNotNull(editor.state.value.armToken)
                assertEquals(warning, editor.state.value.submitError)
                editor.onSubmitConfirmationDismissed()
                editor.onSubmit(1_000)
                dispatcher.scheduler.advanceTimeBy(1_000)
                settle()
                assertEquals(submission, editor.state.value.submission)
                assertEquals(warning, editor.state.value.submitError)
                assertEquals(events, editor.editorEvents.state.events)
            }
            editor.viewModelScope.cancel()
        }
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test fun identicalLocalRefusalsDeliverTwoEventsWithoutPostingOrArming() = runTest(dispatcher) {
        // Exercise the local anonymous guard after the initial authenticated GET was accepted.
        var anonymous = false
        served = spyk(form())
        every { served.isAnonymous } answers { anonymous }
        val editor = open()
        settle()
        anonymous = true
        val keys = List(2) {
            editor.onSubmit()
            assertEquals(EditorErrors.loginRequired, editor.state.value.submitError)
            assertNull(editor.state.value.armToken)
            editor.editorEvents.state.events.last().key
        }
        assertNotEquals(keys.first(), keys.last())
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test fun identicalCertainServerRefusalsDeliverDistinctEvents() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        val editor = open()
        settle()
        type(editor, "private reply")
        settle()
        val keys = List(2) {
            editor.onSubmit()
            settle()
            assertEquals(EditorErrors.antiFlood, editor.state.value.submitError)
            assertFalse(editor.state.value.isSubmitting)
            assertNull(editor.state.value.armToken)
            assertNull(store.entries[key]?.attempt)
            editor.editorEvents.state.events.last().also { event ->
                assertEquals(EditorEventContent.Problem(EditorErrors.antiFlood), event.content)
            }.key
        }
        // A new send retires its predecessor's banner; compare the two deliveries, not queue retention.
        assertNotEquals(keys.first(), keys.last())
        assertNotEquals(keys.first().operation, keys.last().operation)
        coVerify(exactly = 2) { repository.submitReply(any(), any(), "private reply", any(), any()) }
    }

    @Test fun ignoredOfferPersistsOnlyItsBoundedFingerprint() = runTest(dispatcher) {
        val body = "private ignored draft"
        store.preload(key, Draft(body, isPrivate = true))
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        assertEquals(body, editor.state.value.restorableDraft)
        editor.onDraftDiscardRequested()
        settle()
        assertNull(editor.state.value.restorableDraft)
        val frozen = freeze(saved)
        assertTrue("draft_restore_offer_fingerprint" in frozen.keys())
        assertEquals(Draft(body, isPrivate = true).restoreOfferFingerprint(),
            frozen.get<String>("draft_restore_offer_fingerprint"))
        assertPrivateMessageReplySavedState(frozen, listOf(body, "private ignored", served.initialContent))
    }

    @Test fun successfulReadKeepsSendEventsAndFailedReadsStayFullScreen() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } throws
            EditorPreparationException(EditorPreparationException.Reason.INVALID_FORM)
        val editor = open()
        settle()
        editor.onSubmit()
        settle()
        val problem = EditorEventContent.Problem(EditorErrors.invalidForm)
        assertTrue(editor.editorEvents.state.events.any { it.content == problem })
        fetchFailure = IOException("offline")
        editor.retryFormLoad()
        settle()
        assertTrue(editor.editorEvents.state.events.any { it.content == problem })
        fetchFailure = null
        editor.retryFormLoad()
        settle()
        assertEquals(EditorErrors.invalidForm, editor.state.value.submitError)
        assertTrue(editor.editorEvents.state.events.any { it.content == problem })
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        editor.onSubmit()
        settle()
        val events = editor.editorEvents.state.events
        editor.retryFormLoad()
        settle()
        assertEquals(EditorErrors.antiFlood, editor.state.value.submitError)
        assertEquals(events, editor.editorEvents.state.events)
    }

    @Test fun accountChangeRevokesAnAlreadyAvailableForm() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        assertTrue(editor.state.value.formAvailable)
        editor.onStopped()
        assertTrue("messageReplyEditor.resumeVersion" in saved.keys())
        store.activeOwner = "other"
        auth.value = AuthState.Authenticated("other")
        settle()
        assertFalse(editor.state.value.formAvailable)
        assertFalse(editor.state.value.canSubmit)
        assertFalse("messageReplyEditor.resumeVersion" in saved.keys())
        editor.onSubmit()
        settle()
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test fun accountChangeInvalidatesLateCallbacksEventsAndCheckpoint() = runTest(dispatcher) {
        val saved = checkpoint()
        formGate = CompletableDeferred()
        val editor = open(saved)
        settle()
        editor.onStopped()
        store.activeOwner = "other"
        auth.value = AuthState.Authenticated("other")
        settle()
        formGate?.complete(served)
        type(editor, "foreign edit")
        editor.onStopped()
        settle()
        assertEquals("saved body", editor.bodyFieldState.text.toString())
        assertFalse(editor.state.value.formAvailable)
        assertTrue(editor.editorEvents.state.events.isEmpty())
        assertTrue(store.entries.isEmpty())
        assertTrue(saved.keys().none { it.endsWith("resumeVersion") })
        val foreign = open(freeze(saved))
        settle()
        assertNull(foreign.state.value.restorableDraft)
        assertEquals(served.initialContent, foreign.bodyFieldState.text.toString())
    }

    private suspend fun checkpoint(body: String = "saved body"): SavedStateHandle {
        store.entries.clear()
        val saved = SavedStateHandle()
        val editor = open(saved)
        settle()
        type(editor, body)
        editor.bodyScrollState.scrollTo(80)
        editor.onStopped()
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        return frozen
    }
    private fun type(editor: PrivateMessageReplyViewModel, body: String, range: TextRange = TextRange(body.length)) {
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
    private fun assertResumed(editor: PrivateMessageReplyViewModel, body: String = "saved body") {
        assertFalse(editor.state.value.isInitializing)
        assertEquals(body, editor.bodyFieldState.text.toString())
        assertNull(editor.state.value.restorableDraft)
    }
    @Suppress("DEPRECATION") // Actual Parcel round-trip of primitives, never TextFieldState or private content.
    private fun freeze(saved: SavedStateHandle): SavedStateHandle {
        // An empty form seed cannot identify leaked content; the assertion requires nonempty fragments.
        assertPrivateMessageReplySavedState(
            saved, listOf(served.sujet, served.initialContent, "private seed", "alice", served.hashCheck)
                .filter(String::isNotEmpty),
        )
        val bundle = Bundle()
        for (key in saved.keys()) {
            when (val value = saved.get<Any?>(key)) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                null -> bundle.putString(key, null)
                else -> error("Unexpected metadata: ${value::class}")
            }
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
            SavedStateHandle(restored.keySet().associateWith { restored.get(it) })
        } finally { parcel.recycle() }
    }
    private fun open(saved: SavedStateHandle = SavedStateHandle()) = PrivateMessageReplyViewModel(
        request = request, repository = repository, previewParser = mockk(relaxed = true),
        defaultDispatcher = dispatcher, userPreferencesRepository = preferences, draftStore = store,
        authRepository = mockk<AuthRepository> { every { observeAuthState() } returns auth },
        uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
        diagnostics = DiagnosticsLog(), clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
        smileyRepository = mockk(relaxed = true), hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
        smileyLocalCollections = FakeSmileyLocalCollections(), savedStateHandle = saved,
    ).also(editors::add)
    private fun form() = ReplyForm(
        hashCheck = "hash", sujet = "private subject", initialContent = "[quote=alice]private seed[/quote]\n",
        hiddenFields = emptyMap(), isAnonymous = false,
    )
}
