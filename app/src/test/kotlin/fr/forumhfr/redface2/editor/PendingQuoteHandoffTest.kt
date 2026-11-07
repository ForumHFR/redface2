package fr.forumhfr.redface2.editor

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
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
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.feature.editor.PostEditorIntent
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.PostEditorRequest
import fr.forumhfr.redface2.feature.editor.PostEditorViewModel
import fr.forumhfr.redface2.feature.topic.QuickReplyEffect
import fr.forumhfr.redface2.feature.topic.QuickReplyRequest
import fr.forumhfr.redface2.feature.topic.QuickReplyViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** #1553: restoration reads only the durable row, independently of frozen navigation/Bundle snapshots. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingQuoteHandoffTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `delivery commits body and request removal before autosave and never replays an old Bundle`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        val saved = SavedStateHandle()
        scenario.quoteGate = CompletableDeferred()
        var editor = scenario.editor(saved)
        runCurrent()
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("typed during GET")))
        val frozen = snapshot(saved)
        scenario.quoteGate?.complete(Unit)
        runCurrent() // No autosave debounce has elapsed.
        val expected = "[quotemsg=101]quote[/quotemsg]\n\ntyped during GET"
        assertEquals(expected, scenario.body)
        assertNull(scenario.pending)
        editor.viewModelScope.cancel()
        scenario.quoteGate = null
        val fetches = scenario.fetchedQuotes.size
        editor = scenario.editor(SavedStateHandle(frozen))
        advanceUntilIdle()
        assertEquals(expected, editor.state.value.draft.text)
        assertEquals(fetches, scenario.fetchedQuotes.size)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `death before delivery keeps the durable request without any saved coordinates`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        var editor = scenario.editor()
        runCurrent()
        assertEquals(scenario.request, scenario.pending)
        editor.viewModelScope.cancel()
        scenario.quoteGate = null
        editor = scenario.editor()
        advanceUntilIdle()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.pending)
        assertEquals(listOf(101, 101), scenario.fetchedQuotes)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `tags typed during GET are reconciled without duplicating any requested quote`() = runTest {
        val scenario = Scenario()
        val session = EditorDraftSession(scenario.drafts, scenario.key)
        session.load()
        session.enqueueInlineQuoteRequest(scenario.request.merge(PendingInlineQuoteRequest(
            23, 35421, listOf(PendingInlineQuoteRequest.Quote(202, 2)),
        )))
        scenario.quoteGate = CompletableDeferred()
        val editor = scenario.editor()
        runCurrent()
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("[quotemsg=101]typed[/quotemsg]\nmy text")))
        scenario.quoteGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, Regex("\\[quotemsg=101").findAll(scenario.body.orEmpty()).count())
        assertEquals(1, Regex("\\[quotemsg=202").findAll(scenario.body.orEmpty()).count())
        assertTrue(scenario.body.orEmpty().endsWith("my text"))
        assertNull(scenario.pending)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `delivery failure blocks POST and retry reconciles existing tags without another GET`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val editor = scenario.editor()
        runCurrent()
        scenario.drafts.saveFailure = IOException("disk")
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertEquals(scenario.request, scenario.pending)
        assertFalse(editor.state.value.canSubmit)
        editor.submit(PostEditorIntent.SubmitClicked())
        scenario.noPost()
        scenario.drafts.saveFailure = null
        editor.submit(PostEditorIntent.ReloadRequested)
        advanceUntilIdle()
        assertNull(scenario.pending)
        assertEquals(listOf(101), scenario.fetchedQuotes)
        assertEquals(1, Regex("\\[quotemsg=101").findAll(scenario.body.orEmpty()).count())
        assertTrue(editor.state.value.canSubmit)
        editor.viewModelScope.cancel()
    }

    @Test
    fun `typing while the committed CAS returns cannot restore a consumed request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val editor = scenario.editor()
        runCurrent()
        val commitReturn = CompletableDeferred<Unit>()
        scenario.drafts.saveReturnGate = commitReturn
        scenario.quoteGate?.complete(Unit)
        runCurrent()
        assertNull(scenario.pending)
        assertFalse(editor.state.value.canSubmit)
        val updated = editor.state.value.draft.text + "continued while saving"
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue(updated)))
        commitReturn.complete(Unit)
        advanceUntilIdle()
        assertEquals(updated, scenario.body)
        assertNull(scenario.pending)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `saved deletion after delivery is never reinserted from a frozen Bundle`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        val saved = SavedStateHandle()
        var editor = scenario.editor(saved)
        advanceUntilIdle()
        val frozen = snapshot(saved)
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("quote deliberately removed")))
        advanceUntilIdle()
        editor.viewModelScope.cancel()
        val fetches = scenario.fetchedQuotes.size
        editor = scenario.editor(SavedStateHandle(frozen))
        advanceUntilIdle()
        assertEquals("quote deliberately removed", editor.state.value.draft.text)
        assertEquals(fetches, scenario.fetchedQuotes.size)
        assertNull(scenario.pending)
        editor.viewModelScope.cancel()
    }

    @Test
    fun `cold recreation offers the delivered body without replaying its retired request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        val saved = SavedStateHandle()
        var editor = scenario.editor(saved, resumeSharedDraft = false)
        advanceUntilIdle()
        val delivered = scenario.body
        val frozen = snapshot(saved)
        editor.viewModelScope.cancel()
        val fetches = scenario.fetchedQuotes.size
        editor = scenario.editor(SavedStateHandle(frozen), resumeSharedDraft = false)
        advanceUntilIdle()
        assertEquals("", editor.state.value.draft.text)
        assertEquals(delivered, editor.state.value.restorableDraft)
        assertTrue(editor.state.value.pendingQuotes.isEmpty())
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        advanceUntilIdle()
        assertEquals(delivered, editor.state.value.draft.text)
        assertEquals(fetches, scenario.fetchedQuotes.size)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `ignore removes only the durable request and a stale Bundle cannot revive it`() = runTest {
        val scenario = Scenario()
        scenario.drafts.preload(scenario.key, Draft("kept body"))
        scenario.enqueue()
        scenario.quoteAvailable = false
        val oldBundle = SavedStateHandle(mapOf("postEditor.pendingQuotes" to intArrayOf(1, 101, -1, 0)))
        var editor = scenario.editor(oldBundle)
        advanceUntilIdle()
        editor.submit(PostEditorIntent.ReloadRequested)
        advanceUntilIdle()
        assertTrue(editor.state.value.canIgnorePendingQuote)
        scenario.drafts.saveFailure = IOException("disk")
        editor.submit(PostEditorIntent.PendingQuoteIgnored)
        advanceUntilIdle()
        assertEquals(scenario.request, scenario.pending)
        assertFalse(editor.state.value.canSubmit)
        scenario.drafts.saveFailure = null
        editor.submit(PostEditorIntent.PendingQuoteIgnored)
        advanceUntilIdle()
        assertEquals("kept body", scenario.body)
        assertNull(scenario.pending)
        editor.viewModelScope.cancel()
        val fetches = scenario.fetchedQuotes.size
        editor = scenario.editor(SavedStateHandle(mapOf("postEditor.pendingQuotes" to intArrayOf(1, 101, -1, 0))))
        advanceUntilIdle()
        assertEquals(fetches, scenario.fetchedQuotes.size)
        assertEquals("kept body", editor.state.value.draft.text)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `discarding the offered draft preserves the new request and awaits storage before GET`() = runTest {
        val scenario = Scenario()
        scenario.drafts.preload(scenario.key, Draft("offered draft"))
        scenario.enqueue()
        val editor = scenario.editor(resumeSharedDraft = false)
        advanceUntilIdle()
        assertTrue(scenario.fetchedQuotes.isEmpty())
        val discardReturn = CompletableDeferred<Unit>()
        scenario.drafts.saveReturnGate = discardReturn
        editor.submit(PostEditorIntent.DraftDiscardRequested)
        runCurrent()
        assertEquals("", scenario.body)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        discardReturn.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(101), scenario.fetchedQuotes)
        assertNull(scenario.pending)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `accepting an offered quote consumes it without another GET`() = runTest {
        val scenario = Scenario()
        val offered = "[quotemsg=101]stored[/quotemsg]\nbody"
        scenario.drafts.preload(scenario.key, Draft(offered))
        scenario.enqueue()
        val editor = scenario.editor(resumeSharedDraft = false)
        advanceUntilIdle()
        editor.submit(PostEditorIntent.DraftRestoreRequested)
        advanceUntilIdle()
        assertEquals(offered, scenario.body)
        assertNull(scenario.pending)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        editor.viewModelScope.cancel()
    }

    @Test
    fun `process death while an offer awaits acceptance keeps its durable quote request`() = runTest {
        restorePendingOffer(discard = false)
    }

    @Test
    fun `process death while an offer awaits refusal keeps its durable quote request`() = runTest {
        restorePendingOffer(discard = true)
    }

    private suspend fun TestScope.restorePendingOffer(discard: Boolean) {
        val scenario = Scenario()
        scenario.drafts.preload(scenario.key, Draft("offered body"))
        scenario.enqueue()
        var editor = scenario.editor(resumeSharedDraft = false)
        advanceUntilIdle()
        assertEquals("offered body", editor.state.value.restorableDraft)
        editor.viewModelScope.cancel()
        editor = scenario.editor(resumeSharedDraft = false)
        advanceUntilIdle()
        assertEquals("offered body", editor.state.value.restorableDraft)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        editor.submit(if (discard) PostEditorIntent.DraftDiscardRequested else PostEditorIntent.DraftRestoreRequested)
        advanceUntilIdle()
        val expected = "[quotemsg=101]quote[/quotemsg]\n" + if (discard) "" else "\noffered body"
        assertEquals(expected, scenario.body)
        assertNull(scenario.pending)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `dormant request does no GET or POST until explicit successful acknowledgement`() = runTest {
        val scenario = Scenario()
        val attempt = scenario.unresolved()
        scenario.enqueue()
        val editor = scenario.editor()
        advanceUntilIdle()
        assertTrue(editor.state.value.hasDormantQuote)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        assertEquals("uncertain body", editor.state.value.draft.text)
        editor.submit(PostEditorIntent.SubmitClicked())
        editor.submit(PostEditorIntent.ReloadRequested)
        editor.submit(PostEditorIntent.ErrorDismissed)
        advanceUntilIdle()
        assertEquals(attempt, scenario.drafts.entries[scenario.key]?.attempt?.token)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        scenario.drafts.finishFailure = IOException("disk")
        editor.submit(PostEditorIntent.PendingQuoteResumed)
        advanceUntilIdle()
        assertTrue(editor.state.value.hasDormantQuote)
        assertEquals(scenario.request, scenario.pending)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        scenario.drafts.finishFailure = null
        editor.submit(PostEditorIntent.PendingQuoteResumed)
        advanceUntilIdle()
        assertNull(scenario.drafts.entries[scenario.key]?.attempt)
        assertNull(scenario.pending)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\nuncertain body", scenario.body)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `ignoring a dormant quote preserves the uncertain attempt and its body`() = runTest {
        val scenario = Scenario()
        val attempt = scenario.unresolved()
        scenario.enqueue()
        val editor = scenario.editor()
        advanceUntilIdle()
        editor.submit(PostEditorIntent.PendingQuoteIgnored)
        advanceUntilIdle()
        assertNull(scenario.pending)
        assertEquals(attempt, scenario.drafts.entries[scenario.key]?.attempt?.token)
        assertEquals(AttemptStatus.UNCERTAIN, scenario.drafts.entries[scenario.key]?.attempt?.status)
        assertEquals("uncertain body", scenario.body)
        assertTrue(editor.state.value.submission is EditorSubmissionState.Uncertain)
        assertEquals(EditorErrors.uncertain, editor.state.value.submitError)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `legacy Bundle coordinates without a durable request are ignored with an explicit notice`() = runTest {
        val scenario = Scenario()
        scenario.drafts.preload(scenario.key, Draft("ordinary v20 draft"))
        val saved = SavedStateHandle(mapOf(
            "postEditor.pendingQuotes" to intArrayOf(1, 101, -1, 0),
            "postEditor.lastAttempt" to arrayListOf("tester", scenario.key, "old-id"),
        ))
        val editor = scenario.editor(saved)
        advanceUntilIdle()
        assertTrue(editor.state.value.legacyQuoteRequestDropped)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        assertNull(scenario.pending)
        assertEquals("ordinary v20 draft", editor.state.value.draft.text)
        assertFalse(saved.contains("postEditor.pendingQuotes"))
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `unreadable durable request blocks every GET and POST until restoration succeeds`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.drafts.loadFailure = UnreadableInlineQuoteRequestException()
        val editor = scenario.editor()
        advanceUntilIdle()
        assertTrue(editor.state.value.quoteStorageUnavailable)
        editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("typed while storage failed")))
        editor.submit(PostEditorIntent.SubmitClicked())
        assertFalse(editor.state.value.canSubmit)
        coVerify(exactly = 0) { scenario.repository.fetchReplyForm(any()) }
        scenario.noPost()
        assertEquals(scenario.request, scenario.pending)
        scenario.drafts.loadFailure = null
        editor.submit(PostEditorIntent.ReloadRequested)
        advanceUntilIdle()
        assertFalse(editor.state.value.quoteStorageUnavailable)
        assertNull(scenario.pending)
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\ntyped while storage failed", scenario.body)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `snapshot while armed cannot replay a quote after confirmation and publication`() = runTest {
        restorePublishedSnapshot(whileArmed = true, beforeBeginReturns = false)
    }

    @Test
    fun `snapshot before begin commits cannot replay a quote after publication`() = runTest {
        restorePublishedSnapshot(whileArmed = false, beforeBeginReturns = false)
    }

    @Test
    fun `snapshot before begin returns cannot replay a quote after publication`() = runTest {
        restorePublishedSnapshot(whileArmed = false, beforeBeginReturns = true)
    }

    private suspend fun TestScope.restorePublishedSnapshot(whileArmed: Boolean, beforeBeginReturns: Boolean) {
        val scenario = Scenario(confirmationRequired = whileArmed)
        scenario.enqueue()
        val saved = SavedStateHandle()
        var editor = scenario.editor(saved)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        if (!whileArmed) {
            if (beforeBeginReturns) scenario.drafts.beginReturnGate = gate else scenario.drafts.beginGate = gate
        }
        editor.submit(PostEditorIntent.SubmitClicked())
        runCurrent()
        val frozen = snapshot(saved)
        assertFalse(saved.contains("postEditor.lastAttempt"))
        if (whileArmed) {
            advanceTimeBy(400)
            editor.submit(PostEditorIntent.SubmitConfirmed(editor.state.value.armToken))
        } else {
            gate.complete(Unit)
        }
        advanceUntilIdle()
        assertEquals(AttemptStatus.PUBLISHED, scenario.drafts.entries[scenario.key]?.attempt?.status)
        val fetches = scenario.fetchedQuotes.size
        editor.viewModelScope.cancel()
        editor = scenario.editor(SavedStateHandle(frozen))
        advanceUntilIdle()
        assertEquals("", editor.state.value.draft.text)
        assertNull(editor.state.value.restorableDraft)
        assertNull(scenario.pending)
        assertEquals(fetches, scenario.fetchedQuotes.size)
        coVerify(exactly = 1) { scenario.repository.submitReply(any(), any(), any(), any()) }
        editor.viewModelScope.cancel()
    }

    @Test
    fun `publication B retires a frozen request from abandoned composition A`() = runTest {
        // Inverts the pre-#1553 expectation formerly at PendingQuoteHandoffTest.kt:522.
        val scenario = Scenario()
        scenario.enqueue()
        scenario.submitGate = CompletableDeferred()
        val saved = SavedStateHandle()
        var editor = scenario.editor(saved)
        advanceUntilIdle()
        editor.submit(PostEditorIntent.SubmitClicked())
        runCurrent()
        val earlier = (editor.state.value.submission as EditorSubmissionState.Sending).attempt
        val frozen = snapshot(saved)
        editor.viewModelScope.cancel()
        scenario.drafts.finishAttempt(earlier, AttemptOutcome.CERTAIN_FAILURE)
        val next = scenario.drafts.beginAttempt("tester", scenario.key, Draft("composition B"), earlier.version)
        val published = (next as BeginAttemptResult.Started).token
        scenario.drafts.finishAttempt(published, AttemptOutcome.PUBLISHED)
        scenario.drafts.erasePublishedContent(published)
        val fetches = scenario.fetchedQuotes.size
        editor = scenario.editor(SavedStateHandle(frozen))
        advanceUntilIdle()
        assertEquals("", editor.state.value.draft.text)
        assertNull(editor.state.value.restorableDraft)
        assertNull(scenario.pending)
        assertEquals(fetches, scenario.fetchedQuotes.size)
        coVerify(exactly = 1) { scenario.repository.submitReply(any(), any(), any(), any()) }
        editor.viewModelScope.cancel()
    }

    @Test
    fun `a new explicit quote after publication starts an empty composition`() = runTest {
        val scenario = Scenario()
        scenario.unresolved().let { scenario.drafts.finishAttempt(it, AttemptOutcome.PUBLISHED) }
        scenario.enqueue()
        val editor = scenario.editor()
        advanceUntilIdle()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n", scenario.body)
        assertNull(scenario.drafts.entries[scenario.key]?.attempt)
        assertNull(scenario.pending)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `late GET after account change cannot deliver or consume the previous request`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteGate = CompletableDeferred()
        val editor = scenario.editor()
        runCurrent()
        scenario.drafts.activeOwner = "other"
        scenario.quoteGate?.complete(Unit)
        advanceUntilIdle()
        assertTrue(scenario.drafts.entries.isEmpty())
        assertFalse(editor.state.value.canSubmit)
        scenario.drafts.activeOwner = "tester"
        assertEquals(scenario.request, scenario.pending)
        scenario.noPost()
        editor.viewModelScope.cancel()
    }

    @Test
    fun `durable escalation never replays a frozen transfer flag`() = runTest {
        val scenario = Scenario()
        scenario.enqueue()
        scenario.quoteAvailable = false
        val frozen = mapOf<String, Any?>(
            "quickReply.pendingTransferred" to true,
            "quickReply.pendingQuotes" to intArrayOf(101, 1, -1, 0),
        )
        val sheet = scenario.sheet(SavedStateHandle(frozen))
        sheet.onSheetOpened()
        advanceUntilIdle()
        sheet.onTextChanged(TextFieldValue("text from the sheet"))
        sheet.onEscalateRequested()
        advanceUntilIdle()
        val handoff = sheet.effects.first() as QuickReplyEffect.EscalateToFullEditor
        assertTrue(handoff.quotes.isEmpty())
        assertEquals(scenario.request, scenario.pending)
        // A new editor instance reads Room; no inline coordinates travel through the handoff.
        scenario.quoteAvailable = true
        val editor = scenario.editor(cards = handoff.quotes)
        advanceUntilIdle()
        assertEquals("[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet", editor.state.value.draft.text)
        assertTrue(editor.state.value.quotes.isEmpty())
        assertNull(scenario.pending)
        editor.viewModelScope.cancel()
        val fetches = scenario.fetchedQuotes.size
        sheet.onSheetOpened() // Return to the retained sheet VM before recreating it.
        advanceUntilIdle()
        assertTrue(sheet.state.value.pendingQuotes.isEmpty())
        assertEquals(fetches, scenario.fetchedQuotes.size)
        sheet.viewModelScope.cancel()
        val restoredSheet = scenario.sheet(SavedStateHandle(frozen))
        restoredSheet.onSheetOpened()
        advanceUntilIdle()
        assertTrue(restoredSheet.state.value.pendingQuotes.isEmpty())
        assertEquals(fetches, scenario.fetchedQuotes.size)
        assertEquals(scenario.body, restoredSheet.state.value.text.text)
        scenario.noPost()
        restoredSheet.viewModelScope.cancel()
    }

    @Test
    fun `card escalation keeps the previews in memory and creates no durable request`() = runTest {
        val scenario = Scenario()
        val cards = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", "preview"))
        // Rendering was decided at the gesture. The current inline preference cannot reinterpret cards.
        val sheet = scenario.sheet()
        sheet.onSheetOpened(cards)
        sheet.onTextChanged(TextFieldValue("body"))
        sheet.onEscalateRequested()
        advanceUntilIdle()
        val handoff = sheet.effects.first() as QuickReplyEffect.EscalateToFullEditor
        assertEquals(cards, handoff.quotes)
        assertNull(scenario.pending)
        val editor = scenario.editor(cards = handoff.quotes)
        advanceUntilIdle()
        assertEquals(cards, editor.state.value.quotes)
        assertEquals("body", editor.state.value.draft.text)
        assertTrue(scenario.fetchedQuotes.isEmpty())
        scenario.noPost()
        editor.viewModelScope.cancel()
        sheet.viewModelScope.cancel()
    }

    private fun snapshot(handle: SavedStateHandle): Map<String, Any?> =
        handle.keys().associateWith { handle.get<Any?>(it) }

    private inner class Scenario(confirmationRequired: Boolean = false) {
        val drafts = VersionedEditorDraftStore()
        val key = EditorDraftKey.reply(23, 35421)
        val request = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        val body: String? get() = drafts.saved[key]?.body
        val pending: PendingInlineQuoteRequest? get() = drafts.saved[key]?.pendingInlineQuoteRequest
        val fetchedQuotes = mutableListOf<Int>()
        var quoteAvailable = true
        var quoteGate: CompletableDeferred<Unit>? = null
        var submitGate: CompletableDeferred<Unit>? = null
        private val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns flowOf(confirmationRequired)
            every { observeQuoteCardsEnabled() } returns flowOf(false)
            every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        }
        private val auth = mockk<AuthRepository> {
            every { observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
        }
        private val localSmileys = mockk<SmileyLocalCollections> {
            every { observe() } returns flowOf(SmileyLocalCollectionSnapshot())
        }
        val repository = mockk<ReplyRepository> {
            coEvery { submitReply(any(), any(), any(), any()) } coAnswers {
                submitGate?.await()
                ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
            }
            coEvery { fetchReplyForm(any()) } coAnswers {
                val number = firstArg<ReplyContext>().quotedNumreponse
                if (number != null) {
                    fetchedQuotes += number
                    quoteGate?.await()
                    if (!quoteAvailable) throw IOException("offline")
                }
                ReplyForm(hashCheck = "FAKE_HASH", sujet = "Topic", hiddenFields = emptyMap(), isAnonymous = false,
                    initialContent = if (number == null) "" else "[quotemsg=$number]quote[/quotemsg]")
            }
        }

        suspend fun enqueue() {
            val session = EditorDraftSession(drafts, key)
            session.load()
            assertTrue(session.enqueueInlineQuoteRequest(request) is StoreWriteResult.Written)
        }

        suspend fun unresolved() = (drafts.beginAttempt("tester", key, Draft("uncertain body"), 0)
            as BeginAttemptResult.Started).token.also { drafts.finishAttempt(it, AttemptOutcome.UNCERTAIN) }

        fun noPost() { coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) } }

        fun sheet(saved: SavedStateHandle = SavedStateHandle()) = QuickReplyViewModel(
            QuickReplyRequest(cat = 23, subcat = 0, topicId = 35421, page = 1), saved,
            repository, TopicReplyQuoteMaterializer(repository),
            EditorSessionResources(drafts, preferences, auth,
                DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }, DiagnosticsLog()),
        )

        fun editor(
            saved: SavedStateHandle = SavedStateHandle(),
            resumeSharedDraft: Boolean = true,
            cards: List<QuoteSelection> = emptyList(),
        ) = PostEditorViewModel(
            request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0,
                initialQuotes = cards, resumeSharedDraft = resumeSharedDraft),
            previewParser = mockk(relaxed = true), replyRepository = repository,
            editPostRepository = mockk(relaxed = true), smileyRepository = mockk(relaxed = true),
            userPreferencesRepository = preferences, draftStore = drafts, diagnostics = DiagnosticsLog(),
            uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
            authRepository = auth, quoteMaterializer = TopicReplyQuoteMaterializer(repository),
            hfrFavoriteSmileyRepository = mockk(relaxed = true), smileyLocalCollections = localSmileys,
            clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }, savedStateHandle = saved,
        )
    }
}
