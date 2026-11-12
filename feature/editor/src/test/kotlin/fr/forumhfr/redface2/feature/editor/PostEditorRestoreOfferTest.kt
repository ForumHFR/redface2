package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** ED-89: real ViewModel hydration/restore/autosave, with no TextFieldState migration. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostEditorRestoreOfferTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = VersionedEditorDraftStore()
    private val editRepository = mockk<EditPostRepository>()
    private val replyRepository = mockk<ReplyRepository>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val auth = mockk<AuthRepository>()
    private val editors = mutableListOf<PostEditorViewModel>()
    private val editKey = EditorDraftKey.editPost(23, 101)
    private val replyKey = EditorDraftKey.reply(23, 35421)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store.preload(editKey, Draft(OFFER))
        store.preload(replyKey, Draft(OFFER))
        every { preferences.observeConfirmBeforePosting() } returns flowOf(false)
        every { preferences.observeEditorImageInsert() } returns flowOf(EditorImageInsert.REDUCED)
        every { preferences.observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        every { preferences.observeEditorShortcut() } returns flowOf(EditorShortcut.NONE)
        every { auth.observeAuthState() } returns flowOf(AuthState.Anonymous)
        coEvery { editRepository.fetchEditPostForm(any()) } returns form(SEED)
        coEvery { replyRepository.fetchReplyForm(any()) } returns form("")
    }

    @After
    fun tearDown() {
        editors.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun restoreReplacesUntouchedSeed() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val editor = open(saved = saved)
        runCurrent()
        assertEquals(SEED, editor.bodyFieldState.text.toString())
        assertEquals(SEED, editor.state.value.draftRestoreSeed)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
        assertFalse(saved.keys().any { saved.get<Any?>(it) == SEED || saved.get<Any?>(it) == OFFER })
        advanceUntilIdle()
        assertEquals(OFFER, store.saved[editKey]?.body)
    }

    @Test
    fun draftRereadRemovesQuoteFailureKeyWhenResettingCounter() = runTest(dispatcher) {
        val key = "postEditor.quoteFailures"
        val saved = SavedStateHandle(mapOf(key to 2))
        val editor = open(saved = saved)
        assertEquals(2, editor.state.value.quoteFetchFailures)
        runCurrent()
        assertEquals(0, editor.state.value.quoteFetchFailures)
        assertFalse(saved.contains(key))
        assertEquals(0, open(saved = saved).state.value.quoteFetchFailures)
    }

    @Test
    fun restoreKeepsTextTypedAfterSeed() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, SEED + "\n\t  typed suffix  ")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER + "\n\n" + "typed suffix  ")
    }

    // v0.7.8 ED-89 case 4, empty seed: D, separator, then the typing (was typing then D until v0.7.7).
    @Test
    fun restorePutsDraftBeforeTypedTextWhenSeedEmpty() = runTest(dispatcher) {
        val editor = open(PostEditorMode.Reply)
        runCurrent()
        assertEquals("", editor.state.value.draftRestoreSeed)
        type(editor, "typed response")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER + "\n\n" + "typed response")
    }

    @Test
    fun restoreAppendsWhenSeedEditedInside() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, "server EDITED body")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, "server EDITED body\n\n" + OFFER)
    }

    @Test
    fun editReopenRestoreYieldsSingleCopy() = runTest(dispatcher) {
        store.entries.remove(editKey)
        val first = open()
        runCurrent()
        type(first, "server body with local edits")
        first.submit(PostEditorIntent.CloseRequested)
        runCurrent()
        first.viewModelScope.cancel()
        assertEquals("server body with local edits", store.saved[editKey]?.body)

        val reopened = open()
        runCurrent()
        assertEquals(SEED, reopened.bodyFieldState.text.toString())
        assertEquals("server body with local edits", reopened.state.value.restorableDraft)
        reopened.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(reopened, "server body with local edits")
        advanceUntilIdle()
        assertEquals("server body with local edits", store.saved[editKey]?.body)
    }

    @Test
    fun restoreReplacesBlankField() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, " \n\t ")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
    }

    // v0.7.8 ED-89 case 4, empty seed: the typing now follows D and keeps its own trailing whitespace.
    @Test
    fun restoreKeepsTrailingWhitespaceOfTypedTextAfterDraft() = runTest(dispatcher) {
        val editor = open(PostEditorMode.Reply)
        runCurrent()
        type(editor, "typed response \t\n\n")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER + "\n\n" + "typed response \t\n\n")
    }

    @Test
    fun restoreTreatsBlankSuffixAsUntouchedSeed() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, SEED + " \t\n\n")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
    }

    @Test
    fun restoreDisabledWhileSeedFormLoads() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReplyForm>()
        coEvery { editRepository.fetchEditPostForm(any()) } coAnswers { gate.await() }
        val editor = open()
        runCurrent()
        assertTrue(editor.state.value.isLoadingForm)
        assertNull(editor.state.value.draftRestoreSeed)
        assertFalse(editor.state.value.canRestoreDraft)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertEquals(OFFER, editor.state.value.restorableDraft)
        assertEquals("", editor.bodyFieldState.text.toString())
        gate.complete(form(SEED))
        runCurrent()
        assertTrue(editor.state.value.canRestoreDraft)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
    }

    @Test
    fun restoreAllowedWithEmptySeedAfterFormFailure() = runTest(dispatcher) {
        coEvery { editRepository.fetchEditPostForm(any()) } throws IOException("offline")
        val editor = open()
        runCurrent()
        assertFalse(editor.state.value.isLoadingForm)
        assertEquals("", editor.state.value.draftRestoreSeed)
        assertTrue(editor.state.value.canRestoreDraft)
        assertTrue(editor.state.value.isAwaitingInitialBody)
        type(editor, "typed offline")
        assertEquals("", editor.bodyFieldState.text.toString())
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
        assertFalse(editor.state.value.isAwaitingInitialBody)
        type(editor, OFFER + " suffix")
        assertEquals(OFFER + " suffix", editor.bodyFieldState.text.toString())
        advanceUntilIdle()
        assertEquals(OFFER + " suffix", store.saved[editKey]?.body)
        coEvery { editRepository.fetchEditPostForm(any()) } returns form(SEED)
        editor.submit(PostEditorIntent.ReloadRequested)
        runCurrent()
        assertEquals(OFFER + " suffix", editor.bodyFieldState.text.toString())
    }

    @Test
    fun blankDraftCannotUnlockEditAfterFormFailure() = runTest(dispatcher) {
        coEvery { editRepository.fetchEditPostForm(any()) } throws IOException("offline")
        for (body in listOf("", " \n")) {
            store.preload(editKey, Draft(body))
            val editor = open()
            runCurrent()
            assertNull(editor.state.value.restorableDraft)
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            type(editor, "partial replacement")
            assertEquals("", editor.bodyFieldState.text.toString())
            assertTrue(editor.state.value.isAwaitingInitialBody)
            assertFalse(editor.state.value.canSubmit)
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun restorationAfterFormFailureDecidesTheBodyEvenIfClearedBeforeReload() = runTest(dispatcher) {
        coEvery { editRepository.fetchEditPostForm(any()) } throws IOException("offline")
        val editor = open()
        runCurrent()
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
        type(editor, "")
        coEvery { editRepository.fetchEditPostForm(any()) } returns form(SEED)
        editor.submit(PostEditorIntent.ReloadRequested)
        runCurrent()
        assertEquals("", editor.bodyFieldState.text.toString())
        assertTrue(editor.state.value.draftHydratedFromForm)
    }

    @Test
    fun restoreDoesNotDuplicateAnIdenticalLiveDraft() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, OFFER)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
    }

    @Test
    fun rejectedTypingLeavesServerSeedAvailableForRestore() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReplyForm>()
        coEvery { editRepository.fetchEditPostForm(any()) } coAnswers { gate.await() }
        val editor = open()
        runCurrent()
        type(editor, "typed before server form")
        assertEquals("", editor.bodyFieldState.text.toString())
        gate.complete(form(SEED))
        runCurrent()
        assertEquals(SEED, editor.state.value.draftRestoreSeed)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun restoreEmitsOneBodyMutationWithCollapsedCaretForEveryRule() = runTest(dispatcher) {
        for ((mode, live, expected) in listOf(
            Triple(PostEditorMode.Edit, SEED, OFFER),
            // v0.7.8 ED-89 case 4, empty seed: D then typing; edit seeds below keep their rule.
            Triple(PostEditorMode.Reply, "typed", OFFER + "\n\ntyped"),
            Triple(PostEditorMode.Edit, SEED + " suffix", OFFER + "\n\nsuffix"),
            Triple(PostEditorMode.Edit, SEED + " \t\n", OFFER),
            Triple(PostEditorMode.Edit, "edited seed", "edited seed\n\n" + OFFER),
            Triple(PostEditorMode.Edit, " \n", OFFER),
            Triple(PostEditorMode.Edit, OFFER, OFFER),
        )) {
            val editor = open(mode)
            runCurrent()
            type(editor, live)
            val bodies = mutableListOf<String>()
            val collector = backgroundScope.launch(
                UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED,
            ) {
                snapshotFlow { editor.bodyFieldState.text.toString() }.collect { bodies += it }
            }
            val before = bodies.size
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            Snapshot.sendApplyNotifications()
            runCurrent()
            val mutations = bodies.drop(before).fold(live to 0) { (previous, count), body ->
                body to (count + if (body != previous) 1 else 0)
            }.second
            assertEquals(if (live == expected) 0 else 1, mutations)
            assertRestored(editor, expected)
            if (live != expected) {
                // The flow conflates; history does not. One undo must land exactly on the pre-restore body.
                editor.bodyFieldState.undoState.undo()
                assertEquals(live, editor.bodyFieldState.text.toString())
                assertEquals(TextRange(live.length, 0), editor.bodyFieldState.selection)
                editor.bodyFieldState.undoState.redo()
                assertRestored(editor, expected)
            }
            collector.cancel()
            editor.viewModelScope.cancel()
            // Each iteration starts from the same offered body rather than a prior test's decided row.
            store.preload(editKey, Draft(OFFER))
            store.preload(replyKey, Draft(OFFER))
        }
    }

    private fun type(editor: PostEditorViewModel, text: String) {
        // Deliberately leave the display mirror behind before restoration.
        editor.bodyFieldState.edit {
            replace(0, length, text)
            selection = TextRange(text.length, 0)
            with(editor.bodyInputTransformation) { transformInput() }
        }
    }

    private fun assertRestored(editor: PostEditorViewModel, expected: String) {
        val state = editor.state.value
        assertEquals(expected, editor.bodyFieldState.text.toString())
        assertEquals(TextRange(expected.length), editor.bodyFieldState.selection)
        assertNull(editor.bodyFieldState.composition)
        assertNull(state.restorableDraft)
        coVerify(exactly = 0) { editRepository.submitEditPost(any(), any(), any(), any()) }
        coVerify(exactly = 0) { replyRepository.submitReply(any(), any(), any(), any()) }
    }

    private fun open(
        mode: PostEditorMode = PostEditorMode.Edit,
        saved: SavedStateHandle = SavedStateHandle(),
    ): PostEditorViewModel = PostEditorViewModel(
        request = PostEditorRequest(mode, 23, 35421, if (mode == PostEditorMode.Edit) 101 else null, 1, 0),
        previewParser = mockk(relaxed = true),
        defaultDispatcher = dispatcher,
        replyRepository = replyRepository,
        editPostRepository = editRepository,
        smileyRepository = mockk(relaxed = true),
        userPreferencesRepository = preferences,
        draftStore = store,
        diagnostics = DiagnosticsLog(),
        uploadRepository = mockk(),
        imageUploadReader = mockk(),
        authRepository = auth,
        quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
        hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
        smileyLocalCollections = FakeSmileyLocalCollections(),
        clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
        savedStateHandle = saved,
    ).also(editors::add)

    private fun form(body: String) = ReplyForm("test-hash", "test topic", emptyMap(), false, body)

    private companion object {
        const val SEED = "server body"
        const val OFFER = "  recovered draft\n\n "
    }
}
