package fr.forumhfr.redface2.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorSessionResources
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

    private fun snapshot(handle: SavedStateHandle): Map<String, Any?> = handle.keys().associateWith { key ->
        val value = handle.get<Any?>(key)
        if (value is IntArray) value.copyOf() else value
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
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertEquals(fetches, scenario.fetchedQuotes.size)
            editor.submit(PostEditorIntent.DraftRestoreRequested)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.storedBody)
            coVerify(exactly = 0) { scenario.repository.submitReply(any(), any(), any(), any()) }
        } finally {
            editor.viewModelScope.cancel()
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
        val quotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", ""))
        var quoteAvailable = false
        var quoteGate: CompletableDeferred<Unit>? = null
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
