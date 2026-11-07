package fr.forumhfr.redface2.feature.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
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

/** #1512 — real BTF2 inputs with deliberately stale editable chrome, before readOnly catches up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PrivateMessageReplyInputTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: PrivateMessageReplyViewModel
    private val savedStateHandle = SavedStateHandle()
    private lateinit var registry: SaveableStateRegistry
    private val store = VersionedEditorDraftStore()
    private val uploads = FakeUploadRepository()
    private val repository = mockk<PrivateMessageWriteRepository>()

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    @Test
    fun typingAndPasteDisarmBeforeRecomposition() {
        setFieldContent(confirmBeforePosting = true)
        field().performTextInput("body")
        compose.runOnIdle {
            vm.onSubmit()
            assertNotNull(vm.state.value.armToken)
        }
        field().performTextInput(" typed")
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed", vm.bodyFieldState.text.toString())
            vm.onSubmit()
            assertNotNull(vm.state.value.armToken)
        }
        paste()
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body typed pasted", vm.bodyFieldState.text.toString())
        }
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun blockedInputIsRejectedBeforeRecomposition() {
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("body")
        val attemptGate = CompletableDeferred<Unit>()
        store.beginGate = attemptGate
        compose.runOnIdle {
            vm.onSubmit()
            assertTrue(vm.state.value.submission.blocksBodyMutation)
        }
        assertInputRejected()
        compose.runOnIdle { attemptGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isSubmitting }
        coVerify(exactly = 1) { repository.submitReply(any(), any(), "body", any(), any()) }

        val uploadGate = CompletableDeferred<Unit>()
        uploads.uploadGate = uploadGate
        compose.runOnIdle {
            vm.onImagesPicked(listOf("content://pick/1"))
            assertTrue(vm.state.value.isUploading)
        }
        assertInputRejected()
        compose.runOnIdle { uploadGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isUploading }
        compose.runOnIdle {
            assertEquals(
                "body[url=https://rehost.diberie.com/Picture/Get/f/1]" +
                    "[img]https://rehost.diberie.com/Picture/Get/f/1[/img][/url]",
                vm.bodyFieldState.text.toString(),
            )
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun initialHydrationStartsNewUndoHistory() {
        val key = EditorDraftKey.mpReply(3195237)
        val token = EditorDraftStore.AttemptToken(checkNotNull(store.activeOwner), key, "hydration", 1)
        store.entries[key] = EditorDraftStore.DraftEntry(
            draft = EditorDraftStore.Draft(
                body = "recovered body", isPrivate = true,
            ),
            version = token.version,
            attempt = EditorDraftStore.Attempt(token, EditorDraftStore.AttemptStatus.UNCERTAIN),
        )
        setFieldContent(confirmBeforePosting = false)

        compose.runOnIdle {
            val state = vm.bodyFieldState
            assertEquals("recovered body", state.text.toString())
            assertFalse(state.undoState.canUndo)
            state.undoState.undo()
            assertEquals("recovered body", state.text.toString())
            assertFalse(state.undoState.canUndo)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun requestedRestoreRemainsUndoable() {
        store.preload(EditorDraftKey.mpReply(3195237), EditorDraftStore.Draft(body = "stored", isPrivate = true))
        setFieldContent(confirmBeforePosting = false)
        field().performTextInput("typed")

        compose.runOnIdle {
            assertEquals("stored", vm.state.value.restorableDraft)
            vm.onDraftRestoreRequested()
            val state = vm.bodyFieldState
            assertEquals("typed\n\nstored", state.text.toString())
            assertTrue(state.undoState.canUndo)
            state.undoState.undo()
            assertEquals("typed", state.text.toString())
        }
    }

    @Test
    fun selectionAloneDisarmsBeforeRecomposition() {
        setFieldContent(confirmBeforePosting = true)
        field().performTextInput("body")
        compose.runOnIdle {
            vm.onSubmit()
            assertNotNull(vm.state.value.armToken)
        }
        field().performTextInputSelection(TextRange(1, 3))
        compose.runOnIdle {
            assertNull(vm.state.value.armToken)
            assertEquals("body", vm.bodyFieldState.text.toString())
            assertEquals(TextRange(1, 3), vm.bodyFieldState.selection)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun quoteHydrationStartsNewUndoHistory() {
        setFieldContent(confirmBeforePosting = false, initialQuote = "seed\n")
        compose.runOnIdle {
            assertEquals("seed\n", vm.bodyFieldState.text.toString())
            assertEquals("seed\n", vm.state.value.draftHydratedContent)
            assertFalse(vm.bodyFieldState.undoState.canUndo)
        }
    }

    @Test
    @OptIn(ExperimentalFoundationApi::class)
    fun bodyAndUndoHistoryStayOutsideSavedState() {
        setFieldContent(confirmBeforePosting = false)
        val initialBytes = compose.runOnIdle { savedStateSize() }
        val privateBody = "private reply body\n".repeat(1_000)
        val previousBody = "previous private revision\n".repeat(1_000)
        compose.runOnIdle {
            vm.bodyFieldState.edit { append(previousBody) }
            vm.bodyFieldState.edit { replace(0, length, privateBody) }
            assertTrue(vm.bodyFieldState.undoState.canUndo)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val saved = registry.performSave().toString()
            assertFalse(saved.contains("private reply body"))
            assertFalse(saved.contains("previous private revision"))
            assertTrue(savedStateHandle.keys().isEmpty())
            assertTrue("Bundle must stay independent of private text and undo history", savedStateSize() < initialBytes + 2_048)
            vm.bodyFieldState.undoState.undo()
            assertEquals(previousBody, vm.bodyFieldState.text.toString())
        }
    }

    private fun savedStateSize(): Int {
        val bundle = Bundle()
        registry.performSave().forEach { (key, values) ->
            @Suppress("UNCHECKED_CAST")
            bundle.putParcelableArrayList(key, ArrayList(values) as ArrayList<Parcelable?>)
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
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
        // Real platform clipboard + BTF2 paste semantics, rather than state.edit or SetText.
        field().performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitForIdle()
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun setFieldContent(confirmBeforePosting: Boolean, initialQuote: String = "") {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", " pasted"))
        coEvery { repository.fetchReplyForm(any(), any()) } returns ReplyForm(
            hashCheck = "abc", sujet = "", hiddenFields = mapOf("cat" to "prive"),
            isAnonymous = false, initialContent = initialQuote,
        )
        coEvery { repository.submitReply(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
        }
        compose.runOnIdle {
            vm = PrivateMessageReplyViewModel(
                request = PrivateMessageReplyRequest(threadId = 3195237, page = 1),
                repository = repository,
                previewParser = BbcodePreviewParser { PostContent(emptyList()) },
                defaultDispatcher = Dispatchers.Default,
                userPreferencesRepository = preferences,
                draftStore = store,
                authRepository = FakeAuthRepository(),
                uploadRepository = uploads,
                imageUploadReader = FakeImageUploadReader(),
                diagnostics = DiagnosticsLog(),
                clock = DiagnosticsClock { 0L },
                smileyRepository = mockk(relaxed = true),
                hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
                smileyLocalCollections = FakeSmileyLocalCollections(),
                savedStateHandle = savedStateHandle,
            )
        }
        compose.setContent {
            val hostRegistry = checkNotNull(LocalSaveableStateRegistry.current)
            registry = remember { SaveableStateRegistry(restoredValues = null, canBeSaved = hostRegistry::canBeSaved) }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        // Keep editable chrome stale: synchronous guards must reject blocked inputs.
                        BbcodeTextField(
                            state = vm.bodyFieldState,
                            inputTransformation = vm.bodyInputTransformation,
                            label = "Message",
                            readOnly = false,
                        )
                    }
                }
            }
        }
        compose.waitUntil { vm.state.value.formAvailable }
        field().requestFocus()
    }
}
