package fr.forumhfr.redface2.feature.editor

import android.os.Bundle
import android.os.Parcel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.model.write.TopicPollForm
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
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

/** ED-81/86/170 and D3: every scenario runs against New AND EditFirstPost with parcelled metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
class TopicFormRestorationTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TopicFormRepository>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val auth = mockk<AuthRepository>()
    private val editors = mutableListOf<TopicFormViewModel>()
    private var store = VersionedEditorDraftStore()
    private var served = form()
    private var formGate: CompletableDeferred<TopicForm>? = null
    private var fetchFailure: IOException? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.observeAuthState() } returns MutableStateFlow(AuthState.Authenticated("tester"))
        every { preferences.observeConfirmBeforePosting() } returns flowOf(true)
        every { preferences.observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
        every { preferences.observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        every { preferences.observeEditorShortcut() } returns flowOf(EditorShortcut.NONE)
        coEvery { repository.fetchNewTopicForm(any()) } coAnswers { fetch() }
        coEvery { repository.fetchEditFirstPostForm(any()) } coAnswers { fetch() }
    }

    @After fun tearDown() {
        editors.forEach { it.viewModelScope.cancel() }
        store.saveReturnGate?.complete(Unit)
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun recreationWaitsForTheOwnerReadBeforeRestoringOrFetching() = bothModes { mode ->
        val saved = checkpoint(mode)
        store.loadGate = CompletableDeferred()
        val editor = open(mode, saved)
        settle()
        assertTrue(editor.state.value.isInitializing)
        assertTrue(editor.state.value.isRestoringSession)
        assertFalse(editor.state.value.hasLoadedForm)
        type(editor, "too early")
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("too early")))
        assertEquals("", editor.bodyFieldState.text.toString())
        assertEquals("", editor.state.value.subject.text)
        store.loadGate?.complete(Unit)
        settle()
        assertResumed(editor)
    }

    @Test fun freshOpenAcceptsTitleEditsDuringTheInitialDraftRead() = bothModes { mode ->
        store.loadGate = CompletableDeferred()
        val editor = open(mode)
        settle()
        assertTrue(editor.state.value.isInitializing)
        assertFalse(editor.state.value.isRestoringSession)
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("early title")))
        assertEquals("early title", editor.state.value.subject.text)
        store.loadGate?.complete(Unit)
        settle()
        assertFalse(editor.state.value.isRestoringSession)
        assertEquals("early title", editor.state.value.subject.text)
    }

    @Test fun rotationKeepsLiveStateAndStopFlushesBeforeTheBodyMirrorOrDebounce() = bothModes { mode ->
        val saved = SavedStateHandle()
        val first = open(mode, saved)
        settle()
        val body = "private body ".repeat(2_000)
        type(first, body, TextRange(19, 3))
        first.submit(TopicFormIntent.SubjectChanged(TextFieldValue("private title")))
        first.bodyScrollState.scrollTo(80)
        assertNotEquals(body, first.state.value.bodyText)
        first.submit(TopicFormIntent.TogglePreview)
        first.smileyPicker.open(SmileyPickerOpenIntent.CONSULTATION)
        first.submit(TopicFormIntent.SubmitClicked())
        val arm = first.state.value.armToken
        assertNotNull(arm)
        first.submit(TopicFormIntent.Stopped)
        mainImmediateTurn()
        val frozen = freeze(saved)
        first.submit(TopicFormIntent.Started) // Same VM is retained across rotation.
        assertEquals(arm, first.state.value.armToken)
        assertTrue(first.state.value.isPreviewVisible)
        assertTrue(first.smileyPicker.state.value is SmileyPickerState.Open)
        assertEquals(80, first.bodyScrollState.value)
        assertTrue(first.bodyFieldState.undoState.canUndo)
        assertEquals(body, store.saved[key(mode)]?.body)
        first.viewModelScope.cancel()
        val restored = open(mode, frozen)
        settle()
        assertResumed(restored, body, "private title")
        assertEquals(TextRange(19, 3), restored.bodyFieldState.selection)
        restored.restoreBodyScroll()
        assertEquals(80, restored.bodyScrollState.value)
        assertFalse(restored.bodyFieldState.undoState.canUndo)
        assertFalse(restored.state.value.isPreviewVisible)
        assertNull(restored.state.value.armToken)
        assertEquals(SmileyPickerState.Hidden, restored.smileyPicker.state.value)
        assertTrue(frozen.keys().all { frozen.get<Any?>(it) is String || frozen.get<Any?>(it) is Int })
        assertTrue(frozen.keys().none { frozen.get<Any?>(it) in listOf(body, "private title") })
    }

    @Test fun bundleAloneDoesNotRestoreContentOrTheSavedSubcategory() = bothModes { mode ->
        val saved = checkpoint(mode)
        store.entries.clear()
        val editor = open(mode, saved)
        settle()
        assertNull(editor.state.value.restorableDraft)
        val expected = if (mode == TopicFormMode.New) "" else served.initialContent
        assertEquals(expected, editor.bodyFieldState.text.toString())
        assertEquals(401, editor.state.value.selectedSubcat)
    }

    @Test fun newerRowIsOfferedAndRestoreDoesNotApplyTheCheckpointSubcategory() = bothModes { mode ->
        val saved = checkpoint(mode)
        val entry = checkNotNull(store.entries[key(mode)])
        store.entries[key(mode)] = entry.copy(version = entry.version + 1, draft = Draft("newer", "newer title"))
        val editor = open(mode, saved)
        settle()
        assertEquals("newer", editor.state.value.restorableDraft)
        assertEquals(401, editor.state.value.selectedSubcat)
        editor.submit(TopicFormIntent.DraftRestoreRequested)
        settle()
        assertEquals("newer", editor.bodyFieldState.text.toString())
        assertEquals(401, editor.state.value.selectedSubcat)
    }

    @Test fun twoDeathsBeforeFormLoadKeepThePendingSubcategory() = bothModes { mode ->
        val saved = checkpoint(mode)
        formGate = CompletableDeferred()
        val first = open(mode, saved)
        settle()
        assertResumed(first)
        assertTrue(first.state.value.isLoadingForm)
        assertFalse(first.state.value.canSubmit)
        first.submit(TopicFormIntent.Stopped)
        val secondBundle = freeze(saved)
        first.viewModelScope.cancel()
        val second = open(mode, secondBundle)
        settle()
        assertResumed(second)
        formGate?.complete(form())
        settle()
        assertEquals(402, second.state.value.selectedSubcat)
        served = form().copy(selectedSubcat = 403)
        second.submit(TopicFormIntent.ReloadRequested)
        settle()
        assertEquals(402, second.state.value.selectedSubcat)
    }

    @Test fun removedSubcategoryFallsBackToTheLoadedForm() = bothModes { mode ->
        val saved = checkpoint(mode)
        served = form().copy(subcategoryChoices = form().subcategoryChoices.filterNot { it.id == 402 })
        val editor = open(mode, saved)
        settle()
        assertResumed(editor)
        assertEquals(401, editor.state.value.selectedSubcat)
    }

    @Test fun slowGetCannotReplaceResumedFieldsEvenAfterTheyAreCleared() = bothModes { mode ->
        val saved = checkpoint(mode)
        formGate = CompletableDeferred()
        val editor = open(mode, saved)
        settle()
        type(editor, "")
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("")))
        editor.submit(TopicFormIntent.SubcatSelected(403))
        formGate?.complete(form())
        settle()
        assertEquals("", editor.bodyFieldState.text.toString())
        assertEquals("", editor.state.value.subject.text)
        assertEquals(403, editor.state.value.selectedSubcat)
        assertTrue(editor.state.value.hasLoadedForm)
    }

    @Test fun titleClearedDuringTheInitialGetRemainsBlank() = bothModes { mode ->
        formGate = CompletableDeferred()
        val editor = open(mode)
        settle()
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("typed")))
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("")))
        formGate?.complete(form())
        settle()
        assertEquals("", editor.state.value.subject.text)
    }

    @Test fun failedGetAllowsOfflineResumeButNeverProvesTheFormLoaded() = bothModes { mode ->
        val saved = checkpoint(mode)
        fetchFailure = IOException("offline")
        val editor = open(mode, saved)
        settle()
        assertResumed(editor)
        assertFalse(editor.state.value.hasLoadedForm)
        assertFalse(editor.state.value.canSubmit)
        assertEquals(EditorErrors.formNetwork, editor.state.value.submitError)
        fetchFailure = null
        editor.submit(TopicFormIntent.ReloadRequested)
        settle()
        assertTrue(editor.state.value.canSubmit)
        assertNull(editor.state.value.submitError)
        val readError = EditorEventContent.Problem(EditorErrors.formNetwork)
        assertTrue(editor.editorEvents.state.events.none { it.content == readError })
    }

    @Test fun failedInitialGetNeverSavesOrCertifiesAnUnknownFirstPost() = runTest(dispatcher) {
        fetchFailure = IOException("offline")
        val saved = SavedStateHandle()
        val editor = open(TopicFormMode.EditFirstPost, saved)
        settle()
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title while offline")))
        dispatcher.scheduler.advanceTimeBy(800)
        settle()
        editor.submit(TopicFormIntent.Stopped)
        assertTrue(editor.state.value.isAwaitingInitialBody)
        assertTrue(store.entries.isEmpty())
        assertTrue(saved.keys().none { it.endsWith("resumeVersion") })
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        fetchFailure = null
        val restored = open(TopicFormMode.EditFirstPost, frozen)
        settle()
        assertEquals("server body", restored.bodyFieldState.text.toString())
    }

    @Test fun titleOnlyOfflineOfferDoesNotConsumeFirstPostHydration() = bothModes { mode ->
        fetchFailure = IOException("offline")
        store.preload(key(mode), Draft("", "title only"))
        val editor = open(mode)
        settle()
        editor.submit(TopicFormIntent.DraftRestoreRequested)
        settle()
        assertEquals("title only", editor.state.value.subject.text)
        assertEquals(mode == TopicFormMode.EditFirstPost, editor.state.value.isAwaitingInitialBody)
        fetchFailure = null
        editor.submit(TopicFormIntent.ReloadRequested)
        settle()
        assertEquals(if (mode == TopicFormMode.New) "" else "server body", editor.bodyFieldState.text.toString())
        assertEquals("title only", editor.state.value.subject.text)
    }

    @Test fun unresolvedAttemptsReturnUncertainAndPublicationAlwaysWins() = bothModes { mode ->
        val saved = checkpoint(mode)
        val entry = checkNotNull(store.entries[key(mode)])
        val token = AttemptToken("tester", key(mode), "attempt", entry.version)
        for (status in AttemptStatus.entries) {
            store.entries[key(mode)] = entry.copy(attempt = Attempt(token, status))
            val editor = open(mode, freeze(saved))
            settle()
            assertNull(editor.state.value.restorableDraft)
            if (status == AttemptStatus.PUBLISHED) {
                assertNotEquals("saved body", editor.bodyFieldState.text.toString())
                assertFalse(editor.state.value.submission is EditorSubmissionState.Uncertain)
            } else {
                assertResumed(editor)
                assertTrue(editor.state.value.submission is EditorSubmissionState.Uncertain)
                assertEquals(EditorErrors.uncertain, editor.state.value.submitError)
                val warning = EditorEventContent.Problem(EditorErrors.uncertain)
                assertTrue(editor.editorEvents.state.events.any { it.content == warning })
            }
            editor.viewModelScope.cancel()
        }
    }

    @Test fun repeatedMissingContextDeliversTwoOccurrences() = bothModes { mode ->
        val invalid = open(mode, requestOverride = request(mode).copy(cat = null))
        settle()
        val missing = invalid.editorEvents.state.events.last().key
        invalid.submit(TopicFormIntent.ReloadRequested)
        assertNotEquals(missing, invalid.editorEvents.state.events.last().key)
        assertEquals(EditorErrors.missingSubcat, invalid.state.value.submitError)
    }

    @Test fun identicalCertainServerRefusalsDeliverDistinctEvents() = runTest(dispatcher) {
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        coEvery { repository.submitNewTopic(any(), any(), any(), any(), any(), any()) } returns
            NewTopicSubmitResult.Failure(ReplyFailureReason.AntiFlood)
        coEvery { repository.submitEditFirstPost(any(), any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)
        for (mode in TopicFormMode.entries) {
            store = VersionedEditorDraftStore()
            val editor = open(mode)
            settle()
            type(editor, "body")
            editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
            settle() // canSubmit reads the bodyText mirror, refreshed on the next frame.
            val occurrences = List(2) {
                assertTrue(editor.state.value.canSubmit)
                editor.submit(TopicFormIntent.SubmitClicked())
                settle()
                assertEquals(EditorSubmissionState.Ready, editor.state.value.submission)
                assertEquals(EditorErrors.antiFlood, editor.state.value.submitError)
                editor.editorEvents.state.events.single {
                    it.content == EditorEventContent.Problem(EditorErrors.antiFlood)
                }.key
            }
            assertNotEquals(occurrences.first(), occurrences.last())
            assertEquals(2, store.beginCalls)
            assertEquals(2, store.finishCalls)
            editor.viewModelScope.cancel()
        }
        coVerify(exactly = 2) { repository.submitNewTopic(any(), any(), "title", "body", 401, any()) }
        coVerify(exactly = 2) { repository.submitEditFirstPost(any(), any(), "title", "body", 401, any()) }
    }

    @Test fun uncertainWarningSurvivesArmingThenDismissalOrExpiry() = bothModes { mode ->
        val saved = checkpoint(mode)
        val entry = checkNotNull(store.entries[key(mode)])
        val token = AttemptToken("tester", key(mode), "attempt", entry.version)
        store.entries[key(mode)] = entry.copy(attempt = Attempt(token, AttemptStatus.UNCERTAIN))
        val editor = open(mode, saved)
        settle()
        assertTrue(editor.state.value.submission is EditorSubmissionState.Uncertain)
        assertWarningSurvivesArming(editor, EditorErrors.uncertain)
    }

    @Test fun draftSaveWarningSurvivesArmingThenDismissalOrExpiry() = bothModes { mode ->
        val editor = open(mode)
        settle()
        type(editor, "body")
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
        store.saveFailure = IOException("disk full")
        editor.submit(TopicFormIntent.Stopped)
        settle()
        assertWarningSurvivesArming(editor, EditorErrors.draftSaveFailed)
    }

    @Test fun matchingUncertainCheckpointKeepsD3UntilTheFirstSuccessfulGet() = bothModes { mode ->
        val saved = checkpoint(mode)
        val entry = checkNotNull(store.entries[key(mode)])
        val token = AttemptToken("tester", key(mode), "attempt", entry.version)
        store.entries[key(mode)] = entry.copy(attempt = Attempt(token, AttemptStatus.UNCERTAIN))
        formGate = CompletableDeferred()
        val editor = open(mode, saved)
        settle()
        assertResumed(editor)
        assertTrue(editor.state.value.submission is EditorSubmissionState.Uncertain)
        assertNull(editor.state.value.selectedSubcat)
        formGate?.complete(form())
        settle()
        assertEquals(402, editor.state.value.selectedSubcat)
        formGate = null
        served = form().copy(selectedSubcat = 403)
        editor.submit(TopicFormIntent.ReloadRequested)
        settle()
        assertEquals(402, editor.state.value.selectedSubcat)
        assertEquals(EditorErrors.uncertain, editor.state.value.submitError)
    }

    @Test fun anUncertifiedBlankAttemptStillNeedsTheFirstPostGet() = runTest(dispatcher) {
        val mode = TopicFormMode.EditFirstPost
        val token = AttemptToken("tester", key(mode), "attempt", 1)
        store.entries[key(mode)] = DraftEntry(
            Draft("", "uncertain title"), 1, Attempt(token, AttemptStatus.IN_PROGRESS))
        fetchFailure = IOException("offline")
        val editor = open(mode)
        settle()
        editor.submit(TopicFormIntent.Stopped)
        assertTrue(editor.state.value.isAwaitingInitialBody)
        fetchFailure = null
        editor.submit(TopicFormIntent.ReloadRequested)
        settle()
        assertEquals("server body", editor.bodyFieldState.text.toString())
        assertEquals(EditorErrors.uncertain, editor.state.value.submitError)
    }

    private fun bothModes(block: suspend (TopicFormMode) -> Unit) = runTest(dispatcher) {
        for (mode in TopicFormMode.entries) {
            store = VersionedEditorDraftStore()
            served = form()
            formGate = null
            fetchFailure = null
            block(mode)
            editors.forEach { it.viewModelScope.cancel() }
            settle()
        }
        coVerify(exactly = 0) { repository.submitNewTopic(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.submitEditFirstPost(any(), any(), any(), any(), any(), any()) }
    }

    private suspend fun fetch(): TopicForm {
        fetchFailure?.let { throw it }
        return formGate?.await() ?: served
    }

    private fun checkpoint(mode: TopicFormMode): SavedStateHandle {
        val saved = SavedStateHandle()
        val editor = open(mode, saved)
        settle()
        type(editor, "saved body")
        editor.submit(TopicFormIntent.SubjectChanged(TextFieldValue("saved title")))
        editor.submit(TopicFormIntent.SubcatSelected(402))
        editor.submit(TopicFormIntent.Stopped)
        mainImmediateTurn()
        val frozen = freeze(saved)
        editor.viewModelScope.cancel()
        return frozen
    }

    private fun type(editor: TopicFormViewModel, body: String, range: TextRange = TextRange(body.length)) {
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

    /**
     * ON_STOP on Main.immediate completes the cancelled title/subcategory debounce in place, so the stop
     * flush reaches the store within the same message. StandardTestDispatcher queues that cancellation:
     * run it, without snapshot notifications or virtual time, so neither the body mirror nor the debounce runs.
     */
    private fun mainImmediateTurn() = dispatcher.scheduler.runCurrent()

    private fun assertWarningSurvivesArming(editor: TopicFormViewModel, warning: EditorError) {
        val submission = editor.state.value.submission
        val events = editor.editorEvents.state.events
        assertEquals(warning, editor.state.value.submitError)
        assertTrue(editor.state.value.canSubmit)
        editor.submit(TopicFormIntent.SubmitClicked(timeoutMillis = 1_000))
        assertNotNull(editor.state.value.armToken)
        assertEquals(warning, editor.state.value.submitError)
        editor.submit(TopicFormIntent.SubmitConfirmationDismissed)
        assertEquals(submission, editor.state.value.submission)
        assertEquals(warning, editor.state.value.submitError)
        assertEquals(events, editor.editorEvents.state.events)
        editor.submit(TopicFormIntent.SubmitClicked(timeoutMillis = 1_000))
        assertNotNull(editor.state.value.armToken)
        dispatcher.scheduler.advanceTimeBy(1_000)
        settle()
        assertEquals(submission, editor.state.value.submission)
        assertEquals(warning, editor.state.value.submitError)
        assertEquals(events, editor.editorEvents.state.events)
    }

    private fun assertResumed(editor: TopicFormViewModel, body: String = "saved body", title: String = "saved title") {
        assertFalse(editor.state.value.isInitializing)
        assertFalse(editor.state.value.isRestoringSession)
        assertEquals(body, editor.bodyFieldState.text.toString())
        assertEquals(title, editor.state.value.subject.text)
        assertNull(editor.state.value.restorableDraft)
        assertNull(editor.state.value.restorableSubject)
    }

    @Suppress("DEPRECATION") // Primitive metadata is round-tripped through a real Parcel, never content/undo.
    private fun freeze(saved: SavedStateHandle): SavedStateHandle {
        val bundle = Bundle()
        for (key in saved.keys()) {
            when (val value = saved.get<Any?>(key)) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                null -> bundle.putString(key, null)
                else -> error("Unexpected saved value: ${value::class}")
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

    private fun open(
        mode: TopicFormMode,
        saved: SavedStateHandle = SavedStateHandle(),
        requestOverride: TopicFormRequest = request(mode),
    ) = TopicFormViewModel(
        request = requestOverride, previewParser = mockk(relaxed = true), defaultDispatcher = dispatcher,
        topicFormRepository = repository, smileyRepository = mockk(relaxed = true),
        userPreferencesRepository = preferences, draftStore = store, diagnostics = DiagnosticsLog(),
        authRepository = auth, uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
        hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
        smileyLocalCollections = FakeSmileyLocalCollections(),
        clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }, savedStateHandle = saved,
    ).also(editors::add)

    private fun key(mode: TopicFormMode) = if (mode == TopicFormMode.New) EditorDraftKey.newTopic(23)
        else EditorDraftKey.editFirstPost(23, 640)
    private fun request(mode: TopicFormMode) = TopicFormRequest(mode, 23, 401, 35421, 1, 640)
    private fun form() = TopicForm(
        hashCheck = "hash", subject = "server title", initialContent = "server body", selectedSubcat = 401,
        subcategoryChoices = listOf(401, 402, 403).map { TopicFormSubcategoryChoice(it, "choice $it", it == 401) },
        hiddenFields = emptyMap(), options = ReplyFormOptions(), msgIcon = "1", poll = TopicPollForm(false, emptyMap()),
        isAnonymous = false,
    )
}
