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
import androidx.compose.ui.text.input.TextFieldValue
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
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicPollForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class TopicFormInputTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: TopicFormViewModel
    private lateinit var scrollState: ScrollState
    private var latestTextLayout: TextLayoutResult? = null
    private val store = VersionedEditorDraftStore()
    private val repository = mockk<TopicFormRepository>()
    private val uploads = mockk<UploadRepository>()
    private var uploadGate: CompletableDeferred<Unit>? = null
    private var postStarted = false
    private var postGate: CompletableDeferred<Unit>? = null

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test
    fun typingAndPasteDisarmBeforeRecomposition() {
        setFieldContent(confirmBeforePosting = true)
        field().performTextInput("body")
        compose.runOnIdle {
            vm.submit(TopicFormIntent.SubmitClicked())
            assertNotNull(vm.state.value.armToken)
        }
        field().performTextInput(" typed")
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed", vm.bodyFieldState.text.toString())
            vm.submit(TopicFormIntent.SubmitClicked())
            assertNotNull(vm.state.value.armToken)
        }
        paste()
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed pasted", vm.bodyFieldState.text.toString())
        }
        coVerify(exactly = 0) { repository.submitNewTopic(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun selectionOnlyKeepsArmedSubmissionAndDoesNotMarkBodyEdited() {
        setFieldContent(confirmBeforePosting = true, editing = true)
        val bodyEdited = TopicFormViewModel::class.java.getDeclaredField("bodyEditedBeforeHydration").apply {
            isAccessible = true
        }
        val token = compose.runOnIdle {
            assertFalse(bodyEdited.getBoolean(vm))
            vm.submit(TopicFormIntent.SubmitClicked())
            requireNotNull(vm.state.value.armToken)
        }
        // Exercise user selection through the field, without programmatic TextFieldState.edit.
        field().performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 3, false)) }
        compose.runOnIdle {
            assertEquals("seed", vm.bodyFieldState.text.toString())
            assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
            assertEquals(token, vm.state.value.armToken)
            assertTrue(vm.state.value.showSubmitConfirmation)
            assertFalse(bodyEdited.getBoolean(vm))
        }
        coVerify(exactly = 0) { repository.submitEditFirstPost(any(), any(), any(), any(), any(), any()) }
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
            vm.submit(TopicFormIntent.SubmitClicked())
            assertTrue(vm.state.value.submission.blocksBodyMutation)
        }
        assertInputRejected()
        compose.runOnIdle { attemptGate.complete(Unit) }
        compose.waitUntil { postStarted }
        assertInputRejected()
        compose.runOnIdle { sendingGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isSubmitting }
        coVerify(exactly = 1) { repository.submitNewTopic(any(), any(), "title", "body", 401, any()) }

        val uploadingGate = CompletableDeferred<Unit>()
        uploadGate = uploadingGate
        compose.runOnIdle {
            vm.submit(TopicFormIntent.ImagesPicked(listOf("content://pick/1")))
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
        store.preload(EditorDraftKey.newTopic(23), EditorDraftStore.Draft(body = "stored"))
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("typed")
        compose.runOnIdle {
            val selectionBeforeRestore = vm.bodyFieldState.selection
            vm.submit(TopicFormIntent.DraftRestoreRequested)
            val state = vm.bodyFieldState
            assertEquals("typed\n\nstored", state.text.toString())
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
        store.preload(EditorDraftKey.editFirstPost(23, 640), EditorDraftStore.Draft(body = draft))
        setFieldContent(confirmBeforePosting = false, editing = true)
        field().performTextInput(suffix)
        compose.runOnIdle {
            vm.bodyFieldState.edit { selection = TextRange(0) }
        }
        compose.waitForIdle()
        compose.runOnIdle { vm.submit(TopicFormIntent.DraftRestoreRequested) }
        compose.waitForIdle()
        compose.runOnIdle {
            val state = vm.bodyFieldState
            val expected = draft + "\n\n" + suffix
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
        val form = TopicForm(
            hashCheck = "abc", subject = "title", initialContent = "seed", selectedSubcat = 401,
            subcategoryChoices = emptyList(), hiddenFields = emptyMap(), options = ReplyFormOptions(),
            msgIcon = "1", poll = TopicPollForm(false, emptyMap()), isAnonymous = false,
        )
        coEvery { repository.fetchNewTopicForm(any()) } returns form
        coEvery { repository.fetchEditFirstPostForm(any()) } returns form
        coEvery { repository.submitNewTopic(any(), any(), any(), any(), any(), any()) } coAnswers {
            postStarted = true
            postGate?.await()
            NewTopicSubmitResult.Failure(ReplyFailureReason.EmptyMessage)
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
        }
        compose.runOnIdle {
            vm = TopicFormViewModel(
                request = if (editing) {
                    TopicFormRequest(TopicFormMode.EditFirstPost, 23, 401, 35421, 1, 640)
                } else {
                    TopicFormRequest(TopicFormMode.New, 23, 401, null, null, null)
                },
                previewParser = BbcodePreviewParser { PostContent(emptyList()) },
                defaultDispatcher = Dispatchers.Default,
                topicFormRepository = repository,
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
            vm.submit(TopicFormIntent.SubjectChanged(TextFieldValue("title")))
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
