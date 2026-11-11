package fr.forumhfr.redface2.feature.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
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
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientField
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorSubjectField
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
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
class PrivateMessageComposeInputTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: PrivateMessageComposeViewModel
    private val savedStateHandle = SavedStateHandle()
    private lateinit var registry: SaveableStateRegistry
    private val store = VersionedEditorDraftStore()
    private val uploads = FakeUploadRepository()
    private val repository = mockk<PrivateMessageWriteRepository>()
    private var nowNanos = 0L

    @Test
    fun chipInputBlocksArmingAndPostUntilCommaThenSendsTrimmedChipCsv() {
        setFieldContent(confirmBeforePosting = true, recipientFields = true)
        val recipients = compose.onNodeWithTag("recipients")
        recipients.performTextInput(" Bob  with spaces,tail")
        compose.runOnIdle { vm.bodyFieldState.edit { replace(0, length, "body") } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(EditorRecipientState(listOf("alice", "Bob  with spaces"), "tail"),
                vm.state.value.recipientState)
            assertFalse(vm.state.value.canSubmit)
            vm.onSubmit()
            vm.onSubmitConfirmed(null)
            assertNull(vm.state.value.armToken)
        }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        recipients.performTextInput(",")
        compose.runOnIdle {
            assertTrue(vm.state.value.canSubmit)
            vm.onSubmit()
            assertNotNull(vm.state.value.armToken)
            val arm = vm.state.value.armToken
            // ED-164: exercise the real confirmation guard, with the same displayed token.
            vm.onSubmitConfirmed(vm.state.value.armToken)
            assertEquals(arm, vm.state.value.armToken)
            nowNanos += 399_000_000L
            vm.onSubmitConfirmed(arm)
            assertEquals(arm, vm.state.value.armToken)
        }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
        val attemptGate = CompletableDeferred<Unit>()
        store.beginGate = attemptGate
        compose.runOnIdle {
            nowNanos += 1_000_000L
            vm.onSubmitConfirmed(vm.state.value.armToken)
            assertTrue(vm.state.value.isSubmitting)
        }
        compose.runOnIdle { attemptGate.complete(Unit) }
        compose.waitUntil { !vm.state.value.isSubmitting }
        coVerify(exactly = 1) {
            repository.submitNewMessage(any(), "alice,Bob  with spaces,tail", "title", "body", any())
        }
    }

    @Test
    fun chipFieldsPersistSeparatelyThroughC1WithoutComposeOrHandleContent() {
        setFieldContent(confirmBeforePosting = false, recipientFields = true)
        compose.onNodeWithTag("recipients").performTextInput("Bob with spaces,unfinished")
        compose.onNodeWithTag("subject").performTextInput(" privé".repeat(20))
        compose.runOnIdle { vm.onStopped() }
        compose.waitForIdle()
        compose.runOnIdle {
            val checkpoint = checkNotNull(store.saved[EditorDraftKey.mpCompose()])
            assertEquals(EditorRecipientState(listOf("alice", "Bob with spaces"), "unfinished"),
                EditorRecipientState.decode(checkpoint.recipients))
            assertEquals(("title" + " privé".repeat(20)).take(70), checkpoint.subject)
            val fragments = listOf("alice", "Bob with spaces", "unfinished", "title", "privé")
            val serialized = registry.performSave().toString()
            fragments.forEach { assertFalse(serialized.contains(it)) }
            assertPrivateMessageComposeSavedState(savedStateHandle, fragments)
        }
    }

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
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 1) { repository.submitNewMessage(any(), "alice", "title", "body", any()) }

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
        val key = EditorDraftKey.mpCompose()
        val token = EditorDraftStore.AttemptToken(checkNotNull(store.activeOwner), key, "hydration", 1)
        store.entries[key] = EditorDraftStore.DraftEntry(
            draft = EditorDraftStore.Draft(
                body = "recovered body", subject = "title", recipients = "alice, bo", isPrivate = true,
            ),
            version = token.version,
            attempt = EditorDraftStore.Attempt(token, EditorDraftStore.AttemptStatus.UNCERTAIN),
        )
        setFieldContent(confirmBeforePosting = false)

        compose.runOnIdle {
            // ED-110: historical CSV has no D4 buffer; both recipients are already committed.
            assertEquals(EditorRecipientState(listOf("alice", "bo")), vm.state.value.recipientState)
            assertEquals("alice,bo", vm.state.value.recipientState.csv)
            assertTrue(vm.state.value.canSubmit)
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
        store.preload(EditorDraftKey.mpCompose(), EditorDraftStore.Draft(body = "stored", isPrivate = true))
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
    @OptIn(ExperimentalFoundationApi::class)
    fun bodyHeadersAndUndoStayOutsideTheActualComposeSavedState() {
        setFieldContent(confirmBeforePosting = false, fullEditor = true)
        val initialBytes = compose.runOnIdle { savedStateSize() }
        val body = "private body\n".repeat(1_000)
        val previous = "previous private revision\n".repeat(1_000)
        val fragments = listOf("private body", "previous private revision", "private subject", "alice", "bob", "tail")
        compose.runOnIdle {
            vm.onSubjectChanged("private subject")
            vm.onRecipientStateChanged(EditorRecipientState(listOf("alice", "bob"), "tail"))
            vm.bodyFieldState.edit { replace(0, length, previous) }
            vm.bodyFieldState.edit { replace(0, length, body) }
            assertTrue(vm.bodyFieldState.undoState.canUndo)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            vm.onStopped()
            val serialized = registry.performSave().toString()
            fragments.forEach { assertFalse(serialized.contains(it)) }
            assertEquals(privateMessageComposeCheckpointKeys + PrivateMessageComposeChromeMemory.INITIAL_HEADER_HANDLED,
                savedStateHandle.keys())
            assertPrivateMessageComposeSavedState(savedStateHandle, fragments)
            assertTrue(savedStateSize() < initialBytes + 2_048)
            vm.bodyFieldState.undoState.undo()
            assertEquals(previous, vm.bodyFieldState.text.toString())
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
        } finally { parcel.recycle() }
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

    private fun setFieldContent(
        confirmBeforePosting: Boolean,
        fullEditor: Boolean = false,
        recipientFields: Boolean = false,
    ) {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", " pasted"))
        coEvery { repository.fetchComposeForm(any()) } returns ReplyForm(
            hashCheck = "abc", sujet = "", hiddenFields = mapOf("cat" to "prive"),
            isAnonymous = false,
        )
        coEvery { repository.submitNewMessage(any(), any(), any(), any(), any()) } returns
            ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        val preferences = mockk<UserPreferencesRepository> {
            every { observeConfirmBeforePosting() } returns MutableStateFlow(confirmBeforePosting)
            every { observeEditorImageInsert() } returns MutableStateFlow(EditorImageInsert.REDUCED)
            every { observeImagePickerMode() } returns MutableStateFlow(ImagePickerMode.DEFAULT)
        }
        compose.runOnIdle {
            vm = PrivateMessageComposeViewModel(
                initialRecipient = "alice",
                repository = repository,
                previewParser = BbcodePreviewParser { PostContent(emptyList()) },
                defaultDispatcher = Dispatchers.Default,
                userPreferencesRepository = preferences,
                draftStore = store,
                authRepository = FakeAuthRepository(),
                uploadRepository = uploads,
                imageUploadReader = FakeImageUploadReader(),
                diagnostics = DiagnosticsLog(),
                clock = DiagnosticsClock { nowNanos },
                smileyRepository = mockk(relaxed = true),
                hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
                smileyLocalCollections = FakeSmileyLocalCollections(),
                savedStateHandle = savedStateHandle,
            )
            vm.onSubjectChanged("title")
        }
        compose.setContent {
            val hostRegistry = requireNotNull(LocalSaveableStateRegistry.current)
            registry = remember { SaveableStateRegistry(null, hostRegistry::canBeSaved) }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    if (recipientFields) RecipientFields() else if (fullEditor) ComposerBody()
                    else Box(Modifier.size(320.dp, 240.dp)) {
                        // Keep the old editable composition: synchronous guards must reject blocked input.
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
        if (recipientFields) compose.onNodeWithTag("recipients").requestFocus()
        else if (!fullEditor) field().requestFocus()
    }

    /** Isolated inputs keep the synchronous mutation guard tests independent of surface focus. */
    @Composable
    private fun RecipientFields() {
        val state by vm.state.collectAsState()
        val subjectFocus = remember { FocusRequester() }
        Column {
            EditorRecipientField(state.recipientState, vm.recipientInput, { subjectFocus.requestFocus() },
                Modifier.testTag("recipients"))
            EditorSubjectField(state.subject, vm.subjectInput,
                Modifier.testTag("subject").focusRequester(subjectFocus))
        }
    }

    @Composable
    private fun ComposerBody() {
        val state by vm.state.collectAsState()
        PrivateMessageComposeContent(state, vm.bodyFieldState, vm.bodyInputTransformation,
            vm.recipientInput, vm.subjectInput, remember(vm) { vm.chromeActions() }, vm.smileyPicker,
            bodyScrollState = vm.bodyScrollState, chromeEvents = vm.editorEvents, chromeMemory = vm.chromeMemory,
            windowMetrics = EditorWindowMetrics(640.dp, 0.dp, 0.dp))
    }

}
