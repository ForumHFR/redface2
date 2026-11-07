package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftSession
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.EditorSessionResources
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/** #1553: only the shared durable row can request or retire an inline quote. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplyDurableQuoteTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val instances = mutableListOf<QuickReplyViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        instances.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `old pending Bundle coordinates cannot seed a request or GET`() = runTest {
        val scenario = Scenario()
        scenario.store.preload(scenario.key, Draft("ordinary body"))
        val saved = SavedStateHandle(mapOf("quickReply.pendingQuotes" to intArrayOf(101, 1, -1, 0)))
        val sheet = scenario.open(saved)
        advanceUntilIdle()
        assertEquals("ordinary body", sheet.state.value.text.text)
        assertTrue(sheet.state.value.legacyQuoteRequestDropped)
        assertFalse(saved.contains("quickReply.pendingQuotes"))
        assertTrue(sheet.state.value.pendingQuotes.isEmpty())
        assertTrue(scenario.fetches.isEmpty())
        assertNull(scenario.pending)
    }

    @Test
    fun `old saver coordinates keep the mount without importing a quote or issuing a GET`() = runTest {
        val scenario = Scenario()
        val launch = checkNotNull(QuickReplyLaunchSaver.restore(
            listOf(23, 401, 35421, 1, true, 1, 101, -1, "alice", "old preview", false),
        ))
        val sheet = scenario.create()
        sheet.onSheetAttached(launch.request.page, launch.initialQuotes, restored = true)
        runCurrent()
        assertTrue(sheet.state.value.pendingQuotes.isEmpty())
        assertTrue(scenario.fetches.isEmpty())
        assertNull(scenario.pending)
        assertEquals(0, scenario.store.writes)
    }

    @Test
    fun `obsolete delivered marker cannot suppress a new durable request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        val sheet = scenario.open(SavedStateHandle(mapOf("quickReply.deliveredQuotes" to "101:false")))
        runCurrent()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", sheet.state.value.text.text)
        assertEquals(listOf(101), scenario.fetches)
        assertNull(scenario.pending)
    }

    @Test
    fun `delivery commits body and request removal before autosave then ignores an old delivered marker`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        sheet.onTextChanged(TextFieldValue("typed during GET"))
        scenario.quoteGate?.complete(Unit)
        runCurrent() // No 750ms debounce: delivery itself must be durable.
        assertEquals("typed during GET\n\n[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
        assertTrue(sheet.state.value.pendingQuotes.isEmpty())
        sheet.onTextChanged(TextFieldValue("quote deliberately deleted"))
        advanceTimeBy(751)
        runCurrent()
        sheet.viewModelScope.cancel()
        val restored = scenario.open(SavedStateHandle(mapOf(
            "quickReply.deliveredQuotes" to "101:false",
            "quickReply.pendingQuotes" to intArrayOf(101, 1, -1, 0),
        )))
        advanceUntilIdle()
        assertEquals("quote deliberately deleted", restored.state.value.text.text)
        assertEquals(listOf(101), scenario.fetches)
        assertNull(scenario.pending)
    }

    @Test
    fun `ignore quote awaits durable removal and cannot replay an old pending Bundle`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.offline = true
        val sheet = scenario.open()
        sheet.onTextChanged(TextFieldValue("keep this body"))
        advanceUntilIdle()
        sheet.onQuoteReloadRequested()
        advanceUntilIdle()
        assertTrue(sheet.state.value.canIgnorePendingQuote)
        scenario.store.saveFailure = IOException("disk")
        sheet.onPendingQuoteIgnored()
        runCurrent()
        assertEquals(scenario.request, scenario.pending)
        assertFalse(sheet.state.value.canSubmit)
        scenario.store.saveFailure = null
        sheet.onPendingQuoteIgnored()
        runCurrent()
        assertNull(scenario.pending)
        assertEquals("keep this body", scenario.body)
        assertTrue(sheet.state.value.canSubmit)
        sheet.viewModelScope.cancel()
        scenario.offline = false
        val restored = scenario.open(SavedStateHandle(mapOf("quickReply.pendingQuotes" to intArrayOf(101, 1, -1, 0))))
        advanceUntilIdle()
        assertTrue(restored.state.value.pendingQuotes.isEmpty())
        assertEquals(listOf(101, 101), scenario.fetches)
        assertEquals("keep this body", restored.state.value.text.text)
    }

    @Test
    fun `ignoring an offered body preserves the request and waits for its write before GET`() = runTest {
        val scenario = Scenario()
        scenario.store.preload(scenario.key, Draft("offered body"))
        scenario.enqueue()
        val sheet = scenario.open()
        runCurrent()
        assertEquals("offered body", sheet.state.value.restorableDraft)
        assertTrue(scenario.fetches.isEmpty())
        scenario.store.saveFailure = IOException("disk")
        sheet.onDraftDiscardRequested()
        runCurrent()
        assertEquals("offered body", sheet.state.value.restorableDraft)
        assertEquals("offered body", scenario.body)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetches.isEmpty())
        scenario.store.saveFailure = null
        val saved = CompletableDeferred<Unit>()
        scenario.store.saveReturnGate = saved
        scenario.quoteGate = CompletableDeferred()
        sheet.onDraftDiscardRequested()
        runCurrent()
        assertTrue(scenario.fetches.isEmpty())
        assertEquals("", scenario.body)
        assertEquals(scenario.request, scenario.pending)
        saved.complete(Unit)
        runCurrent()
        assertNull(sheet.state.value.restorableDraft)
        assertEquals(listOf(101), scenario.fetches)
        assertEquals(scenario.request, scenario.pending)
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
    }

    @Test
    fun `restoring the offered body retains typing and starts quote delivery only after the choice`() = runTest {
        val scenario = Scenario()
        scenario.store.preload(scenario.key, Draft("older body"))
        scenario.enqueue()
        val sheet = scenario.open()
        sheet.onTextChanged(TextFieldValue("live body"))
        advanceUntilIdle()
        assertEquals("older body", scenario.body)
        assertTrue(scenario.fetches.isEmpty())
        assertFalse(sheet.state.value.canSubmit)
        sheet.onDraftRestoreRequested()
        runCurrent()
        assertEquals("live body\n\nolder body\n\n[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
    }

    @Test
    fun `uncertain request stays dormant until durable acknowledgement succeeds`() = runTest {
        val scenario = Scenario()
        val token = scenario.uncertain()
        scenario.enqueue()
        val sheet = scenario.open()
        sheet.onSubmitClicked()
        sheet.onSubmitConfirmed(null)
        assertTrue(sheet.state.value.hasDormantQuote)
        assertTrue(scenario.fetches.isEmpty())
        assertFalse(sheet.state.value.canSubmit)
        scenario.store.finishFailure = IOException("disk")
        sheet.onDormantQuoteResumed()
        runCurrent()
        assertEquals(token, scenario.store.entries[scenario.key]?.attempt?.token)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetches.isEmpty())
        scenario.store.finishFailure = null
        scenario.quoteGate = CompletableDeferred()
        sheet.onDormantQuoteResumed()
        runCurrent()
        assertNull(scenario.store.entries[scenario.key]?.attempt)
        assertEquals(scenario.request, scenario.pending)
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertEquals("uncertain body\n\n[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
        assertEquals(0, scenario.posts)
    }

    @Test
    fun `ignoring a dormant quote preserves the unresolved attempt and its uncertainty`() = runTest {
        val scenario = Scenario()
        val token = scenario.uncertain()
        scenario.enqueue()
        val sheet = scenario.open()
        sheet.onPendingQuoteIgnored()
        runCurrent()
        assertNull(scenario.pending)
        assertEquals(token, scenario.store.entries[scenario.key]?.attempt?.token)
        assertEquals(AttemptStatus.UNCERTAIN, scenario.store.entries[scenario.key]?.attempt?.status)
        assertTrue(sheet.state.value.submission is EditorSubmissionState.Uncertain)
        assertEquals(EditorErrors.uncertain, sheet.state.value.submitError)
        assertTrue(scenario.fetches.isEmpty())
        assertEquals(0, scenario.posts)
    }

    @Test
    fun `failed delivery keeps the request and retry reconciles tags without another GET`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        scenario.store.saveFailure = IOException("disk")
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertEquals(scenario.request, scenario.pending)
        assertFalse(sheet.state.value.canSubmit)
        assertEquals(EditorErrors.draftSaveFailed, sheet.state.value.submitError)
        scenario.store.saveFailure = null
        sheet.onQuoteReloadRequested()
        runCurrent()
        assertNull(scenario.pending)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertEquals(listOf(101), scenario.fetches)
    }

    @Test
    fun `a concurrent row change blocks delivery and reloads the durable request before retry`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        val other = EditorDraftSession(scenario.store, scenario.key)
        other.load()
        other.save(Draft("body from another surface"))
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertTrue(sheet.state.value.quoteStorageUnavailable)
        assertFalse(sheet.state.value.canSubmit)
        assertEquals(scenario.request, scenario.pending)
        assertEquals("body from another surface", scenario.body)
        sheet.onQuoteReloadRequested()
        runCurrent()
        assertEquals("body from another surface", sheet.state.value.restorableDraft)
        sheet.onDraftDiscardRequested() // Preserve the live quoted body, discard only the offer.
        runCurrent()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
        assertEquals(listOf(101), scenario.fetches)
    }

    @Test
    fun `typing while the committed delivery returns cannot restore the consumed request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        val committed = CompletableDeferred<Unit>()
        scenario.store.saveReturnGate = committed
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertNull(scenario.pending)
        assertFalse(sheet.state.value.canSubmit)
        val continued = sheet.state.value.text.text + "typed while saving"
        sheet.onTextChanged(TextFieldValue(continued))
        committed.complete(Unit)
        advanceUntilIdle()
        assertEquals(continued, scenario.body)
        assertNull(scenario.pending)
    }

    @Test
    fun `manual quote tags typed during GET are reconciled without duplicate insertion`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        val manual = "[quotemsg=101]manual quote[/quotemsg]\nmy text"
        sheet.onTextChanged(TextFieldValue(manual))
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertEquals(manual, scenario.body)
        assertNull(scenario.pending)
        assertEquals(listOf(101), scenario.fetches)
    }

    @Test
    fun `death before delivery resumes only the durable request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val sheet = scenario.open()
        sheet.viewModelScope.cancel()
        scenario.quoteGate = null
        val restored = scenario.open()
        runCurrent()
        assertEquals(listOf(101, 101), scenario.fetches)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", restored.state.value.text.text)
        assertNull(scenario.pending)
    }

    @Test
    fun `unreadable request exposes reload without any GET or draft write`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.store.loadFailure = UnreadableInlineQuoteRequestException()
        val sheet = scenario.open()
        sheet.onTextChanged(TextFieldValue("must not overwrite"))
        sheet.onSubmitClicked()
        advanceUntilIdle()
        assertTrue(sheet.state.value.quoteStorageUnavailable)
        assertEquals(EditorErrors.draftSaveFailed, sheet.state.value.submitError)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetches.isEmpty())
        assertEquals(0, scenario.formFetches)
        assertEquals(1, scenario.store.writes) // Only the original entry gesture.
        scenario.store.loadFailure = null
        sheet.onQuoteReloadRequested()
        runCurrent()
        assertFalse(sheet.state.value.quoteStorageUnavailable)
        assertNull(scenario.pending)
        assertEquals(listOf(101), scenario.fetches)
    }

    @Test
    fun `card launch cannot be reinterpreted by a changed inline preference`() = runTest {
        val scenario = Scenario()
        val card = QuoteSelection(QuoteLocator(1, 101, null), "alice", "preview")
        val sheet = scenario.create()
        sheet.onSheetOpened(listOf(card))
        runCurrent()
        assertEquals(listOf(card), sheet.state.value.quotes)
        assertNull(scenario.pending)
        assertTrue(scenario.fetches.isEmpty())
        assertEquals(0, scenario.store.writes)
    }

    @Test
    fun `a restored old launch after publication cannot replay but a new quote can start a composition`() = runTest {
        val scenario = Scenario()
        val attempt = scenario.uncertain()
        scenario.store.finishAttempt(attempt, AttemptOutcome.PUBLISHED)
        scenario.store.erasePublishedContent(attempt)
        val sheet = scenario.open(SavedStateHandle(mapOf(
            "quickReply.pendingQuotes" to intArrayOf(101, 1, -1, 0),
            "quickReply.deliveredQuotes" to "101:false",
        )))
        runCurrent()
        assertEquals("", sheet.state.value.text.text)
        assertTrue(scenario.fetches.isEmpty())
        scenario.enqueue()
        sheet.onSheetOpened()
        runCurrent()
        assertEquals(listOf(101), scenario.fetches)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
        assertNull(scenario.store.entries[scenario.key]?.attempt)
    }

    private inner class Scenario {
        val key = EditorDraftKey.reply(23, 35421)
        val store = VersionedEditorDraftStore()
        val request = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        val body get() = store.saved[key]?.body
        val pending get() = store.saved[key]?.pendingInlineQuoteRequest
        val fetches = mutableListOf<Int>()
        var quoteGate: CompletableDeferred<Unit>? = null
        var offline = false
        var posts = 0
        var formFetches = 0
        private val repository = object : ReplyRepository {
            override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
                formFetches++
                val number = context.quotedNumreponse
                if (number != null) {
                    fetches += number
                    quoteGate?.await()
                    if (offline) throw IOException("offline")
                }
                return ReplyForm(hashCheck = "hash", sujet = "topic", hiddenFields = emptyMap(),
                    isAnonymous = false, initialContent = number?.let { "[quotemsg=$it]quote[/quotemsg]" }.orEmpty())
            }
            override suspend fun submitReply(
                context: ReplyContext, form: ReplyForm, bbcodeContent: String, options: ReplyFormOptions,
            ): ReplySubmitResult {
                posts++
                error("No action in these tests may POST")
            }
        }

        suspend fun enqueue() {
            val session = EditorDraftSession(store, key)
            session.load()
            assertTrue(session.enqueueInlineQuoteRequest(request) is StoreWriteResult.Written)
        }

        suspend fun uncertain() = (store.beginAttempt("tester", key, Draft("uncertain body"), 0)
            as BeginAttemptResult.Started).token.also { store.finishAttempt(it, AttemptOutcome.UNCERTAIN) }

        fun create(saved: SavedStateHandle = SavedStateHandle()) = QuickReplyViewModel(
            QuickReplyRequest(23, 401, 35421, 1), saved, repository, TopicReplyQuoteMaterializer(repository),
            EditorSessionResources(store, FakeUserPreferencesRepository(quoteCardsEnabled = false),
                quickReplyAuthRepository(), DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
                DiagnosticsLog()),
        ).also { instances += it }

        fun open(saved: SavedStateHandle = SavedStateHandle()) = create(saved).also { it.onSheetOpened() }
    }
}
