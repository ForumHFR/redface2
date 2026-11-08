package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSessionResources
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionAdapter
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionScenario
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.EditorWriteOwner
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.SubmissionBodyMutation
import fr.forumhfr.redface2.core.domain.editor.SubmissionErrorProbe
import fr.forumhfr.redface2.core.domain.editor.SubmissionProbe
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.domain.write.truncateQuote
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.toEditorError
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

/**
 * Vague 4 (#604) lot 1 — the quick-reply sheet's thin ViewModel. The pinned contracts: the #405
 * draft row is the sheet↔full-screen transfer (auto-applied on open, flushed on dismiss, written
 * BEFORE the escalation effect), the POST path mirrors the full editor's typed outcomes, and the
 * #312 confirmation preference gates the POST behind the dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplyViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `expired hash with failed prefetch fetches a new form before the next POST`() = runTest {
        val base = FakeQuickReplyRepository(results = mutableListOf(
            ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck),
        ))
        var fetches = 0
        var submittedHash: String? = null
        val repository = object : ReplyRepository by base {
            override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
                fetches++
                if (fetches == 2) throw IOException("refresh offline")
                return base.fetchReplyForm(context).copy(hashCheck = "hash-$fetches")
            }

            override suspend fun submitReply(
                context: ReplyContext, form: ReplyForm, bbcodeContent: String, options: ReplyFormOptions,
            ): ReplySubmitResult {
                submittedHash = form.hashCheck
                return base.submitReply(context, form, bbcodeContent, options)
            }
        }
        val vm = quickReplyViewModel(replyRepository = repository)
        vm.openForTest()
        advanceUntilIdle()
        vm.onTextChanged(TextFieldValue("body"))
        vm.onSubmitClicked()
        advanceUntilIdle()
        assertEquals(EditorErrors.invalidHashCheck, vm.state.value.submitError)
        assertEquals(2, fetches)
        assertEquals(1, base.submitCalls)
        assertEquals("hash-1", submittedHash)
        vm.onSubmitClicked()
        advanceUntilIdle()
        assertEquals(3, fetches)
        assertEquals(2, base.submitCalls)
        assertEquals("hash-3", submittedHash)
    }

    @Test
    fun `ED132 quote alone with empty prefill stays blocked through close and reopen (#1033)`() = runTest {
        val repository = FakeQuickReplyRepository(quotePrefills = mapOf(101 to ""))
        val vm = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        vm.openForTest(listOf(preview(101, "alice")))
        advanceUntilIdle()
        vm.onSubmitClicked()
        assertEquals("", vm.state.value.text.text)
        assertEquals(EditorErrors.quoteFetchFailed(inline = true, quickReply = true), vm.state.value.submitError)
        assertFalse(vm.state.value.canSubmit)
        vm.onDismissed()
        advanceUntilIdle()
        vm.openForTest()
        advanceUntilIdle()
        assertEquals(listOf(101), vm.state.value.pendingQuotes.map { it.numreponse })
        assertFalse(vm.state.value.canSubmit)
        assertEquals(0, repository.submitCalls)
    }

    @Test
    fun `ED132 a pending request revokes the previous arm even after its quote arrives`() = runTest {
        val repository = FakeQuickReplyRepository()
        val vm = quickReplyViewModel(
            replyRepository = repository, quoteCardsEnabled = false, confirmBeforePosting = true,
        )
        vm.openForTest()
        vm.onTextChanged(TextFieldValue("body"))
        vm.onSubmitClicked()
        val token = (vm.state.value.submission as EditorSubmissionState.Armed).token
        vm.openForTest(listOf(preview(101, "alice")))
        advanceTimeBy(401)
        runCurrent()
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertFalse(vm.state.value.confirmVisible)
        vm.onSubmitConfirmed(token)
        runCurrent()
        assertEquals(0, repository.submitCalls)
    }

    @Test
    fun `ED131 sheet error survives typing and selection`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)),
        )
        val vm = quickReplyViewModel(replyRepository = repository)
        vm.openForTest()
        vm.onTextChanged(TextFieldValue("body"))
        vm.onSubmitClicked()
        advanceUntilIdle()
        vm.onTextChanged(TextFieldValue("changed"))
        assertEquals(EditorErrors.empty, vm.state.value.submitError)
        vm.onTextChanged(TextFieldValue("changed", TextRange(1, 3)))
        assertEquals(EditorErrors.empty, vm.state.value.submitError)
    }

    @Test
    fun `ED132 escalation carries no inline coordinates and reopening reads the pending row`() = runTest {
        val repository = FakeQuickReplyRepository(quotePrefills = mapOf(202 to ""))
        val vm = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        val quotes = listOf(preview(202, "bob"), preview(101, "alice"))
        vm.openForTest(quotes)
        advanceUntilIdle()
        vm.onTextChanged(TextFieldValue("body"))
        vm.onSubmitClicked()
        vm.onSubmitConfirmed(null)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(0, repository.submitCalls)
        vm.onEscalateRequested()
        advanceUntilIdle()
        val effect = vm.effects.first() as QuickReplyEffect.EscalateToFullEditor
        assertTrue(effect.quotes.isEmpty())
        assertEquals(EditorErrors.quoteFetchFailed(inline = true, quickReply = true), vm.state.value.submitError)
        vm.onDismissed()
        advanceUntilIdle()
        vm.openForTest()
        advanceUntilIdle()
        assertEquals(listOf(202, 101), vm.state.value.pendingQuotes.map { it.numreponse })
        assertEquals("body", vm.state.value.text.text)
        assertEquals(0, repository.submitCalls)
    }

    @Test
    fun `ED132 close and recite retries all pending quotes once and retains typing`() = runTest {
        var fail = true
        val base = FakeQuickReplyRepository()
        val repository = object : ReplyRepository by base {
            override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
                if (context.isQuote && fail) throw IOException("offline")
                return base.fetchReplyForm(context)
            }
        }
        val store = FakeQuickReplyDraftStore()
        val vm = quickReplyViewModel(replyRepository = repository, draftStore = store, quoteCardsEnabled = false)
        val quotes = listOf(preview(202, "bob"), preview(101, "alice"))
        vm.openForTest(quotes)
        advanceUntilIdle()
        vm.onTextChanged(TextFieldValue("body"))
        vm.onDismissed()
        advanceUntilIdle()
        assertEquals(listOf(202, 101), vm.state.value.pendingQuotes.map { it.numreponse })
        fail = false
        vm.openForTest(listOf(preview(101, "alice")))
        advanceUntilIdle()
        val body = vm.state.value.text.text
        assertEquals(1, Regex("quotemsg=202").findAll(body).count())
        assertEquals(1, Regex("quotemsg=101").findAll(body).count())
        assertTrue(body.startsWith("body"))
        assertTrue(body.indexOf("202") < body.indexOf("101"))
        assertTrue(vm.state.value.pendingQuotes.isEmpty())
        assertEquals(0, base.submitCalls)
    }

    @Test
    fun `opening the sheet seeds the field from the draft row`() = runTest {
        val store = FakeQuickReplyDraftStore(initialBody = "brouillon en cours")
        val viewModel = quickReplyViewModel(draftStore = store)

        viewModel.openForTest()
        advanceUntilIdle()

        assertEquals("brouillon en cours", viewModel.state.value.text.text)
    }

    @Test
    fun `reopening the sheet re-seeds the field after the row changed elsewhere`() = runTest {
        // Gate #788 — the VM outlives the sheet: escalate → the full-screen editor rewrites the
        // shared #405 row → back → reopen. The row must win over the VM's cached text.
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.openForTest()
        viewModel.onTextChanged(TextFieldValue("version sheet"))
        viewModel.onEscalateRequested()
        advanceUntilIdle()

        store.storedBody = "version plein écran"
        viewModel.openForTest()
        advanceUntilIdle()

        assertEquals("version plein écran", viewModel.state.value.text.text)
    }

    @Test
    fun `reopening after an account switch captures the new owner and never surfaces the old draft`() = runTest {
        // #953 F2 — the VM is nav-entry-scoped and OUTLIVES the sheet : alice opens (her draft
        // seeds the field), closes, the account switches to bob, bob reopens the SAME VM. The
        // opening must re-snapshot the owner : bob gets HIS row, never alice's private draft.
        val store = FakeQuickReplyDraftStore(initialBody = "brouillon privé d'alice", initialOwner = "alice")
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.openForTest()
        advanceUntilIdle()
        assertEquals("brouillon privé d'alice", viewModel.state.value.text.text)
        viewModel.onDismissed()
        advanceUntilIdle()

        store.activeOwner = "bob"
        store.storedBody = "brouillon de bob"
        viewModel.openForTest()
        advanceUntilIdle()

        assertEquals(
            "the reopening must ride bob's row — alice's draft must never resurface",
            "brouillon de bob",
            viewModel.state.value.text.text,
        )
    }

    @Test
    fun `reopening never exposes the previous account's text, even while the load is in flight`() = runTest {
        // Gate Sol #953 F2 — the state SEQUENCE across a reopen must never show alice's text to
        // bob, not even transiently while the new owner's row is still loading (the owner
        // snapshot is gated to hold the load in flight).
        val store = FakeQuickReplyDraftStore(initialBody = "brouillon privé d'alice", initialOwner = "alice")
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.openForTest()
        advanceUntilIdle()
        viewModel.onDismissed()
        advanceUntilIdle()

        store.activeOwner = "bob"
        store.storedBody = "brouillon de bob"
        val ownerGate = CompletableDeferred<Unit>()
        store.ownerGate = ownerGate

        viewModel.state.test {
            assertEquals("brouillon privé d'alice", awaitItem().text.text) // pre-reopen baseline
            viewModel.openForTest()
            // The masking state must land BEFORE the (still-gated) load completes.
            assertEquals("bob must never see alice's text mid-load", "", awaitItem().text.text)
            ownerGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("brouillon de bob", awaitItem().text.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tasks of a session whose owner never resolved no-op instead of adopting the next account`() = runTest {
        // Gate Sol #953 F2 — currentOwner() resolving LATE (after an account switch + reopen)
        // must not let the dead session's deferred writes ride the NEW owner : alice's flushed
        // text must never land under bob's row, and alice's own row stays untouched.
        val store = FakeQuickReplyDraftStore(initialBody = "brouillon d'alice", initialOwner = "alice")
        val ownerGate = CompletableDeferred<Unit>()
        store.ownerGate = ownerGate
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.openForTest() // session 1 : owner snapshot still pending
        viewModel.onTextChanged(TextFieldValue("texte d'alice"))
        viewModel.onDismissed() // flush scheduled, awaiting session 1's owner
        advanceUntilIdle()

        store.activeOwner = "bob"
        viewModel.openForTest() // seals session 1 before its owner ever resolved
        ownerGate.complete(Unit) // the late resolution must not resurrect session 1's flush
        advanceUntilIdle()

        assertTrue("a sealed session's writes must no-op", store.savedBodies.isEmpty())
        assertNull("no row may be created under bob", store.storedBody)
        store.activeOwner = "alice"
        assertEquals("alice's row is untouched", "brouillon d'alice", store.storedBody)
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
        assertEquals("", store.storedBody)
        assertEquals(EditorDraftStore.AttemptStatus.PUBLISHED, store.entries.values.single().attempt?.status)
        assertEquals(listOf("hop"), repository.submittedBodies)
    }

    @Test
    fun `the context page follows the current opening at the second submit`() = runTest {
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(
                ReplySubmitResult.Success(refreshUrl = null, targetPage = 3, numreponse = 301),
                ReplySubmitResult.Success(refreshUrl = null, targetPage = 5, numreponse = 501),
            ),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        advanceUntilIdle() // init prefetch for the assisted request page (3).

        viewModel.openForTest(currentPage = 3)
        viewModel.onTextChanged(TextFieldValue("premier"))
        viewModel.onSubmitClicked()
        advanceUntilIdle()
        assertEquals(QuickReplyEffect.SubmitSucceeded(targetPage = 3, scrollTo = 301), viewModel.effects.first())

        viewModel.openForTest(currentPage = 5, initialQuotes = listOf(preview(202, "bob")))
        advanceUntilIdle()
        assertEquals("[quotemsg=202]corps[/quotemsg]\n", viewModel.state.value.text.text)
        viewModel.onTextChanged(TextFieldValue(viewModel.state.value.text.text + "\nsecond"))
        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(listOf(3, 5), repository.submittedPages)
        assertEquals(listOf(3, 5, 5), repository.fetchedPages)
        assertEquals(listOf(null, null, 202), repository.fetchedQuotedNumreponses)
        assertEquals(
            QuickReplyEffect.SubmitSucceeded(targetPage = 5, scrollTo = 501, quotedNumreponses = listOf(202)),
            viewModel.effects.first(),
        )
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
            ReplyFailureReason.AntiFlood.toEditorError(),
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
            ReplyFailureReason.InvalidHashCheck.toEditorError(),
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
    fun `escalation never announces a refused draft write`() = runTest {
        val store = FakeQuickReplyDraftStore().apply { saveFailure = IOException("disk full") }
        val viewModel = quickReplyViewModel(draftStore = store)
        viewModel.onTextChanged(TextFieldValue("unsaved text"))
        viewModel.effects.test {
            viewModel.onEscalateRequested()
            runCurrent()
            expectNoEvents()
            assertEquals(EditorErrors.draftSaveFailed, viewModel.state.value.submitError)
        }
    }

    @Test
    fun `escalation retains edited text and unresolved attempt`() = runTest {
        val repository = FakeQuickReplyRepository().apply { submitException = IOException("response lost") }
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
        viewModel.onTextChanged(TextFieldValue("attempt text"))
        viewModel.onSubmitClicked()
        runCurrent()
        val attempt = store.entries.values.single().attempt?.token
        assertEquals(EditorErrors.uncertain, viewModel.state.value.submitError)
        viewModel.onTextChanged(viewModel.state.value.text.copy(selection = TextRange(0)))
        assertEquals(EditorErrors.uncertain, viewModel.state.value.submitError)
        viewModel.onTextChanged(TextFieldValue("edited uncertainty"))
        assertEquals(EditorErrors.uncertain, viewModel.state.value.submitError)
        viewModel.effects.test {
            viewModel.onEscalateRequested()
            assertEquals(QuickReplyEffect.EscalateToFullEditor(emptyList()), awaitItem())
        }
        val entry = store.entries.values.single()
        assertEquals("edited uncertainty", entry.draft.body)
        assertEquals(attempt, entry.attempt?.token)
        assertEquals(EditorDraftStore.AttemptStatus.UNCERTAIN, entry.attempt?.status)
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

        val armToken = (viewModel.state.value.submission as EditorSubmissionState.Armed).token
        advanceTimeBy(400) // ED-166
        viewModel.onSubmitConfirmed(armToken)
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
        // #974 — the cited posts (card order) ride the effect so the topic engine can land on them.
        assertEquals(
            QuickReplyEffect.SubmitSucceeded(targetPage = 9, scrollTo = 77, quotedNumreponses = listOf(101, 202)),
            viewModel.effects.first(),
        )
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
        viewModel.openForTest(listOf(preview(202, "bob"), preview(101, "alice")))
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
        viewModel.openForTest(listOf(preview(101, "alice"), preview(202, "bob")))
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
        viewModel.openForTest()
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
        viewModel.openForTest(listOf(preview(101, "alice")))
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
    fun `inline mode inserts the truncated quote prefill when the selection requests it`() = runTest {
        val prefill = "[quotemsg=101]${longQuoteBody()}[/quotemsg]"
        val repository = FakeQuickReplyRepository(quotePrefills = mapOf(101 to prefill))
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)

        viewModel.openForTest(listOf(preview(101, "alice", truncate = true)))
        advanceUntilIdle()

        assertEquals(truncateQuote(prefill) + "\n", viewModel.state.value.text.text)
        assertEquals(listOf(null, 101), repository.fetchedQuotedNumreponses)
    }

    @Test
    fun `inline mode merges a small basket in citation order`() = runTest {
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.openForTest(listOf(preview(202, "bob"), preview(101, "alice")))
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
        viewModel.openForTest(listOf(preview(101, "alice")))
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
        viewModel.openForTest(listOf(preview(101, "alice")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("acquis", state.text.text)
        assertEquals(EditorErrors.quoteFetchFailed(inline = true, quickReply = true), state.submitError)
        assertFalse(state.isPreparingQuotes)
    }

    @Test
    fun `blank quote preparation is certain with no POST in card mode`() = runTest {
        val repository = FakeQuickReplyRepository(quotePrefills = mapOf(101 to ""))
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
        viewModel.openForTest(listOf(preview(101, "alice")))
        viewModel.onTextChanged(TextFieldValue("body"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(0, repository.submitCalls)
        assertEquals(EditorErrors.quoteFetchFailed(inline = false), viewModel.state.value.submitError)
        assertEquals("body", viewModel.state.value.text.text)
        assertEquals(listOf(101), viewModel.state.value.quotes.map { it.numreponse })
        assertEquals(null, store.entries.values.single().attempt)
        assertTrue(viewModel.state.value.canSubmit)
        viewModel.onQuoteRemoved(101)
        assertTrue(viewModel.state.value.canSubmit)
        viewModel.onSubmitClicked()
        advanceUntilIdle()
        assertEquals(1, repository.submitCalls)
        assertEquals(listOf("body"), repository.submittedBodies)
    }

    @Test
    fun `unreadable quote form is certain with no POST in card mode`() = runTest {
        val repository = FakeQuickReplyRepository().apply {
            quoteFormFailure = IllegalStateException("Reply form not found")
        }
        val store = FakeQuickReplyDraftStore()
        val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
        viewModel.openForTest(listOf(preview(101, "alice")))
        viewModel.onTextChanged(TextFieldValue("body"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(0, repository.submitCalls)
        assertEquals(EditorErrors.quoteFetchFailed(inline = false), viewModel.state.value.submitError)
        assertEquals("body", viewModel.state.value.text.text)
        assertEquals(null, store.entries.values.single().attempt)
    }

    @Test
    fun `quote GET failures are certain and keep their existing cause text`() = runTest {
        val failures = listOf(
            IOException("quote GET failed"),
            fr.forumhfr.redface2.core.domain.error.HfrServerException(503, "quote GET"),
            fr.forumhfr.redface2.core.domain.auth.SessionExpiredException("quote GET"),
        )
        for (failure in failures) {
            val repository = FakeQuickReplyRepository().apply { quoteFormFailure = failure }
            val store = FakeQuickReplyDraftStore()
            val viewModel = quickReplyViewModel(replyRepository = repository, draftStore = store)
            viewModel.openForTest(listOf(preview(101, "alice")))
            viewModel.onTextChanged(TextFieldValue("body"))
            viewModel.onSubmitClicked()
            advanceUntilIdle()

            val expected = if (failure is fr.forumhfr.redface2.core.domain.auth.SessionExpiredException) {
                EditorErrors.sessionExpired
            } else {
                EditorErrors.quoteFetchFailed(inline = false)
            }
            assertEquals(expected, viewModel.state.value.submitError)
            assertEquals(0, repository.submitCalls)
            assertEquals(null, store.entries.values.single().attempt)
            assertEquals("body", viewModel.state.value.text.text)
        }
    }

    @Test
    fun `blank or unreadable inline quote preparation is caught without a POST`() = runTest {
        for (unreadable in listOf(false, true)) {
            val repository = FakeQuickReplyRepository(quotePrefills = mapOf(101 to "")).apply {
                if (unreadable) quoteFormFailure = IllegalStateException("Reply form not found")
            }
            val viewModel = quickReplyViewModel(
                replyRepository = repository, quoteCardsEnabled = false,
                draftStore = FakeQuickReplyDraftStore(initialBody = "body"),
            )

            viewModel.openForTest(listOf(preview(101, "alice")))
            advanceUntilIdle()

            assertEquals(0, repository.submitCalls)
            assertEquals(EditorErrors.quoteFetchFailed(inline = true, quickReply = true),
                viewModel.state.value.submitError)
            assertFalse(viewModel.state.value.isPreparingQuotes)
            assertEquals("body", viewModel.state.value.text.text)
        }
    }

    @Test
    fun `dismiss cancels an in-flight inline materialisation`() = runTest {
        // Réserve Codex n°2 — a fetch started for an abandoned composition must never inject
        // its BBCode into the next one.
        val repository = GatedQuoteFormRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.openForTest(listOf(preview(101, "alice")))
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
        viewModel.openForTest(listOf(preview(101, "alice")))
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

        viewModel.openForTest(listOf(preview(202, "bob")))
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
        viewModel.openForTest(listOf(preview(101, "alice")))
        advanceUntilIdle()
        viewModel.onTextChanged(TextFieldValue("[quotemsg=101]corps[/quotemsg]\n\nmon ajout"))

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(listOf("[quotemsg=101]corps[/quotemsg]\n\nmon ajout"), repository.submittedBodies)
        // No card in state → plain submit path, riding the warmed quote form : no extra fetch.
        assertEquals(listOf(null, 101), repository.fetchedQuotedNumreponses)
    }

    @Test
    fun `inline mode forwards every quotemsg numreponse of the field for the topic landing (#974)`() = runTest {
        // Production default : no card, the citations are `[quotemsg]` BBCode in the field (one
        // materialised at opening, one typed by hand). HFR anchors the success on the FIRST cited
        // post ; the effect must still carry them all so the topic engine can pick the landing.
        val repository = FakeQuickReplyRepository(
            results = mutableListOf(ReplySubmitResult.Success(refreshUrl = null, targetPage = 9, numreponse = 101)),
        )
        val viewModel = quickReplyViewModel(replyRepository = repository, quoteCardsEnabled = false)
        viewModel.openForTest(listOf(preview(101, "alice")))
        advanceUntilIdle()
        assertTrue("inline mode arms no card", viewModel.state.value.quotes.isEmpty())
        viewModel.onTextChanged(
            TextFieldValue("[quotemsg=101]corps[/quotemsg]\n\n[quotemsg=303,3,9]suite[/quotemsg]\n\nmon ajout"),
        )

        viewModel.onSubmitClicked()
        advanceUntilIdle()

        assertEquals(
            QuickReplyEffect.SubmitSucceeded(targetPage = 9, scrollTo = 101, quotedNumreponses = listOf(101, 303)),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `a new explicit opening after terminal success permits one new send`() = runTest {
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository)
        viewModel.openForTest()
        viewModel.onTextChanged(TextFieldValue("first"))
        viewModel.onSubmitClicked()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.canSubmit)
        viewModel.onTextChanged(TextFieldValue("ignored after success"))
        viewModel.onSubmitClicked()
        assertEquals(1, repository.submitCalls)

        viewModel.openForTest()
        viewModel.onTextChanged(TextFieldValue("second"))
        viewModel.onSubmitClicked()
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), repository.submittedBodies)
    }

    @Test
    fun `rotation retains the dialog token and its accessible deadline`() = runTest {
        val repository = FakeQuickReplyRepository()
        val viewModel = quickReplyViewModel(replyRepository = repository, confirmBeforePosting = true)
        viewModel.onSheetAttached(3, emptyList(), restored = false)
        viewModel.onTextChanged(TextFieldValue("reply"))
        viewModel.onSubmitClicked(timeoutMillis = 8_000)
        val arm = (viewModel.state.value.submission as EditorSubmissionState.Armed).token
        advanceTimeBy(4_500)
        viewModel.onSheetAttached(3, emptyList(), restored = true)
        assertEquals(arm, (viewModel.state.value.submission as EditorSubmissionState.Armed).token)
        viewModel.onSubmitConfirmed(arm)
        advanceUntilIdle()
        assertEquals(1, repository.submitCalls)
    }

    private fun preview(
        numreponse: Int,
        author: String,
        truncate: Boolean = false,
    ): QuoteSelection = QuoteSelection(
        locator = QuoteLocator(page = 3, numreponse = numreponse, ref = 1),
        author = author,
        excerpt = "extrait",
        truncate = truncate,
    )

    private fun longQuoteBody(): String = (1..120).joinToString(separator = " ") { "mot" }

    /** #1301-ST — la feuille utilise le même faux repository et sa vraie ligne partagée de brouillon. */
    internal fun submissionAdapter(
        scope: TestScope,
        preferences: Flow<Boolean>,
        scenario: EditorSubmissionScenario,
    ): EditorSubmissionAdapter {
        val postGate = CompletableDeferred<Unit>()
        val results = mutableListOf<ReplySubmitResult>()
        scenario.postRejection?.let { results += ReplySubmitResult.Failure(it) }
        val repository = FakeQuickReplyRepository(results).apply {
            submitGate = postGate
            submitScenario = scenario
        }
        val store = scenario.store
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
        val authRepository = object : AuthRepository by quickReplyAuthRepository() {
            override fun observeAuthState(): Flow<AuthState> = auth
        }
        scenario.changeAccount = {
            store.activeOwner = "other"
            auth.value = AuthState.Authenticated("other")
        }
        store.preload(EditorDraftKey.reply(23, 35421), EditorDraftStore.Draft(body = "stored draft"))
        repository.submitException = scenario.postFailure
        val userPreferences = object : UserPreferencesRepository by FakeUserPreferencesRepository() {
            override fun observeConfirmBeforePosting(): Flow<Boolean> = preferences
        }
        fun createViewModel() = QuickReplyViewModel(
            request = QuickReplyRequest(cat = 23, subcat = 401, topicId = 35421, page = 3),
            savedStateHandle = SavedStateHandle(),
            replyRepository = repository,
            quoteMaterializer = TopicReplyQuoteMaterializer(repository),
            sessionResources = EditorSessionResources(
                store, userPreferences, authRepository,
                DiagnosticsClock { scope.testScheduler.currentTime * 1_000_000L }, DiagnosticsLog(),
            ),
        ).also { it.onSheetOpened() }
        var viewModel = createViewModel()
        val effects = mutableListOf<QuickReplyEffect>()
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
                SubmissionBodyMutation.SMILEY to "la feuille n'a pas de picker smiley",
                SubmissionBodyMutation.FORMAT to "la feuille n'a pas de barre de formatage",
                SubmissionBodyMutation.IMAGE_URL to "la feuille n'a pas d'insertion d'image",
                SubmissionBodyMutation.SHARED_RESTORE to "pas de route resumeSharedDraft, relecture via RESTORE",
            )
            override val uploadUnsupportedReason = "la feuille n'expose aucune entrée d'upload"
            override val postCalls get() = repository.submitCalls
            override val formFetchCalls get() = repository.fetchCalls
            override val uploadCalls: Int = 0
            override val draftSaves get() = store.saveCount
            override val draftWrites get() = store.writes
            override val successEffects get() = effects.count { it is QuickReplyEffect.SubmitSucceeded }
            override val closeEffects: Int = 0 // onDismissed n'émet pas d'effet de navigation.

            override fun editBody(body: String) = viewModel.onTextChanged(TextFieldValue(body))
            override fun clickSubmit() = viewModel.onSubmitClicked()
            override fun confirm() = viewModel.onSubmitConfirmed(
                (viewModel.state.value.submission as? EditorSubmissionState.Armed)?.token,
            )
            override fun confirmIdentity(id: Long?) = viewModel.onSubmitConfirmed(arms[id])
            override fun dismissConfirmation() = viewModel.onSubmitConfirmDismissed()
            override fun close() = viewModel.onDismissed()
            override fun completePost() { postGate.complete(Unit) }
            override fun dispose() = viewModel.viewModelScope.cancel()
            override fun startUpload(): Unit = error("cas explicitement désactivé")
            override fun completeUpload(): Unit = error("cas explicitement désactivé")
            override fun deliverLateUploadCallback(): Unit = error(uploadUnsupportedReason)

            override fun snapshot(): SubmissionProbe = viewModel.state.value.let {
                val token = (it.submission as? EditorSubmissionState.Armed)?.token
                token?.let { arms[it.id] = it }
                SubmissionProbe(
                    it.text.text, it.canSubmit, it.isSubmitting, false, it.confirmVisible,
                    armId = token?.id,
                    blocksClose = it.isSubmitting,
                    error = when (it.submitError) {
                        EditorErrors.network -> SubmissionErrorProbe.NETWORK
                        EditorErrors.sessionExpired -> SubmissionErrorProbe.SESSION_EXPIRED
                        EditorErrors.uncertain -> SubmissionErrorProbe.UNCERTAIN
                        EditorErrors.draftWriteFailed -> SubmissionErrorProbe.DRAFT_WRITE_FAILED
                        EditorErrors.invalidForm, EditorErrors.quoteFetchFailed(inline = false) ->
                            SubmissionErrorProbe.PREPARATION_FAILED
                        else -> null
                    },
                )
            }

            override fun mutateBody(mutation: SubmissionBodyMutation) {
                when (mutation) {
                    SubmissionBodyMutation.TYPING -> editBody("changed during POST")
                    SubmissionBodyMutation.RESTORE -> viewModel.onSheetOpened()
                    else -> error("cas explicitement désactivé : $mutation")
                }
            }
        }
    }

    private val openings = mutableMapOf<QuickReplyViewModel, Pair<EditorDraftStore, Boolean>>()

    /** Mimic TopicViewModel's gesture preparation; the sheet receives cards only. */
    private suspend fun QuickReplyViewModel.openForTest(initialQuotes: List<QuoteSelection> = emptyList()) =
        openForTest(currentPage = 3, initialQuotes = initialQuotes)

    private suspend fun QuickReplyViewModel.openForTest(
        currentPage: Int, initialQuotes: List<QuoteSelection> = emptyList(),
    ) {
        val (store, cards) = openings.getValue(this)
        if (!cards && initialQuotes.isNotEmpty()) {
            val drafts = EditorDraftSession(store, EditorDraftKey.reply(23, 35421))
            drafts.load()
            val pending = PendingInlineQuoteRequest(23, 35421, initialQuotes.map {
                PendingInlineQuoteRequest.Quote(it.numreponse, it.locator.page, it.locator.ref, it.truncate)
            })
            assertTrue(drafts.enqueueInlineQuoteRequest(pending) is EditorDraftStore.StoreWriteResult.Written)
        }
        onSheetOpened(currentPage, if (cards) initialQuotes else emptyList())
        // These pre-existing tests continue the offered body; explicit decisions have dedicated tests.
        if (state.value.restorableDraft != null) onDraftRestoreRequested()
    }

    private fun quickReplyViewModel(
        replyRepository: ReplyRepository = FakeQuickReplyRepository(),
        draftStore: EditorDraftStore = FakeQuickReplyDraftStore(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        confirmBeforePosting: Boolean = false,
        // Test default = cards ON so the #604 lot 2-3 card suites keep exercising their mode ;
        // the #805 inline tests opt OUT explicitly (the PRODUCTION default is false = inline).
        quoteCardsEnabled: Boolean = true,
    ): QuickReplyViewModel = QuickReplyViewModel(
        request = QuickReplyRequest(cat = 23, subcat = 401, topicId = 35421, page = 3),
        savedStateHandle = savedStateHandle,
        replyRepository = replyRepository,
        quoteMaterializer = TopicReplyQuoteMaterializer(replyRepository),
        sessionResources = EditorSessionResources(
            draftStore,
            FakeUserPreferencesRepository(
                confirmBeforePosting = confirmBeforePosting, quoteCardsEnabled = quoteCardsEnabled,
            ),
            quickReplyAuthRepository(),
            DiagnosticsClock { mainDispatcher.scheduler.currentTime * 1_000_000L }, DiagnosticsLog(),
        ),
    ).also { openings[it] = draftStore to quoteCardsEnabled }

    private companion object {
        /** Just past QuickReplyViewModel's 750 ms debounce. */
        const val AUTOSAVE_DEBOUNCE_PLUS_MS = 751L
    }
}

private class FakeQuickReplyDraftStore(
    initialBody: String? = null,
    initialOwner: String? = "xaat",
) : VersionedEditorDraftStore() {
    val savedBodies = mutableListOf<String>()
    private val key = EditorDraftKey.reply(23, 35421)
    var storedBody: String?
        get() = entries[key]?.draft?.body
        set(value) {
            if (value == null) entries.remove(key) else preload(key, EditorDraftStore.Draft(body = value))
        }
    var ownerGate: CompletableDeferred<Unit>? = null

    init {
        activeOwner = initialOwner
        if (initialBody != null) storedBody = initialBody
    }

    override suspend fun currentOwner(): String? {
        ownerGate?.await()
        return activeOwner
    }

    override suspend fun saveIfVersion(
        owner: String?, key: String, draft: EditorDraftStore.Draft, expectedVersion: Long,
    ): EditorDraftStore.StoreWriteResult = super.saveIfVersion(owner, key, draft, expectedVersion).also {
        if (it is EditorDraftStore.StoreWriteResult.Written) savedBodies += draft.body
    }

    override suspend fun startNewComposition(
        owner: String?, key: String, draft: EditorDraftStore.Draft, expectedVersion: Long,
    ): EditorDraftStore.StoreWriteResult = super.startNewComposition(owner, key, draft, expectedVersion).also {
        if (it is EditorDraftStore.StoreWriteResult.Written) savedBodies += draft.body
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
    private val quotePrefills: Map<Int, String> = emptyMap(),
) : ReplyRepository {
    var fetchCalls = 0
    var submitCalls = 0
    var submitGate: CompletableDeferred<Unit>? = null
    var submitScenario: EditorSubmissionScenario? = null
    var submitException: IOException? = null
    var quoteFormFailure: Exception? = null
    val submittedBodies = mutableListOf<String>()
    val submittedPages = mutableListOf<Int>()
    val fetchedPages = mutableListOf<Int>()
    val fetchedQuotedNumreponses = mutableListOf<Int?>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        fetchCalls++
        fetchedPages += context.page
        fetchedQuotedNumreponses += context.quotedNumreponse
        if (context.isQuote) quoteFormFailure?.let { throw it }
        val prefill = context.quotedNumreponse
            ?.let { quotePrefills[it] ?: "[quotemsg=$it]corps[/quotemsg]" }
            .orEmpty()
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
        submitScenario?.prepare()
        submitCalls++
        submittedBodies += bbcodeContent
        submittedPages += context.page
        if (submitScenario == null) currentCoroutineContext()[EditorWriteOwner]?.markEmissionPossible()
        submitGate?.let { gate -> submitScenario?.awaitPost(gate) ?: gate.await() }
        submitException?.let { throw it }
        return results.removeFirstOrNull() ?: ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
    }
}

internal fun quickReplyAuthRepository(): AuthRepository = object : AuthRepository {
    override fun observeAuthState(): Flow<AuthState> = flowOf(AuthState.Authenticated("tester"))

    override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
        error("login is not exercised by quick-reply tests")

    override suspend fun logout(): Unit = error("logout is not exercised by quick-reply tests")
}
