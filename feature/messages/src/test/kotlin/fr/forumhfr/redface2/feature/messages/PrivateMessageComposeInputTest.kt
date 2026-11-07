package fr.forumhfr.redface2.feature.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
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

    private fun setFieldContent(confirmBeforePosting: Boolean) {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", " pasted"))
        coEvery { repository.fetchComposeForm(any()) } returns ReplyForm(
            hashCheck = "abc", sujet = "", hiddenFields = mapOf("cat" to "prive"),
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
                clock = DiagnosticsClock { 0L },
                smileyRepository = mockk(relaxed = true),
                hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
                smileyLocalCollections = FakeSmileyLocalCollections(),
            )
            vm.onSubjectChanged("title")
        }
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.size(320.dp, 240.dp)) {
                    // Intentionally never collect screen state: the transformation must protect
                    // the field even while the previous editable composition is still displayed.
                    BbcodeTextField(
                        state = vm.bodyFieldState,
                        inputTransformation = vm.bodyInputTransformation,
                        label = "Message",
                        readOnly = false,
                    )
                }
            }
        }
        compose.waitUntil { vm.state.value.formAvailable }
        field().requestFocus()
    }
}
