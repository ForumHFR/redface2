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
        assertEquals(EditorErrors.quoteFetchFailed, sheet.state.value.submitError)
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
}
