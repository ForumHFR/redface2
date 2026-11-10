package fr.forumhfr.redface2.feature.messages

import android.content.Context
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reply uses the shared chrome budget; new-message checks retain the legacy body until C4. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageEditorLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Test fun replyPreviewDisablesCoilDiskReadsAndWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://images.example.org/private.jpg"
        val policy = AtomicReference<CachePolicy?>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                policy.set(chain.request.diskCachePolicy)
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(url, ColorImage(0xFF2E7D32.toInt(), width = 80, height = 60)).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(recorder)
            add(engine)
        }.build())
        try {
            compose.setContent {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    PrivateMessageReplyTestContent(PrivateMessageReplyUiState(
                        isLoadingForm = false, formAvailable = true, isPreviewVisible = true,
                        preview = PostContent(listOf(PostBlock.Image(url, null)))))
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) { policy.get() != null }
            assertEquals(CachePolicy.DISABLED, policy.get())
        } finally { SingletonImageLoader.reset() }
    }

    @Test fun replyCompactGeometryStaysFixedWhileTyping() = checkReplyGeometry(48.dp)

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun replyNormalGeometryStaysFixedWhileTyping() = checkReplyGeometry(56.dp)

    private fun checkReplyGeometry(barHeight: androidx.compose.ui.unit.Dp) {
        setReplyContent { readyReply() }
        val field = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("editor-scaffold-action-bar").getUnclippedBoundsInRoot()
        assertEquals(barHeight, bar.height)
        assertTrue(field.height >= EDITOR_DRAFT_MIN_HEIGHT)
        assertTrue(field.bottom <= bar.top)
        repeat(50) { compose.onNode(hasSetTextAction()).performTextInput("a") }
        assertEquals(field, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        assertEquals(bar, compose.onNodeWithTag("editor-scaffold-action-bar").getUnclippedBoundsInRoot())
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h200dp-xxhdpi")
    fun replyRestorePanelKeepsBothDraftActionsReachableOnShortWindow() {
        setReplyContent { readyReply().copy(restorableDraft = "Brouillon") }
        openRestore()
        compose.onNodeWithText("Restaurer").performScrollTo()
        assertDraftActionsFullyVisible()
    }

    @Test fun replyRestoreDisabledWhileSeedFormLoadsAndEnabledAfterFailure() {
        val state = mutableStateOf(PrivateMessageReplyUiState(restorableDraft = "Brouillon à restaurer"))
        setReplyContent { state.value }
        openRestore()
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        assertDraftActionsFullyVisible()
        compose.runOnIdle { state.value = state.value.copy(isLoadingForm = false, formError = true) }
        compose.onNodeWithText("Restaurer").assertIsEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        compose.onNodeWithText("Réessayer").assertIsEnabled()
        assertDraftActionsFullyVisible()
        compose.runOnIdle { state.value = state.value.copy(formAvailable = true, formError = false) }
        // The same host memory keeps the restore panel across the full-screen form failure.
        compose.onNodeWithText("Restaurer").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isLoadingForm = true) }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
    }

    @Test fun replyRestoreDisabledDuringUpload() {
        val state = mutableStateOf(readyReply().copy(restorableDraft = "Brouillon", isUploading = true))
        setReplyContent { state.value }
        openRestore()
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isUploading = false) }
        compose.onNodeWithText("Restaurer").assertIsEnabled()
    }

    @Test fun replyRestoreDisabledForAcceptedUncertainSubmission() {
        val state = mutableStateOf(readyReply().copy(restorableDraft = "Brouillon"))
        setReplyContent { state.value }
        openRestore()
        compose.runOnIdle { state.value = state.value.copy(submission = EditorSubmissionState.Uncertain(
            AttemptToken("tester", "key", "id", 1), accepted = true)) }
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
    }

    @Test fun replyRestoreEnabledForUnacceptedUncertainSubmission() {
        setReplyContent { readyReply().copy(restorableDraft = "Brouillon", submission =
            EditorSubmissionState.Uncertain(AttemptToken("tester", "key", "id", 1), accepted = false)) }
        openRestore()
        compose.onNodeWithText("Restaurer").assertIsEnabled()
    }

    private fun readyReply() = PrivateMessageReplyUiState(isLoadingForm = false, formAvailable = true)
    private fun openRestore() =
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()

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

    @Test
    fun `new message disables draft restore for accepted uncertain submission`() {
        setComposeContent(
            submission = EditorSubmissionState.Uncertain(
                AttemptToken("tester", "key", "id", 1),
                accepted = true,
            ),
        )

        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
    }

    @Test
    fun `new message enables draft restore for unaccepted uncertain submission`() {
        setComposeContent(
            submission = EditorSubmissionState.Uncertain(
                AttemptToken("tester", "key", "id", 1),
                accepted = false,
            ),
        )

        compose.onNodeWithText("Restaurer").assertIsEnabled()
    }

    private fun setReplyContent(state: () -> PrivateMessageReplyUiState) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageReplyTestContent(state())
            }
        }
    }

    private fun setComposeContent(
        isUploading: Boolean = false,
        submission: EditorSubmissionState = EditorSubmissionState.Ready,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                ComposeEditorBody(
                    state = PrivateMessageComposeUiState(
                        isLoadingForm = false,
                        formAvailable = true,
                        restorableDraft = "Brouillon à restaurer",
                        isUploading = isUploading,
                        submission = submission,
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
