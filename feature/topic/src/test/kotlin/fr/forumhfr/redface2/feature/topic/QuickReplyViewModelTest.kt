package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.write.ReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(QuickReplyEffect.EscalateToFullEditor(emptyList()), effect)
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

    @Test
    fun `quote cards accumulate in citation order and adding twice is a no-op`() = runTest {
        val viewModel = quickReplyViewModel()

        viewModel.onQuoteAdded(preview(101, "alice"))
        viewModel.onQuoteAdded(preview(202, "bob"))
        viewModel.onQuoteAdded(preview(101, "alice-encore"))

        assertEquals(listOf(101, 202), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `cards can be reordered within bounds and removed`() = runTest {
        val viewModel = quickReplyViewModel()
        viewModel.onQuoteAdded(preview(101, "alice"))
        viewModel.onQuoteAdded(preview(202, "bob"))
        viewModel.onQuoteAdded(preview(303, "carol"))

        viewModel.onQuoteMoved(303, delta = -1)
        assertEquals(listOf(101, 303, 202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.onQuoteMoved(101, delta = -1) // already first — no-op
        assertEquals(listOf(101, 303, 202), viewModel.state.value.quotes.map { it.numreponse })

        viewModel.onQuoteRemoved(303)
        assertEquals(listOf(101, 202), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `a submit with cards materialises the prefills before the typed body`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Success(refreshUrl = null, targetPage = 9, numreponse = 77)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository)
        advanceUntilIdle() // init prefetch (plain form)
        viewModel.onQuoteAdded(preview(101, "alice"))
        viewModel.onQuoteAdded(preview(202, "bob"))
        viewModel.onTextChanged(TextFieldValue("mon avis"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        // Materialisation fetched the quote forms in citation order (after the init prefetch).
        assertEquals(listOf(null, 101, 202), repository.fetchedQuotedNumreponses)
        assertEquals(
            "[quotemsg=101]corps[/quotemsg]\n\n[quotemsg=202]corps[/quotemsg]\n\nmon avis",
            repository.submittedBodies.single(),
        )
        assertEquals(QuickReplyEffect.SubmitSucceeded(targetPage = 9, scrollTo = 77), viewModel.effects.first())
        assertTrue(viewModel.state.value.quotes.isEmpty())
    }

    @Test
    fun `a quotes-only submit pins the exact BBCode without trailing blank lines`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Success(refreshUrl = null, targetPage = null)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository)
        viewModel.onQuoteAdded(preview(101, "alice"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals("[quotemsg=101]corps[/quotemsg]", repository.submittedBodies.single())
    }

    @Test
    fun `a submit failure keeps the body and the cards`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository)
        viewModel.onQuoteAdded(preview(101, "alice"))
        viewModel.onTextChanged(TextFieldValue("texte"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(listOf(101), viewModel.state.value.quotes.map { it.numreponse })
        assertEquals("texte", viewModel.state.value.text.text)
    }

    @Test
    fun `cards alone allow the submit`() = runTest {
        val viewModel = quickReplyViewModel()
        assertTrue(!viewModel.state.value.canSubmit)
        viewModel.onQuoteAdded(preview(101, "alice"))
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `escalation carries the full card previews in citation order`() = runTest {
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.onQuoteAdded(preview(202, "bob"))
        viewModel.onQuoteAdded(preview(101, "alice"))
        viewModel.onTextChanged(TextFieldValue("suite en plein écran"))

        viewModel.onEscalateRequested()
        val effect = viewModel.effects.first()

        // #604 lot 3 — full previews (author + excerpt), not bare numreponses : the editor
        // renders the same cards and needs the snapshot only the topic surface could take.
        assertEquals(
            QuickReplyEffect.EscalateToFullEditor(listOf(preview(202, "bob"), preview(101, "alice"))),
            effect,
        )
        assertEquals("suite en plein écran", store.storedBody)
    }

    // ----- #604 lot 3 : pré-armement multi-cartes (panier → sheet) -----------

    @Test
    fun `onSheetOpened pre-arms several cards in citation order`() = runTest {
        val viewModel = quickReplyViewModel()
        viewModel.onSheetOpened(listOf(preview(202, "bob"), preview(101, "alice")))
        advanceUntilIdle()

        assertEquals(listOf(202, 101), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `reopening resets the cards to exactly the delivered set (870)`() = runTest {
        // #870 — the VM outlives the sheet : cards from a PREVIOUS session must not merge into a
        // new opening (they desynchronised the sheet from the « Citer N » FAB). The selection is
        // safe in the hoisted basket (#868/#869 : it now survives until an actual send).
        val viewModel = quickReplyViewModel()
        viewModel.onQuoteAdded(preview(303, "carol"))
        viewModel.onSheetOpened(listOf(preview(101, "alice"), preview(202, "bob")))
        advanceUntilIdle()

        assertEquals(listOf(101, 202), viewModel.state.value.quotes.map { it.numreponse })
    }

    @Test
    fun `reopening with no quotes drops the stale cards and keeps the draft text (870)`() = runTest {
        val draftStore = FakeQuickReplyDraftStore(initialBody = "brouillon")
        val viewModel = quickReplyViewModel(draftStore = draftStore)
        viewModel.onQuoteAdded(preview(303, "carol"))
        // A plain « Répondre » reopening delivers nothing : the previous session's cards must not
        // resurrect, while the #405 draft text is untouched (its row stays the source of truth).
        viewModel.onSheetOpened()
        advanceUntilIdle()

        assertTrue("stale cards must not survive a fresh opening", viewModel.state.value.quotes.isEmpty())
        assertEquals("brouillon", viewModel.state.value.text.text)
    }

    // ----- #805 : cartes OFF (défaut production) — [quotemsg] inline dans le champ ----------

    @Test
    fun `inline mode appends the quote BBCode after the row body and arms no card`() = runTest {
        val repository = FakeQuickReplyRepository()
        val draftStore = FakeQuickReplyDraftStore(initialBody = "brouillon")
        val viewModel = quickReplyViewModel(
            replyRepository = repository,
            draftStore = draftStore,
            quoteCardsEnabled = false,
        )
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()

        val state = viewModel.state.value
        // #881 — exactly one trailing newline: typing starts under the citation.
        assertEquals("brouillon\n\n[quotemsg=101]corps[/quotemsg]\n", state.text.text)
        assertEquals(state.text.text.length, state.text.selection.start)
        assertTrue(state.quotes.isEmpty())
        assertFalse(state.isPreparingQuotes)
        // #881 réserve gate — the autosave persists the field verbatim, trailing newline included.
        assertEquals(state.text.text, draftStore.savedBodies.last())
        // Prefetch (plain) then the quote form — whose hash the later submit rides.
        assertEquals(listOf(null, 101), repository.fetchedQuotedNumreponses)
    }

    @Test
    fun `inline mode merges a small basket in citation order`() = runTest {
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onSheetOpened(listOf(preview(202, "bob"), preview(101, "alice")))
        advanceUntilIdle()

        assertEquals(
            "[quotemsg=202]corps[/quotemsg]\n\n[quotemsg=101]corps[/quotemsg]\n",
            viewModel.state.value.text.text,
        )
        assertTrue(viewModel.state.value.quotes.isEmpty())
    }

    @Test
    fun `inline mode never loses typing made during the quote fetch`() = runTest {
        // Réserve Codex n°1 — the completion CONCATENATES onto the live field content, never a
        // replacement recomputed from the row : keystrokes landed mid-fetch must survive.
        val repository = GatedQuoteFormRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPreparingQuotes)

        viewModel.onTextChanged(TextFieldValue("pendant le fetch"))
        assertFalse(viewModel.state.value.canSubmit)

        repository.quoteGate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("pendant le fetch\n\n[quotemsg=101]corps[/quotemsg]\n", state.text.text)
        assertEquals("caret on the fresh line", state.text.text.length, state.text.selection.start)
        assertFalse(state.isPreparingQuotes)
        assertTrue(state.canSubmit)
    }

    @Test
    fun `inline mode surfaces a quote-fetch failure and keeps the field intact`() = runTest {
        val repository = GatedQuoteFormRepository(failQuoteFetch = true)
        repository.quoteGate.complete(Unit)
        val viewModel = quickReplyViewModel(
            replyRepository = repository,
            draftStore = FakeQuickReplyDraftStore(initialBody = "acquis"),
            quoteCardsEnabled = false,
        )
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("acquis", state.text.text)
        assertEquals(QuickReplySubmitError.QuoteFetchFailed, state.submitError)
        assertFalse(state.isPreparingQuotes)
    }

    @Test
    fun `dismiss cancels an in-flight inline materialisation`() = runTest {
        // Réserve Codex n°2 — a fetch started for an abandoned composition must never inject
        // its BBCode into the next one.
        val repository = GatedQuoteFormRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPreparingQuotes)

        viewModel.onDismissed()
        repository.quoteGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.text.text)
        assertFalse(viewModel.state.value.isPreparingQuotes)
    }

    @Test
    fun `escalation is inert while the quote insert is in flight`() = runTest {
        val repository = GatedQuoteFormRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isPreparingQuotes)

        var escalated = false
        val collector = launch { viewModel.effects.first(); escalated = true }
        viewModel.onEscalateRequested()
        advanceUntilIdle()
        assertFalse(escalated)

        repository.quoteGate.complete(Unit)
        advanceUntilIdle()
        collector.cancel()
    }

    @Test
    fun `stale cards from a previous ON session are dropped by an OFF opening (870)`() = runTest {
        // #870 supersedes the gate-Codex fold (finding 1 of #805) : stale cards are DROPPED, not
        // folded — the delivered set is the session, and the citation selection now survives in
        // the hoisted basket (#868/#869), so nothing the user selected is lost. The OFF opening
        // still never re-arms the cards submit path.
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onQuoteAdded(preview(101, "alice"))

        viewModel.onSheetOpened(listOf(preview(202, "bob")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("no card survives an OFF opening", state.quotes.isEmpty())
        assertEquals(
            "[quotemsg=202]corps[/quotemsg]\n",
            state.text.text,
        )
    }

    @Test
    fun `inline mode submits through the plain path with the field content`() = runTest {
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.onSheetOpened(listOf(preview(101, "alice")))
        advanceUntilIdle()
        viewModel.onTextChanged(TextFieldValue("[quotemsg=101]corps[/quotemsg]\n\nmon ajout"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(listOf("[quotemsg=101]corps[/quotemsg]\n\nmon ajout"), repository.submittedBodies)
        // No card in state → plain submit path, riding the warmed quote form : no extra fetch.
        assertEquals(listOf(null, 101), repository.fetchedQuotedNumreponses)
    }

    private fun preview(numreponse: Int, author: String): QuotedPostPreview =
        QuotedPostPreview(numreponse = numreponse, author = author, excerpt = "extrait")

    private fun quickReplyViewModel(
        replyRepository: ReplyRepository = FakeQuickReplyRepository(),
        draftStore: EditorDraftStore = FakeQuickReplyDraftStore(),
        confirmBeforePosting: Boolean = false,
        // Test default = cards ON so the #604 lot 2-3 card suites keep exercising their mode ;
        // the #805 inline tests opt OUT explicitly (the PRODUCTION default is false = inline).
        quoteCardsEnabled: Boolean = true,
    ): QuickReplyViewModel = QuickReplyViewModel(
        request = QuickReplyRequest(cat = 23, subcat = 401, topicId = 35421, page = 3),
        replyRepository = replyRepository,
        quoteMaterializer = ReplyQuoteMaterializer(replyRepository),
        draftStore = draftStore,
        userPreferencesRepository = FakeUserPreferencesRepository(
            confirmBeforePosting = confirmBeforePosting,
            quoteCardsEnabled = quoteCardsEnabled,
        ),
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

/**
 * #805 — quote-form fetches block on [quoteGate] (and optionally fail once released) so the
 * inline-mode tests can observe the in-flight state ; the plain prefetch answers immediately.
 */
private class GatedQuoteFormRepository(
    private val failQuoteFetch: Boolean = false,
) : ReplyRepository {
    val quoteGate = CompletableDeferred<Unit>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        val quoted = context.quotedNumreponse
        if (quoted != null) {
            quoteGate.await()
            if (failQuoteFetch) throw IOException("quote fetch down")
        }
        return ReplyForm(
            hashCheck = "hash",
            sujet = "sujet",
            hiddenFields = emptyMap(),
            isAnonymous = false,
            initialContent = quoted?.let { "[quotemsg=$it]corps[/quotemsg]" }.orEmpty(),
        )
    }

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
}

private class FakeQuickReplyRepository(
    private val results: MutableList<ReplySubmitResult> = mutableListOf(),
) : ReplyRepository {
    var fetchCalls = 0
    var submitCalls = 0
    val submittedBodies = mutableListOf<String>()
    val fetchedQuotedNumreponses = mutableListOf<Int?>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        fetchCalls++
        fetchedQuotedNumreponses += context.quotedNumreponse
        val prefill = context.quotedNumreponse?.let { "[quotemsg=$it]corps[/quotemsg]" }.orEmpty()
        return ReplyForm(
            hashCheck = "hash",
            sujet = "sujet",
            hiddenFields = emptyMap(),
            isAnonymous = false,
            initialContent = prefill,
        )
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
