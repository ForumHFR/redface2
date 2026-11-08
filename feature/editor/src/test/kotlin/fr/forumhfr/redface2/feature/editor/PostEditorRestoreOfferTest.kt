package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
        assertEquals(SEED, editor.state.value.draft.text)
        assertEquals(SEED, editor.state.value.draftRestoreSeed)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER)
        assertTrue(saved.keys().isEmpty()) // The server body is never put in SavedStateHandle.
        advanceUntilIdle()
        assertEquals(OFFER, store.saved[editKey]?.body)
    }

    @Test
    fun restoreKeepsTextTypedAfterSeed() = runTest(dispatcher) {
        val editor = open()
        runCurrent()
        type(editor, SEED + "\n\t  typed suffix  ")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, OFFER + "\n\n" + "typed suffix  ")
    }

    @Test
    fun restoreAppendsAfterTypedTextWhenSeedEmpty() = runTest(dispatcher) {
        val editor = open(PostEditorMode.Reply)
        runCurrent()
        assertEquals("", editor.state.value.draftRestoreSeed)
        type(editor, "typed response")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, "typed response\n\n" + OFFER)
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
        assertEquals(SEED, reopened.state.value.draft.text)
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

    @Test
    fun restoreTrimsTrailingWhitespaceBeforeSeparator() = runTest(dispatcher) {
        val editor = open(PostEditorMode.Reply)
        runCurrent()
        type(editor, "typed response \t\n\n")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, "typed response\n\n" + OFFER)
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
        assertEquals("", editor.state.value.draft.text)
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
        type(editor, "typed offline")
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, "typed offline\n\n" + OFFER)
        advanceUntilIdle()
        assertEquals("typed offline\n\n" + OFFER, store.saved[editKey]?.body)
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
    fun restoreHasEmptySeedWhenTypingPreventedServerHydration() = runTest(dispatcher) {
        val gate = CompletableDeferred<ReplyForm>()
        coEvery { editRepository.fetchEditPostForm(any()) } coAnswers { gate.await() }
        val editor = open()
        runCurrent()
        type(editor, "typed before server form")
        gate.complete(form(SEED))
        runCurrent()
        assertEquals("", editor.state.value.draftRestoreSeed)
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        assertRestored(editor, "typed before server form\n\n" + OFFER)
    }

    private fun type(editor: PostEditorViewModel, text: String) {
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue(text, TextRange(0, text.length))))
    }

    private fun assertRestored(editor: PostEditorViewModel, expected: String) {
        val state = editor.state.value
        assertEquals(expected, state.draft.text)
        assertEquals(TextRange(expected.length), state.draft.selection)
        assertNull(state.draft.composition)
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
