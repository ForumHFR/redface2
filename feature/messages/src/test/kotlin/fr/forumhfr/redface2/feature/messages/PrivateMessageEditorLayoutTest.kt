package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
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
                    onContentChanged = {},
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

        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT)
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
                    onContentChanged = {},
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
