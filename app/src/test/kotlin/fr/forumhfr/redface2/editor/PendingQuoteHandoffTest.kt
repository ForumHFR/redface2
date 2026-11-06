package fr.forumhfr.redface2.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** Both features meet here; no feature-to-feature production or test dependency. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingQuoteHandoffTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

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
        assertEquals(scenario.quotes, handoff.quotes)
        val savedState = SavedStateHandle()
        var editor = scenario.editor(savedState, handoff.quotes)
        try {
            advanceUntilIdle()
            assertEquals("text from the sheet", editor.state.value.draft.text)
            assertEquals(EditorErrors.quoteFetchFailed, editor.state.value.submitError)
            editor.viewModelScope.cancel()
            // Same route, SavedStateHandle and durable store, but a genuinely new ViewModel.
            val restoreGate = CompletableDeferred<Unit>()
            scenario.drafts.loadGate = restoreGate
            editor = scenario.editor(savedState, handoff.quotes)
            val writes = scenario.drafts.writes
            editor.submit(PostEditorIntent.ReloadRequested)
            assertEquals(writes, scenario.drafts.writes)
            assertFalse(editor.state.value.isLoadingForm)
            restoreGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("text from the sheet", editor.state.value.draft.text)
            assertFalse(editor.state.value.canSubmit)
            scenario.quoteAvailable = true
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            val expected = "[quotemsg=101]quote[/quotemsg]\n\ntext from the sheet"
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.drafts.saved[EditorDraftKey.reply(23, 35421)]?.body)
            assertTrue(editor.state.value.pendingQuotes.isEmpty())
            assertNull(editor.state.value.submitError)
            editor.submit(PostEditorIntent.ReloadRequested)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            // Recreating again after delivery restores the whole stored body without replaying it.
            editor.viewModelScope.cancel()
            editor = scenario.editor(savedState, handoff.quotes)
            advanceUntilIdle()
            assertEquals(expected, editor.state.value.draft.text)
            assertEquals(expected, scenario.drafts.saved[EditorDraftKey.reply(23, 35421)]?.body)
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
        val quotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "alice", ""))
        var quoteAvailable = false
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
            coEvery { fetchReplyForm(any()) } answers {
                val quoted = firstArg<ReplyContext>().isQuote
                if (quoted && !quoteAvailable) throw IOException("gone")
                ReplyForm(hashCheck = "FAKE_HASH", sujet = "Topic", hiddenFields = emptyMap(),
                    isAnonymous = false, initialContent = if (quoted) "[quotemsg=101]quote[/quotemsg]" else "")
            }
        }
        private val materializer = TopicReplyQuoteMaterializer(repository)

        fun sheet() = QuickReplyViewModel(
            QuickReplyRequest(cat = 23, subcat = 0, topicId = 35421, page = 1), SavedStateHandle(),
            repository, materializer, EditorSessionResources(drafts, preferences, auth, clock, diagnostics),
        )

        fun editor(savedState: SavedStateHandle, quotes: List<QuoteSelection>) = PostEditorViewModel(
            request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0,
                initialQuotes = quotes, resumeSharedDraft = true),
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
