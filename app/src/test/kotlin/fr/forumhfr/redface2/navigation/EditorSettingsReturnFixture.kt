package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollections
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.PostEditorRequest
import fr.forumhfr.redface2.feature.editor.PostEditorViewModel
import fr.forumhfr.redface2.feature.settings.SettingsIntent
import fr.forumhfr.redface2.feature.settings.SettingsState
import fr.forumhfr.redface2.feature.settings.SettingsViewModel
import fr.forumhfr.redface2.feature.settings.StartScreenSettingsState
import fr.forumhfr.redface2.feature.settings.StartScreenSettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class EditorSettingsReturnFixture {
    private val drafts = VersionedEditorDraftStore()
    private val shortcut = MutableStateFlow(EditorShortcut.NONE)
    private val settingsState = MutableStateFlow(SettingsState())
    val settings = mockk<SettingsViewModel> {
        every { state } returns settingsState
        every { submit(any()) } answers {
            val intent = firstArg<SettingsIntent>() as SettingsIntent.SetEditorShortcut
            shortcut.value = intent.shortcut
            settingsState.value = settingsState.value.copy(editorShortcut = intent.shortcut)
        }
    }
    val startScreen = mockk<StartScreenSettingsViewModel> {
        every { state } returns MutableStateFlow(StartScreenSettingsState())
    }
    private val preferences = mockk<UserPreferencesRepository> {
        every { observeConfirmBeforePosting() } returns flowOf(true)
        every { observeQuoteCardsEnabled() } returns flowOf(false)
        every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.REDUCED)
        every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
        every { observeEditorShortcut() } returns shortcut
        every { observeEditorPreviewPositions() } returns flowOf(emptyMap())
    }
    private val repository = mockk<ReplyRepository> {
        coEvery { fetchReplyForm(any()) } returns ReplyForm("FAKE_HASH", "Topic", emptyMap(), isAnonymous = false)
    }
    private val auth = mockk<AuthRepository> {
        every { observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
    }
    private val smileys = mockk<SmileyLocalCollections> {
        every { observe() } returns flowOf(SmileyLocalCollectionSnapshot())
    }
    private val parser = mockk<BbcodePreviewParser> {
        every { parsePreview(any()) } returns PostContent(emptyList())
    }

    fun editor() = PostEditorViewModel(
        request = PostEditorRequest(PostEditorMode.Reply, 23, 42, null, 1, 0),
        previewParser = parser, defaultDispatcher = Dispatchers.Unconfined, replyRepository = repository,
        editPostRepository = mockk(relaxed = true), smileyRepository = mockk(relaxed = true),
        userPreferencesRepository = preferences, draftStore = drafts, diagnostics = DiagnosticsLog(),
        uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
        authRepository = auth, quoteMaterializer = TopicReplyQuoteMaterializer(repository),
        hfrFavoriteSmileyRepository = mockk(relaxed = true), smileyLocalCollections = smileys,
        clock = DiagnosticsClock { 0L },
    )

    fun savedBody() = drafts.saved[EditorDraftKey.reply(23, 42)]?.body
    fun noPost() { coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) } }
}
