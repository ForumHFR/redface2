package fr.forumhfr.redface2.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorSessionResources
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
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
import fr.forumhfr.redface2.feature.editor.PostEditorEffect
import fr.forumhfr.redface2.feature.editor.PostEditorIntent
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.PostEditorRequest
import fr.forumhfr.redface2.feature.editor.PostEditorViewModel
import fr.forumhfr.redface2.feature.topic.QuickReplyEffect
import fr.forumhfr.redface2.feature.topic.QuickReplyRequest
import fr.forumhfr.redface2.feature.topic.QuickReplyViewModel
import io.mockk.clearMocks
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Both features meet here; no feature-to-feature production or test dependency. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingQuoteHandoffTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `GET after background then autosave survives process death without duplicating the quote`() = runTest {
        recreateAfterQuoteFetch(snapshotBeforeGet = true, autosaveBeforeDeath = true)
    }

    @Test
    fun `GET after background then death before autosave replays the quote onto the durable text`() = runTest {
        recreateAfterQuoteFetch(snapshotBeforeGet = true, autosaveBeforeDeath = false)
    }

    @Test
    fun `GET before background then autosave survives process death without duplicating the quote`() = runTest {
        recreateAfterQuoteFetch(snapshotBeforeGet = false, autosaveBeforeDeath = true)
    }

    @Test
    fun `GET before background then death before autosave retains the request for replay`() = runTest {
        recreateAfterQuoteFetch(snapshotBeforeGet = false, autosaveBeforeDeath = false)
    }

    private suspend fun TestScope.recreateAfterQuoteFetch(snapshotBeforeGet: Boolean, autosaveBeforeDeath: Boolean) {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        val sheet = scenario.sheet()
        sheet.onSheetOpened(scenario.quotes)
        advanceUntilIdle()
        sheet.onTextChanged(TextFieldValue("text from the sheet"))
        sheet.onEscalateRequested()
        advanceUntilIdle()
        val handoff = sheet.effects.first() as QuickReplyEffect.EscalateToFullEditor
        assertEquals(scenario.quotes, handoff.quotes)
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, handoff.quotes)
        try {
            advanceUntilIdle()
            assertEquals("text from the sheet", editor.state.value.draft.text)
            assertEquals(EditorErrors.quoteFetchFailed, editor.state.value.submitError)
            val quoteGate = CompletableDeferred<Unit>()
            scenario.quoteGate = quoteGate
            scenario.quoteAvailable = true
            editor.submit(PostEditorIntent.ReloadRequested)
            runCurrent()
            assertTrue(editor.state.value.isLoadingForm)
            // The background case freezes the Bundle before the GET; the other case freezes it after.
            var savedSnapshot = snapshot(savedState)
            quoteGate.complete(Unit)
            runCurrent() // Complete insertion without advancing the autosave debounce.
            val expected = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals("text from the sheet", scenario.storedBody)
            if (!snapshotBeforeGet) savedSnapshot = snapshot(savedState)
            if (autosaveBeforeDeath) advanceUntilIdle()
            assertEquals(if (autosaveBeforeDeath) expected else "text from the sheet", scenario.storedBody)
            editor.viewModelScope.cancel()
            scenario.quoteGate = null
            val quoteFetches = scenario.fetchedQuotes.size
            val restoreGate = CompletableDeferred<Unit>()
            scenario.drafts.loadGate = restoreGate
            // New process: the in-memory navigation handoff is gone. Only the frozen coordinates
            // in SavedStateHandle and the independently durable draft row survive.
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            val writes = scenario.drafts.writes
            editor.submit(PostEditorIntent.ReloadRequested)
            assertEquals(writes, scenario.drafts.writes)
            assertFalse(editor.state.value.isLoadingForm)
            restoreGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(quoteFetches + if (autosaveBeforeDeath) 0 else 1, scenario.fetchedQuotes.size)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.submitError)
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
            sheet.viewModelScope.cancel()
        }
    }

    @Test
    fun `recreation after deleting a delivered quote reinserts only the missing selection`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        scenario.drafts.preload(EditorDraftKey.reply(23, 35421),
            Draft("text from the sheet"))
        val quotes = scenario.quotes + QuoteSelection(QuoteLocator(1, 202, null), "bob", "")
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, quotes)
        try {
            advanceUntilIdle()
            val withoutSecondQuote = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            editor.submit(PostEditorIntent.ContentChanged(TextFieldValue(withoutSecondQuote)))
            advanceUntilIdle()
            assertEquals(withoutSecondQuote, scenario.storedBody)
            val savedSnapshot = snapshot(savedState)
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            advanceUntilIdle()
            val expected = "[quotemsg=202]quote[/quotemsg]\n\n$withoutSecondQuote"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(listOf(202), scenario.fetchedQuotes.drop(fetches))
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `quote tags typed during GET are reconciled before insertion`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        val gate = CompletableDeferred<Unit>()
        scenario.quoteGate = gate
        val quotes = scenario.quotes + QuoteSelection(QuoteLocator(1, 202, null), "bob", "")
        val editor = scenario.editor(SavedStateHandle(), quotes)
        try {
            runCurrent()
            assertTrue(editor.state.value.isLoadingForm)
            val typed = "[quotemsg=101]typed quote[/quotemsg]\n\ntext from the sheet"
            editor.submit(PostEditorIntent.ContentChanged(TextFieldValue(typed)))
            gate.complete(Unit)
            advanceUntilIdle()
            val expected = "[quotemsg=202]quote[/quotemsg]\n\n$typed"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(listOf(101, 202, 202), scenario.fetchedQuotes)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.submitError)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    /** The Bundle keeps request coordinates only (`InlineQuoteRequest`), never the author nor the excerpt. */
    private fun List<QuoteSelection>.coordinates() = map { it.locator to it.truncate }

    private fun snapshot(handle: SavedStateHandle): Map<String, Any?> = handle.keys().associateWith { key ->
        when (val value = handle.get<Any?>(key)) {
            is IntArray -> value.copyOf()
            is ArrayList<*> -> ArrayList(value)
            else -> value
        }
    }

    @Test
    fun `publication after background snapshot suppresses both saved and route quotes after process death`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        val sendGate = CompletableDeferred<Unit>()
        scenario.submitGate = sendGate
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            advanceUntilIdle()
            editor.submit(PostEditorIntent.SubmitClicked())
            runCurrent()
            assertTrue(editor.state.value.submission is EditorSubmissionState.Sending)
            assertEquals(AttemptStatus.IN_PROGRESS, scenario.drafts.entries[scenario.draftKey]?.attempt?.status)
            // The Bundle is frozen while the POST is pending; its success happens afterwards.
            val savedSnapshot = snapshot(savedState)
            sendGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(editor.effects.first() is PostEditorEffect.SubmitSucceeded)
            val published = scenario.drafts.entries[scenario.draftKey]
            assertEquals(AttemptStatus.PUBLISHED, published?.attempt?.status)
            val writes = scenario.drafts.writes
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            clearMocks(scenario.repository, answers = false)
            // Check the transient handoff and the independently restored navigation arguments.
            for (routeQuotes in listOf(emptyList(), scenario.quotes)) {
                editor = scenario.editor(SavedStateHandle(savedSnapshot), routeQuotes)
                advanceUntilIdle()
                assertEquals("", editor.state.value.draft.text)
                assertNull(editor.state.value.restorableDraft)
                assertTrue(editor.state.value.pendingQuotes.isEmpty())
                assertTrue(editor.state.value.quotes.isEmpty())
                assertFalse(editor.state.value.canSubmit)
                editor.submit(PostEditorIntent.SubmitClicked())
                editor.submit(PostEditorIntent.ReloadRequested)
                advanceUntilIdle()
                assertEquals("", editor.state.value.draft.text)
                assertEquals(published, scenario.drafts.entries[scenario.draftKey])
                assertEquals(writes, scenario.drafts.writes) // No startNewComposition/autosave.
                assertEquals(fetches, scenario.fetchedQuotes.size)
                coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
                editor.viewModelScope.cancel()
            }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `snapshot before begin commits retires the quote request after publication and process death`() = runTest {
        recreateAfterSuspendedBegin(snapshotBeforeCommit = true)
    }

    @Test
    fun `snapshot before begin returns retires the quote request after publication and process death`() = runTest {
        recreateAfterSuspendedBegin(snapshotBeforeCommit = false)
    }

    private suspend fun TestScope.recreateAfterSuspendedBegin(snapshotBeforeCommit: Boolean) {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        val beginGate = CompletableDeferred<Unit>()
        val beginReturnGate = CompletableDeferred<Unit>()
        scenario.drafts.beginGate = beginGate
        scenario.drafts.beginReturnGate = beginReturnGate
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            advanceUntilIdle()
            editor.submit(PostEditorIntent.SubmitClicked())
            runCurrent()
            assertEquals(1, scenario.drafts.beginCalls)
            assertNull(scenario.drafts.entries[scenario.draftKey]?.attempt)
            if (!snapshotBeforeCommit) {
                beginGate.complete(Unit)
                runCurrent()
                assertEquals(AttemptStatus.IN_PROGRESS, scenario.drafts.entries[scenario.draftKey]?.attempt?.status)
            }
            assertTrue(editor.state.value.submission is EditorSubmissionState.PersistingAttempt)
            val prepared = checkNotNull(savedState.get<ArrayList<String>>("postEditor.lastAttempt"))
            assertEquals(listOf("tester", scenario.draftKey), prepared.take(2))
            // Only the handle survives. The frozen Bundle precedes begin's result and any POST.
            val savedSnapshot = snapshot(savedState)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
            beginGate.complete(Unit)
            beginReturnGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(editor.effects.first() is PostEditorEffect.SubmitSucceeded)
            val published = checkNotNull(scenario.drafts.entries[scenario.draftKey])
            val attempt = checkNotNull(published.attempt)
            assertEquals(AttemptStatus.PUBLISHED, attempt.status)
            assertEquals(listOf(attempt.token.owner, attempt.token.key, attempt.token.id), prepared)
            val writes = scenario.drafts.writes
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            clearMocks(scenario.repository, answers = false)
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            advanceUntilIdle()
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertTrue(editor.state.value.quotes.isEmpty())
            assertNull(editor.state.value.restorableDraft)
            assertFalse(editor.state.value.canSubmit)
            editor.submit(PostEditorIntent.SubmitClicked())
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            assertEquals("", editor.state.value.draft.text)
            assertEquals(published, scenario.drafts.entries[scenario.draftKey])
            assertEquals(writes, scenario.drafts.writes)
            assertEquals(fetches, scenario.fetchedQuotes.size)
            coVerify(exactly = 0) { scenario.repository.fetchReplyForm(match { it.isQuote }) }
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `failed begin identity cannot retire the request published by a later retry`() = runTest {
        recreateAfterRejectedBegin(conflict = false)
    }

    @Test
    fun `conflicting begin identity cannot retire the request published by a later retry`() = runTest {
        recreateAfterRejectedBegin(conflict = true)
    }

    private suspend fun TestScope.recreateAfterRejectedBegin(conflict: Boolean) {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            advanceUntilIdle()
            val beginGate = CompletableDeferred<Unit>()
            scenario.drafts.beginGate = beginGate
            editor.submit(PostEditorIntent.SubmitClicked())
            runCurrent()
            assertTrue(editor.state.value.submission is EditorSubmissionState.PersistingAttempt)
            val rejectedIdentity = checkNotNull(savedState.get<ArrayList<String>>("postEditor.lastAttempt"))
            val rejectedSnapshot = snapshot(savedState)
            if (conflict) {
                val entry = scenario.drafts.entries.getValue(scenario.draftKey)
                // A competing autosave advances CAS without changing the body offered on recreation.
                scenario.drafts.saveIfVersion("tester", scenario.draftKey, entry.draft, entry.version)
            } else {
                scenario.drafts.beginFailure = IOException("disk failure")
            }
            beginGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(EditorSubmissionState.Ready, editor.state.value.submission)
            assertEquals(EditorErrors.draftWriteFailed, editor.state.value.submitError)
            assertNull(scenario.drafts.entries[scenario.draftKey]?.attempt)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
            scenario.drafts.beginFailure = null
            scenario.drafts.beginGate = null
            // Reload the current CAS version, keeping the rejected identity in the frozen handle.
            editor.viewModelScope.cancel()
            val retryState = SavedStateHandle(rejectedSnapshot)
            editor = scenario.editor(retryState)
            advanceUntilIdle()
            editor.submit(PostEditorIntent.SubmitClicked())
            val retryIdentity = checkNotNull(retryState.get<ArrayList<String>>("postEditor.lastAttempt"))
            assertNotEquals(rejectedIdentity[2], retryIdentity[2])
            advanceUntilIdle()
            assertTrue(editor.effects.first() is PostEditorEffect.SubmitSucceeded)
            val published = checkNotNull(scenario.drafts.entries[scenario.draftKey]?.attempt)
            assertEquals(AttemptStatus.PUBLISHED, published.status)
            assertEquals(retryIdentity[2], published.token.id)
            assertNotEquals(rejectedIdentity[2], published.token.id)
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            clearMocks(scenario.repository, answers = false)
            editor = scenario.editor(SavedStateHandle(rejectedSnapshot))
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(listOf(101), scenario.fetchedQuotes.drop(fetches))
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `fresh quote navigation starts a new composition despite an earlier published marker`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.publishPreviousComposition()
        scenario.quoteAvailable = true
        val editor = scenario.editor(SavedStateHandle(), scenario.quotes, resumeSharedDraft = false)
        try {
            advanceUntilIdle()
            assertEquals("[quotemsg=101]quote[/quotemsg]\n", editor.state.value.draft.text)
            assertEquals(editor.state.value.draft.text, scenario.storedBody)
            assertEquals(listOf(101), scenario.fetchedQuotes)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(scenario.drafts.entries[scenario.draftKey]?.attempt)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `interrupted quote GET is restored when the published attempt belongs to another composition`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.publishPreviousComposition()
        scenario.quoteAvailable = true
        scenario.quoteGate = CompletableDeferred()
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            runCurrent()
            assertTrue(editor.state.value.isLoadingForm)
            val savedSnapshot = snapshot(savedState)
            editor.viewModelScope.cancel()
            scenario.quoteGate = null
            // Only this instance's coordinates survive; it never launched the earlier attempt.
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            advanceUntilIdle()
            assertEquals("[quotemsg=101]quote[/quotemsg]\n", editor.state.value.draft.text)
            assertEquals(editor.state.value.draft.text, scenario.storedBody)
            assertEquals(listOf(101, 101), scenario.fetchedQuotes)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `a different published token cannot retire the frozen request of an earlier attempt`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        scenario.submitGate = CompletableDeferred()
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            advanceUntilIdle()
            editor.submit(PostEditorIntent.SubmitClicked())
            runCurrent()
            val earlier = (editor.state.value.submission as EditorSubmissionState.Sending).attempt
            val savedSnapshot = snapshot(savedState)
            editor.viewModelScope.cancel()
            // Another writer resolved this attempt and published a different composition meanwhile.
            scenario.drafts.finishAttempt(earlier, AttemptOutcome.CERTAIN_FAILURE)
            val next = scenario.drafts.beginAttempt("tester", scenario.draftKey, Draft("other body"), earlier.version)
            val published = (next as BeginAttemptResult.Started).token
            scenario.drafts.finishAttempt(published, AttemptOutcome.PUBLISHED)
            scenario.drafts.erasePublishedContent(published)
            clearMocks(scenario.repository, answers = false)
            val fetches = scenario.fetchedQuotes.size
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            advanceUntilIdle()
            assertEquals("[quotemsg=101]quote[/quotemsg]\n", editor.state.value.draft.text)
            assertEquals(editor.state.value.draft.text, scenario.storedBody)
            assertEquals(listOf(101), scenario.fetchedQuotes.drop(fetches))
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `replayed escalation handoff after recreation cannot duplicate a durable quote`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        scenario.drafts.preload(EditorDraftKey.reply(23, 35421), Draft("text from the sheet"))
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes)
        try {
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            assertEquals(expected, scenario.storedBody)
            val savedSnapshot = snapshot(savedState)
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            editor = scenario.editor(SavedStateHandle(savedSnapshot), scenario.quotes)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(fetches, scenario.fetchedQuotes.size)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `cold editor keeps the durable restore offer without replaying its quote after process death`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        scenario.quoteAvailable = true
        val gate = CompletableDeferred<Unit>()
        scenario.quoteGate = gate
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes, resumeSharedDraft = false)
        try {
            runCurrent()
            val savedSnapshot = snapshot(savedState)
            editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("text from the sheet")))
            gate.complete(Unit)
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            assertEquals(expected, scenario.storedBody)
            val fetches = scenario.fetchedQuotes.size
            editor.viewModelScope.cancel()
            editor = scenario.editor(SavedStateHandle(savedSnapshot), resumeSharedDraft = false)
            advanceUntilIdle()
            assertEquals("", editor.state.value.draft.text)
            assertEquals(expected, editor.state.value.restorableDraft)
            assertEquals(scenario.quotes.coordinates(), editor.state.value.pendingQuotes.coordinates())
            assertFalse(editor.state.value.canSubmit)
            assertEquals(fetches, scenario.fetchedQuotes.size)
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertEquals(fetches, scenario.fetchedQuotes.size)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `accepting an offer after typing fetches only its missing quote with an immediate GET`() = runTest {
        acceptOfferAfterTyping(suspendGet = false)
    }

    @Test
    fun `accepting an offer serializes a delayed GET and ignores repeated decisions during it`() = runTest {
        acceptOfferAfterTyping(suspendGet = true)
    }

    private suspend fun TestScope.acceptOfferAfterTyping(suspendGet: Boolean) {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        val offered = "[quotemsg=101]stored quote[/quotemsg]\n\nold text"
        scenario.drafts.preload(scenario.draftKey, Draft(offered))
        scenario.quoteAvailable = true
        val gate = CompletableDeferred<Unit>().takeIf { suspendGet }
        scenario.quoteGate = gate
        val quotes = scenario.quotes + QuoteSelection(QuoteLocator(1, 202, null), "bob", "")
        val editor = scenario.editor(SavedStateHandle(), quotes, resumeSharedDraft = false)
        try {
            advanceUntilIdle()
            assertEquals(offered, editor.state.value.restorableDraft)
            assertEquals(quotes, editor.state.value.pendingQuotes)
            editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("fresh text")))
            editor.submit(PostEditorIntent.ReloadRequested)
            editor.submit(PostEditorIntent.SubmitClicked())
            advanceUntilIdle()
            assertEquals("fresh text", editor.state.value.draft.text)
            assertEquals(offered, editor.state.value.restorableDraft)
            assertFalse(editor.state.value.canSubmit)
            assertTrue(scenario.fetchedQuotes.isEmpty()) // Even Reload cannot bypass the offer.
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            runCurrent()
            assertNull(editor.state.value.restorableDraft)
            assertEquals(listOf(202), scenario.fetchedQuotes)
            if (suspendGet) {
                assertTrue(editor.state.value.isLoadingForm)
                assertEquals("fresh text\n\n$offered", editor.state.value.draft.text)
            }
            // A decision after an immediate GET, or during a suspended GET, cannot reapply the offer.
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            editor.submit(PostEditorIntent.DraftDiscardRequested)
            if (suspendGet) editor.submit(PostEditorIntent.SubmitClicked())
            gate?.complete(Unit)
            advanceUntilIdle()
            val expected = "[quotemsg=202]quote[/quotemsg]\n\nfresh text\n\n$offered"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(listOf(202), scenario.fetchedQuotes)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertTrue(editor.state.value.canSubmit)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `discard waits for its durable write before fetching the offered quote and preserves live typing`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        val offered = "[quotemsg=101]stored quote[/quotemsg]\n\nold text"
        scenario.drafts.preload(scenario.draftKey, Draft(offered))
        scenario.quoteAvailable = true
        val quoteGate = CompletableDeferred<Unit>()
        scenario.quoteGate = quoteGate
        val editor = scenario.editor(SavedStateHandle(), scenario.quotes, resumeSharedDraft = false)
        try {
            advanceUntilIdle()
            assertEquals(scenario.quotes, editor.state.value.pendingQuotes)
            assertTrue(scenario.fetchedQuotes.isEmpty())
            editor.submit(PostEditorIntent.ContentChanged(TextFieldValue("fresh text")))
            editor.submit(PostEditorIntent.SubmitClicked())
            val discardGate = CompletableDeferred<Unit>()
            scenario.drafts.saveReturnGate = discardGate
            editor.submit(PostEditorIntent.DraftDiscardRequested)
            runCurrent()
            assertNull(editor.state.value.restorableDraft)
            assertEquals("", scenario.storedBody)
            editor.submit(PostEditorIntent.ReloadRequested)
            editor.submit(PostEditorIntent.SubmitClicked())
            assertTrue(scenario.fetchedQuotes.isEmpty())
            assertFalse(editor.state.value.canSubmit)
            discardGate.complete(Unit)
            runCurrent()
            assertEquals(listOf(101), scenario.fetchedQuotes)
            assertTrue(editor.state.value.isLoadingForm)
            // The offer is settled even while the quote GET is pending; neither decision replays it.
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            editor.submit(PostEditorIntent.DraftDiscardRequested)
            editor.submit(PostEditorIntent.SubmitClicked())
            quoteGate.complete(Unit)
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n\nfresh text"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertTrue(editor.state.value.canSubmit)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `process death before offer acceptance retains the request without fetching the offered quote`() = runTest {
        recreateBeforeOfferDecision(accept = true)
    }

    @Test
    fun `process death before offer refusal retains the request and fetches it after refusal`() = runTest {
        recreateBeforeOfferDecision(accept = false)
    }

    private suspend fun TestScope.recreateBeforeOfferDecision(accept: Boolean) {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        val offered = "[quotemsg=101]stored quote[/quotemsg]\n\nold text"
        scenario.drafts.preload(scenario.draftKey, Draft(offered))
        scenario.quoteAvailable = true
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, scenario.quotes, resumeSharedDraft = false)
        try {
            advanceUntilIdle()
            val savedSnapshot = snapshot(savedState)
            editor.viewModelScope.cancel()
            editor = scenario.editor(SavedStateHandle(savedSnapshot), resumeSharedDraft = false)
            advanceUntilIdle()
            assertEquals("", editor.state.value.draft.text)
            assertEquals(offered, editor.state.value.restorableDraft)
            assertEquals(scenario.quotes.coordinates(), editor.state.value.pendingQuotes.coordinates())
            assertFalse(editor.state.value.canSubmit)
            assertTrue(scenario.fetchedQuotes.isEmpty())
            editor.submit(PostEditorIntent.SubmitClicked())
            editor.submit(
                if (accept) PostEditorIntent.DraftRestoreRequested else PostEditorIntent.DraftDiscardRequested,
            )
            advanceUntilIdle()
            val expected = if (accept) offered else "[quotemsg=101]quote[/quotemsg]\n"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertEquals(if (accept) emptyList<Int>() else listOf(101), scenario.fetchedQuotes)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.restorableDraft)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
        }
    }

    @Test
    fun `escalation restores text before quote retry after process death and inserts only once`() = runTest {
        val scenario = RecreationScenario(DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L })
        val sheet = scenario.sheet()
        sheet.onSheetOpened(scenario.quotes)
        advanceUntilIdle()
        sheet.onTextChanged(TextFieldValue("text from the sheet"))
        sheet.onEscalateRequested()
        advanceUntilIdle()
        val handoff = sheet.effects.first() as QuickReplyEffect.EscalateToFullEditor
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, handoff.quotes)
        try {
            advanceUntilIdle()
            assertEquals("text from the sheet", editor.state.value.draft.text)
            assertEquals(EditorErrors.quoteFetchFailed, editor.state.value.submitError)
            val savedSnapshot = snapshot(savedState)
            editor.viewModelScope.cancel()
            // No escalation payload in the new process. Its quote GET still fails.
            editor = scenario.editor(SavedStateHandle(savedSnapshot))
            advanceUntilIdle()
            assertEquals("text from the sheet", editor.state.value.draft.text)
            assertEquals("text from the sheet", scenario.storedBody)
            assertEquals(EditorErrors.quoteFetchFailed, editor.state.value.submitError)
            assertEquals(scenario.quotes.coordinates(), editor.state.value.pendingQuotes.coordinates())
            assertFalse(editor.state.value.canSubmit)
            editor.submit(PostEditorIntent.SubmitClicked())
            scenario.quoteAvailable = true
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.submitError)
            val fetches = scenario.fetchedQuotes.size
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(fetches, scenario.fetchedQuotes.size)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
            sheet.viewModelScope.cancel()
        }
    }

    @Test
    fun `blocked sheet escalates pending quotes then full editor abandons without POST or text loss`() = runTest {
        val drafts = VersionedEditorDraftStore()
        val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns flowOf(false)
            every { observeQuoteCardsEnabled() } returns flowOf(false)
            every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        }
        val auth = mockk<AuthRepository> {
            every { observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
        }
        val localSmileys = mockk<SmileyLocalCollections> {
            every { observe() } returns flowOf(SmileyLocalCollectionSnapshot())
        }
        val repository = mockk<ReplyRepository> {
            coEvery { fetchReplyForm(any()) } answers {
                if (firstArg<ReplyContext>().isQuote) throw IOException("gone")
                ReplyForm(hashCheck = "FAKE_HASH", sujet = "Topic", hiddenFields = emptyMap(), isAnonymous = false)
            }
        }
        val clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L }
        val materializer = TopicReplyQuoteMaterializer(repository)
        val diagnostics = DiagnosticsLog()
        val sheet = QuickReplyViewModel(
            QuickReplyRequest(cat = 23, subcat = 0, topicId = 35421, page = 1), SavedStateHandle(),
            repository, materializer, EditorSessionResources(drafts, preferences, auth, clock, diagnostics),
        )
        val quotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", ""))
        sheet.onSheetOpened(quotes)
        advanceUntilIdle()
        sheet.onTextChanged(TextFieldValue("text from the sheet"))
        assertFalse(sheet.state.value.canSubmit)
        assertFalse(sheet.state.value.isPreparingQuotes)
        assertFalse(sheet.state.value.isSubmitting)
        assertEquals(EditorErrors.quoteFetchFailed(inline = true, quickReply = true), sheet.state.value.submitError)
        sheet.onEscalateRequested()
        advanceUntilIdle()
        val handoff = sheet.effects.first() as QuickReplyEffect.EscalateToFullEditor
        assertEquals(quotes, handoff.quotes)
        assertEquals("text from the sheet", drafts.saved[EditorDraftKey.reply(23, 35421)]?.body)
        val editor = PostEditorViewModel(
            request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0,
                initialQuotes = handoff.quotes, resumeSharedDraft = true),
            previewParser = mockk(relaxed = true), replyRepository = repository,
            editPostRepository = mockk(relaxed = true), smileyRepository = mockk(relaxed = true),
            userPreferencesRepository = preferences, draftStore = drafts, diagnostics = diagnostics,
            uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
            authRepository = auth, quoteMaterializer = materializer,
            hfrFavoriteSmileyRepository = mockk(relaxed = true), smileyLocalCollections = localSmileys,
            clock = clock,
        )
        try {
            advanceUntilIdle()
            assertFalse(editor.state.value.canSubmit)
            assertEquals("text from the sheet", editor.state.value.draft.text)
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            assertTrue(editor.state.value.canIgnorePendingQuote)
            val typed = TextFieldValue("continued in the editor", TextRange(2, 7))
            editor.submit(PostEditorIntent.ContentChanged(typed))
            editor.submit(PostEditorIntent.PendingQuoteIgnored)
            advanceUntilIdle()
            assertEquals(typed, editor.state.value.draft)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.submitError)
            assertTrue(editor.state.value.isSubmitEnabled)
            coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
            sheet.viewModelScope.cancel()
        }
    }

    private class RecreationScenario(private val clock: DiagnosticsClock) {
        val drafts = VersionedEditorDraftStore()
        val draftKey = EditorDraftKey.reply(23, 35421)
        val quotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", ""))
        var quoteAvailable = false
        var quoteGate: CompletableDeferred<Unit>? = null
        var submitGate: CompletableDeferred<Unit>? = null
        val fetchedQuotes = mutableListOf<Int>()
        val storedBody: String? get() = drafts.saved[EditorDraftKey.reply(23, 35421)]?.body
        private val diagnostics = DiagnosticsLog()
        private val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns flowOf(false)
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
                val numreponse = firstArg<ReplyContext>().quotedNumreponse
                if (numreponse != null) {
                    fetchedQuotes += numreponse
                    quoteGate?.await()
                    if (!quoteAvailable) throw IOException("gone")
                }
                ReplyForm(hashCheck = "FAKE_HASH", sujet = "Topic", hiddenFields = emptyMap(),
                    isAnonymous = false,
                    initialContent = if (numreponse != null) "[quotemsg=$numreponse]quote[/quotemsg]" else "")
            }
        }
        private val materializer = TopicReplyQuoteMaterializer(repository)

        suspend fun publishPreviousComposition() {
            val result = drafts.beginAttempt("tester", draftKey, Draft("previously published"), 0)
            val token = (result as BeginAttemptResult.Started).token
            drafts.finishAttempt(token, AttemptOutcome.PUBLISHED)
            drafts.erasePublishedContent(token)
        }

        fun sheet() = QuickReplyViewModel(
            QuickReplyRequest(cat = 23, subcat = 0, topicId = 35421, page = 1), SavedStateHandle(),
            repository, materializer, EditorSessionResources(drafts, preferences, auth, clock, diagnostics),
        )

        fun editor(
            savedState: SavedStateHandle,
            quotes: List<QuoteSelection> = emptyList(),
            resumeSharedDraft: Boolean = true,
        ) = PostEditorViewModel(
            request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0,
                initialQuotes = quotes, resumeSharedDraft = resumeSharedDraft),
            previewParser = mockk(relaxed = true), replyRepository = repository,
            editPostRepository = mockk(relaxed = true), smileyRepository = mockk(relaxed = true),
            userPreferencesRepository = preferences, draftStore = drafts, diagnostics = diagnostics,
            uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
            authRepository = auth, quoteMaterializer = materializer,
            hfrFavoriteSmileyRepository = mockk(relaxed = true), smileyLocalCollections = localSmileys,
            clock = clock, savedStateHandle = savedState,
        )
    }
}
