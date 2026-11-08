package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #447 — MP reply uses the same short-window budget and alert-first ordering as topic editors. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageEditorLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `reply body keeps 160 dp field and both draft actions on short viewport`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ReplyEditorBody(
                    state = PrivateMessageReplyUiState(
                        isLoadingForm = false,
                        formAvailable = true,
                        restorableDraft = "Brouillon à restaurer",
                    ),
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onToolbarAction = {},
                    onTogglePreview = {},
                    onErrorDismissed = {},
                    onDraftRestore = {},
                    onDraftDiscard = {},
                    onImagePickerEvent = {},
                    onUploadErrorDismissed = {},
                    onManageRecipients = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        assertDraftActionsFullyVisible()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h200dp-xxhdpi")
    fun `reply keeps both draft actions fully visible below the 220 dp budget threshold`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ReplyEditorBody(
                    state = PrivateMessageReplyUiState(
                        isLoadingForm = false,
                        formAvailable = true,
                        restorableDraft = "Brouillon à restaurer",
                    ),
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onToolbarAction = {},
                    onTogglePreview = {},
                    onErrorDismissed = {},
                    onDraftRestore = {},
                    onDraftDiscard = {},
                    onImagePickerEvent = {},
                    onUploadErrorDismissed = {},
                    onManageRecipients = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ReplyEditorBody has 176 dp after its vertical padding on this 200 dp window.
        assertDraftActionsFullyVisible()
    }

    @Test
    fun replyRestoreDisabledWhileSeedFormLoadsAndEnabledAfterFailure() {
        val state = mutableStateOf(PrivateMessageReplyUiState(restorableDraft = "Brouillon à restaurer"))
        setReplyContent { state.value }

        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        assertDraftActionsFullyVisible()

        compose.runOnIdle {
            state.value = state.value.copy(isLoadingForm = false, formError = true)
        }
        compose.onNodeWithText("Restaurer").assertIsEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        assertDraftActionsFullyVisible()

        // Editor state: the offer moves into the editor, still a single banner (onNodeWithText fails on two).
        compose.runOnIdle {
            state.value = state.value.copy(isLoadingForm = false, formAvailable = true, formError = false)
        }
        compose.onNodeWithText("Restaurer").assertIsEnabled()

        // Refetch over an available form: one banner, inactive until the seed is known again.
        compose.runOnIdle { state.value = state.value.copy(isLoadingForm = true) }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
    }

    @Test
    fun replyRestoreDisabledDuringUpload() {
        val state = mutableStateOf(PrivateMessageReplyUiState(
            isLoadingForm = false,
            formAvailable = true,
            restorableDraft = "Brouillon à restaurer",
            isUploading = true,
        ))
        setReplyContent { state.value }

        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()

        compose.runOnIdle { state.value = state.value.copy(isUploading = false) }
        compose.onNodeWithText("Restaurer").assertIsEnabled()

        // The submission locks the body (accepted uncertain attempt): Restore follows the ViewModel refusal.
        compose.runOnIdle {
            state.value = state.value.copy(
                submission = EditorSubmissionState.Uncertain(AttemptToken("tester", "key", "id", 1), accepted = true),
            )
        }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
    }

    @Test
    fun `new message keeps 160 dp body and both draft actions on short viewport`() {
        setComposeContent()
        compose.onNodeWithText("Votre réponse")
            .assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        assertDraftActionsFullyVisible()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h200dp-xxhdpi")
    fun `new message keeps both draft actions visible below the 220 dp budget threshold`() {
        setComposeContent()
        assertDraftActionsFullyVisible()
    }

    @Test
    fun `new message disables draft restore during upload and keeps discard enabled`() {
        setComposeContent(isUploading = true)

        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
    }

    @Test
    fun `new message enables draft restore when no upload is running`() {
        setComposeContent(isUploading = false)

        compose.onNodeWithText("Restaurer").assertIsEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
    }

    private fun setReplyContent(state: () -> PrivateMessageReplyUiState) {
        val picker = mockk<SmileyPickerController>(relaxed = true)
        every { picker.state } returns MutableStateFlow<SmileyPickerState>(SmileyPickerState.Hidden)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageReplyContent(
                    state = state(),
                    autoOpenRecipientManager = false,
                    onBack = {},
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onToolbarAction = {},
                    onTogglePreview = {},
                    onToggleSignature = {},
                    onToggleSmileyDisabled = {},
                    onToggleEmailNotification = {},
                    onErrorDismissed = {},
                    onSubmit = {},
                    onSubmitConfirmed = {},
                    onSubmitConfirmationDismissed = {},
                    onRetryFormLoad = {},
                    onDraftRestore = {},
                    onDraftDiscard = {},
                    onImagePickerEvent = {},
                    onUploadErrorDismissed = {},
                    onAddRecipient = {},
                    onRemoveRecipient = {},
                    smileyPicker = picker,
                    onSmileySelected = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun setComposeContent(isUploading: Boolean = false) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ComposeEditorBody(
                    state = PrivateMessageComposeUiState(
                        isLoadingForm = false,
                        formAvailable = true,
                        restorableDraft = "Brouillon à restaurer",
                        isUploading = isUploading,
                    ),
                    bodyFieldState = remember { TextFieldState() },
                    bodyInputTransformation = InputTransformation {},
                    onRecipientsChanged = {},
                    onSubjectChanged = {},
                    onToolbarAction = {},
                    onTogglePreview = {},
                    onErrorDismissed = {},
                    onDraftRestore = {},
                    onDraftDiscard = {},
                    onImagePickerEvent = {},
                    onUploadErrorDismissed = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun assertDraftActionsFullyVisible() {
        val restoreBounds = assertFullyVisibleAction("Restaurer")
        val discardBounds = assertFullyVisibleAction("Ignorer")
        assertTrue(
            "Draft actions must be separated by at least 8 dp",
            discardBounds.left - restoreBounds.right >= 8.dp,
        )
    }

    private fun assertFullyVisibleAction(label: String): DpRect {
        val action = compose.onNodeWithText(label)
            .assertHeightIsAtLeast(48.dp)
        val clippedBounds = action.getBoundsInRoot()
        val unclippedBounds = action.getUnclippedBoundsInRoot()
        assertEquals("Action $label is clipped", unclippedBounds, clippedBounds)

        val windowBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Action $label must stay entirely inside the window",
            unclippedBounds.left >= windowBounds.left &&
                unclippedBounds.top >= windowBounds.top &&
                unclippedBounds.right <= windowBounds.right &&
                unclippedBounds.bottom <= windowBounds.bottom,
        )
        return unclippedBounds
    }
}

/** The editable node sits under the 8 dp floating-label headroom reserved inside the 160 dp slot. */
private val FIELD_LABEL_HEADROOM = 8.dp
