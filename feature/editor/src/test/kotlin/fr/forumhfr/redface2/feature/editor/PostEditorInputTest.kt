package fr.forumhfr.redface2.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.ImageUploadReader
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1512 — real typing/paste against a deliberately stale editable composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostEditorInputTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: PostEditorViewModel
    private lateinit var scrollState: ScrollState
    private var latestTextLayout: TextLayoutResult? = null
    private val store = VersionedEditorDraftStore()
    private val repository = mockk<ReplyRepository>()
    private val uploads = mockk<UploadRepository>()
    private var uploadGate: CompletableDeferred<Unit>? = null
    private var postStarted = false
    private var postGate: CompletableDeferred<Unit>? = null
    private var editFormFailure: IOException? = null

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun editTypingAndPasteStayLockedAfterFailureUntilReloadEvenWithStaleEditableField() {
        editFormFailure = IOException("offline")
        setFieldContent(confirmBeforePosting = false, editing = true)
        compose.runOnIdle {
            assertTrue(vm.state.value.isAwaitingInitialBody)
            assertFalse(vm.state.value.canSubmit)
        }
        // The composition deliberately keeps readOnly=false: only InputTransformation can reject these.
        field().performTextInput("partial post")
        paste()
        compose.runOnIdle {
            assertEquals("", vm.bodyFieldState.text.toString())
            assertFalse(vm.bodyFieldState.undoState.canUndo)
            editFormFailure = null
            vm.submit(PostEditorIntent.ReloadRequested)
        }
        compose.waitUntil { !vm.state.value.isLoadingForm }
        compose.runOnIdle {
            assertEquals("seed", vm.bodyFieldState.text.toString())
            assertFalse(vm.state.value.isAwaitingInitialBody)
            assertFalse(vm.bodyFieldState.undoState.canUndo)
        }
        field().performTextInput(" complete")
        paste()
        compose.runOnIdle {
            assertEquals("seed complete pasted", vm.bodyFieldState.text.toString())
        }
    }

    @Test
    fun typingAndPasteDisarmBeforeRecomposition() {
        setFieldContent(confirmBeforePosting = true)
        field().performTextInput("body")
        compose.runOnIdle {
            vm.submit(PostEditorIntent.SubmitClicked())
            assertNotNull(vm.state.value.armToken)
        }
        field().performTextInput(" typed")
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed", vm.bodyFieldState.text.toString())
            vm.submit(PostEditorIntent.SubmitClicked())
            assertNotNull(vm.state.value.armToken)
        }
        paste()
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed pasted", vm.bodyFieldState.text.toString())
        }
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun selectionOnlyDisarmsLikeAnyFieldInteractionAndAddsNoHistory() {
        setFieldContent(confirmBeforePosting = true, editing = true)
        compose.runOnIdle {
            assertFalse(vm.bodyFieldState.undoState.canUndo)
            vm.submit(PostEditorIntent.SubmitClicked())
            requireNotNull(vm.state.value.armToken)
        }
        // Exercise user selection through the field, without programmatic TextFieldState.edit.
        // Any field interaction disarms; selecting text alone is not a body edit.
        field().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 3, false)) }
        compose.runOnIdle {
            assertEquals("seed", vm.bodyFieldState.text.toString())
            assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
            assertNull(vm.state.value.armToken)
            // The hydration-baseline consequence is pinned in PostEditorViewModelTest (late hydration).
            assertFalse(vm.bodyFieldState.undoState.canUndo)
        }
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any()) }
    }

    @Test
    fun blockedInputIsRejectedBeforeRecompositionDuringPersistenceSendingAndUpload() {
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("body")
        val attemptGate = CompletableDeferred<Unit>()
        val sendingGate = CompletableDeferred<Unit>()
        store.beginGate = attemptGate
        postGate = sendingGate
        compose.runOnIdle {
            vm.submit(PostEditorIntent.SubmitClicked())
            assertTrue(vm.state.value.submission.blocksBodyMutation)
        }
        assertInputRejected()
        compose.runOnIdle { attemptGate.complete(Unit) }
        compose.waitUntil { postStarted }
        assertInputRejected()
        compose.runOnIdle { sendingGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isSubmitting }
        coVerify(exactly = 1) { repository.submitReply(any(), any(), "body", any()) }

        val uploadingGate = CompletableDeferred<Unit>()
        uploadGate = uploadingGate
        compose.runOnIdle {
            vm.submit(PostEditorIntent.ImagesPicked(listOf("content://pick/1")))
            assertTrue(vm.state.value.isUploading)
        }
        assertInputRejected()
        compose.runOnIdle { uploadingGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isUploading }
        compose.runOnIdle {
            assertEquals("body[url=$IMAGE_URL][img]$IMAGE_URL[/img][/url]", vm.bodyFieldState.text.toString())
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun initialHydrationStartsNewUndoHistory() {
        setFieldContent(confirmBeforePosting = false, editing = true)
        compose.runOnIdle {
            val state = vm.bodyFieldState
            assertEquals("seed", state.text.toString())
            assertFalse(state.undoState.canUndo)
            state.undoState.undo()
            assertEquals("seed", state.text.toString())
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun requestedRestoreRemainsUndoable() {
        store.preload(EditorDraftKey.reply(23, 35421), EditorDraftStore.Draft(body = "stored"))
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("typed")
        compose.runOnIdle {
            val selectionBeforeRestore = vm.bodyFieldState.selection
            vm.submit(PostEditorIntent.DraftRestoreRequested)
            val state = vm.bodyFieldState
            // v0.7.8 ED-89 case 4, empty seed: D, then the typing.
            assertEquals("stored\n\ntyped", state.text.toString())
            assertEquals(TextRange(state.text.length), state.selection)
            assertTrue(state.undoState.canUndo)
            state.undoState.undo()
            assertEquals("typed", state.text.toString())
            assertEquals(selectionBeforeRestore, state.selection)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun restoreKeepsLongSuffixCaretVisibleAtEndAndUndoesOnce() {
        val draft = (1..80).joinToString("\n") { "draft line $it" }
        val suffix = (1..80).joinToString("\n", prefix = "\n") { "typed line $it" }
        store.preload(EditorDraftKey.editPost(23, 101), EditorDraftStore.Draft(body = draft))
        setFieldContent(confirmBeforePosting = false, editing = true)
        field().performTextInput(suffix)
        compose.runOnIdle {
            vm.bodyFieldState.edit { selection = TextRange(0) }
        }
        compose.waitForIdle()
        compose.runOnIdle { vm.submit(PostEditorIntent.DraftRestoreRequested) }
        compose.waitForIdle()
        compose.runOnIdle {
            val state = vm.bodyFieldState
            val expected = draft + "\n\n" + suffix.trimStart()
            assertEquals(expected, state.text.toString())
            assertEquals(TextRange(expected.length), state.selection)
            val layout = requireNotNull(latestTextLayout)
            val caretLine = layout.getLineForOffset(state.selection.end)
            val viewportTop = scrollState.value.toFloat()
            val viewportBottom = viewportTop + layout.size.height - scrollState.maxValue
            assertTrue(layout.getLineTop(caretLine) >= viewportTop - 1f)
            assertTrue(layout.getLineBottom(caretLine) <= viewportBottom + 1f)
            state.undoState.undo()
            assertEquals("seed" + suffix, state.text.toString())
            assertEquals(TextRange(0), state.selection)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun closeSavesLiveUndoBeforeMirrorNotification() {
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("body")
        compose.runOnIdle { vm.submit(PostEditorIntent.ToolbarActionClicked(BbcodeAction.Bold)) }
        compose.runOnIdle {
            val beforeUndo = vm.state.value.bodyText
            vm.bodyFieldState.undoState.undo()
            assertEquals("body", vm.bodyFieldState.text.toString())
            assertEquals(beforeUndo, vm.state.value.bodyText)
            vm.submit(PostEditorIntent.CloseRequested)
        }
        compose.waitUntil { store.saved[EditorDraftKey.reply(23, 35421)]?.body == "body" }
    }

    private fun assertInputRejected() {
        val selection = compose.runOnIdle { vm.bodyFieldState.selection }
        field().performTextInput(" rejected typing")
        paste()
        compose.runOnIdle {
            assertEquals("body", vm.bodyFieldState.text.toString())
            assertEquals(selection, vm.bodyFieldState.selection)
        }
    }

    private fun paste() {
        field().performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitForIdle()
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun setFieldContent(confirmBeforePosting: Boolean, editing: Boolean = false) {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", " pasted"))
        val form = ReplyForm(
            hashCheck = "abc", sujet = "title", initialContent = "seed",
            hiddenFields = emptyMap(), options = ReplyFormOptions(), msgIcon = "1", isAnonymous = false,
        )
        val editRepository = mockk<EditPostRepository> {
            coEvery { fetchEditPostForm(any()) } coAnswers {
                editFormFailure?.let { throw it }
                form
            }
        }
        coEvery { repository.fetchReplyForm(any()) } returns form.copy(initialContent = "")
        coEvery { repository.submitReply(any(), any(), any(), any()) } coAnswers {
            postStarted = true
            postGate?.await()
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        }
        coEvery { uploads.uploadWithCurrentProvider(any(), any()) } coAnswers {
            uploadGate?.await()
            UploadedImage(UploadProviderId.DIBERIE, IMAGE_URL, null, null, null, null)
        }
        val auth = mockk<AuthRepository> {
            every { observeAuthState() } returns MutableStateFlow(AuthState.Authenticated("tester"))
        }
        val reader = mockk<ImageUploadReader> {
            coEvery { read(any()) } returns ImageUpload(byteArrayOf(0), "image/png", null)
        }
        val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
            every { observeEditorShortcut() } returns MutableStateFlow(EditorShortcut.NONE)
        }
        compose.runOnIdle {
            vm = PostEditorViewModel(
                request = PostEditorRequest(
                    if (editing) PostEditorMode.Edit else PostEditorMode.Reply,
                    23, 35421, if (editing) 101 else null, 1, 0,
                ),
                previewParser = BbcodePreviewParser { PostContent(emptyList()) },
                defaultDispatcher = Dispatchers.Default,
                replyRepository = repository,
                editPostRepository = editRepository,
                quoteMaterializer = TopicReplyQuoteMaterializer(repository),
                userPreferencesRepository = preferences,
                draftStore = store,
                authRepository = auth,
                uploadRepository = uploads,
                imageUploadReader = reader,
                diagnostics = DiagnosticsLog(),
                clock = DiagnosticsClock { 0L },
                smileyRepository = mockk(relaxed = true),
                hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
                smileyLocalCollections = FakeSmileyLocalCollections(),
            )
        }
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.size(320.dp, 240.dp)) {
                    scrollState = rememberScrollState()
                    // Never collect screen state: readOnly intentionally stays editable.
                    BbcodeTextField(
                        state = vm.bodyFieldState,
                        inputTransformation = vm.bodyInputTransformation,
                        label = "Message",
                        readOnly = false,
                        scrollState = scrollState,
                        onTextLayout = { getResult -> latestTextLayout = getResult() },
                    )
                }
            }
        }
        compose.waitUntil { !vm.state.value.isLoadingForm }
        field().requestFocus()
    }

    private companion object {
        const val IMAGE_URL = "https://rehost.diberie.com/Picture/Get/f/1"
    }
}
