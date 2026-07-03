package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Vague 4 (#604) lot 1 — the quick-reply sheet's thin ViewModel. The pinned contracts: the #405
 * draft row is the sheet↔full-screen transfer (auto-applied on open, flushed on dismiss, written
 * BEFORE the escalation effect), the POST path mirrors the full editor's typed outcomes, and the
 * #312 confirmation preference gates the POST behind the dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplyViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening the sheet seeds the field from the draft row`() = runTest {
        val store = FakeQuickReplyDraftStore(initialBody = "brouillon en cours")
        val viewModel = quickReplyViewModel(draftStore = store)

        viewModel.onSheetOpened()
        advanceUntilIdle()

        assertEquals("brouillon en cours", viewModel.state.value.text.text)
    }

    @Test
    fun `reopening the sheet re-seeds the field after the row changed elsewhere`() = runTest {
        // Gate #788 — the VM outlives the sheet: escalate → the full-screen editor rewrites the
        // shared #405 row → back → reopen. The row must win over the VM's cached text.
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.onSheetOpened()
        viewModel.onTextChanged(TextFieldValue("version sheet"))
        viewModel.onEscalateRequested()
        advanceUntilIdle()

        store.storedBody = "version plein écran"
        viewModel.onSheetOpened()
        advanceUntilIdle()

        assertEquals("version plein écran", viewModel.state.value.text.text)
    }

    @Test
    fun `typing autosaves the body after the debounce`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)

        viewModel.onTextChanged(TextFieldValue("réponse en cours de frappe"))
        assertTrue(store.savedBodies.isEmpty())
        advanceTimeBy(AUTOSAVE_DEBOUNCE_PLUS_MS)

        assertEquals(listOf("réponse en cours de frappe"), store.savedBodies)
    }

    @Test
    fun `a successful submit deletes the draft, resets the field and emits the refresh effect`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Success(refreshUrl = null, targetPage = 12, numreponse = 345)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
        viewModel.onTextChanged(TextFieldValue("hop"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        val effect = viewModel.effects.first()
        assertEquals(QuickReplyEffect.SubmitSucceeded(targetPage = 12, scrollTo = 345), effect)
        assertEquals("", viewModel.state.value.text.text)
        assertNull(store.storedBody)
        assertEquals(listOf("hop"), repository.submittedBodies)
    }

    @Test
    fun `a typed HFR failure surfaces the error and keeps the draft`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
        viewModel.onTextChanged(TextFieldValue("flood"))
        advanceTimeBy(AUTOSAVE_DEBOUNCE_PLUS_MS)

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(
            QuickReplySubmitError.Hfr(ReplyFailureReason.AntiFlood),
            viewModel.state.value.submitError,
        )
        assertEquals("flood", viewModel.state.value.text.text)
        assertEquals("flood", store.storedBody)
    }

    @Test
    fun `an expired hash_check silently refetches the form for the next attempt`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository)
        viewModel.onTextChanged(TextFieldValue("texte"))
        advanceUntilIdle() // init prefetch = 1st fetch

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(
            QuickReplySubmitError.Hfr(ReplyFailureReason.InvalidHashCheck),
            viewModel.state.value.submitError,
        )
        // 1 prefetch at open + 1 silent refetch after the expired hash.
        assertEquals(2, repository.fetchCalls)
    }

    @Test
    fun `escalation persists the draft before emitting the hand-over effect`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.onTextChanged(TextFieldValue("à finir en plein écran"))

        viewModel.onEscalateRequested()
        val effect = viewModel.effects.first()

        // The effect is only emitted once the row is written — the full editor restores it.
        assertEquals(QuickReplyEffect.EscalateToFullEditor, effect)
        assertEquals("à finir en plein écran", store.storedBody)
    }

    @Test
    fun `dismiss flushes the pending autosave debounce`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.onTextChanged(TextFieldValue("frappe interrompue"))
        assertTrue(store.savedBodies.isEmpty())

        viewModel.onDismissed()
        advanceUntilIdle()

        assertEquals("frappe interrompue", store.storedBody)
    }

    @Test
    fun `confirm-before-posting gates the POST behind the dialog`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Success(refreshUrl = null, targetPage = null)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository, confirmBeforePosting = true)
        viewModel.onTextChanged(TextFieldValue("sûr ?"))

        viewModel.onSubmitClicked()
        assertTrue(viewModel.state.value.confirmVisible)
        assertEquals(0, repository.submitCalls)

        viewModel.onSubmitConfirmed()
        advanceUntilIdle()
        assertEquals(1, repository.submitCalls)
    }

    private fun quickReplyViewModel(
        replyRepository: ReplyRepository = FakeQuickReplyRepository(),
        draftStore: EditorDraftStore = FakeQuickReplyDraftStore(),
        confirmBeforePosting: Boolean = false,
    ): QuickReplyViewModel = QuickReplyViewModel(
        request = QuickReplyRequest(cat = 23, subcat = 401, topicId = 35421, page = 3),
        replyRepository = replyRepository,
        draftStore = draftStore,
        userPreferencesRepository = FakeUserPreferencesRepository(confirmBeforePosting = confirmBeforePosting),
    )

    private companion object {
        /** Just past QuickReplyViewModel's 750 ms debounce. */
        const val AUTOSAVE_DEBOUNCE_PLUS_MS = 751L
    }
}

private class FakeQuickReplyDraftStore(
    initialBody: String? = null,
    private val owner: String? = "xaat",
) : EditorDraftStore {
    var storedBody: String? = initialBody
    val savedBodies = mutableListOf<String>()

    override suspend fun currentOwner(): String? = owner

    override suspend fun load(owner: String?, key: String): EditorDraftStore.Draft? =
        storedBody?.let { EditorDraftStore.Draft(body = it) }

    override suspend fun save(owner: String?, key: String, draft: EditorDraftStore.Draft) {
        storedBody = draft.body
        savedBodies += draft.body
    }

    override suspend fun delete(owner: String?, key: String) {
        storedBody = null
    }
}

private class FakeQuickReplyRepository(
    private val results: MutableList<ReplySubmitResult> = mutableListOf(),
) : ReplyRepository {
    var fetchCalls = 0
    var submitCalls = 0
    val submittedBodies = mutableListOf<String>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        fetchCalls++
        return ReplyForm(hashCheck = "hash", sujet = "sujet", hiddenFields = emptyMap(), isAnonymous = false)
    }

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        submitCalls++
        submittedBodies += bbcodeContent
        return results.removeFirstOrNull() ?: ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
    }
}
