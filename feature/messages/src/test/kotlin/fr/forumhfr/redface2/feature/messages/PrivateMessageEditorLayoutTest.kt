package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
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
        compose.onNodeWithText("Restaurer").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
    }
}
