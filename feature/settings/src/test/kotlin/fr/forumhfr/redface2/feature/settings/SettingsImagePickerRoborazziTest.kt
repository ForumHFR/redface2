package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1128 — record the actual catalogue rows in isolation, using the existing settings capture setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SettingsImagePickerRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun photoPickerSelected() {
        capture(SettingsState(), "settings_image_picker_photo")
    }

    @Test
    fun getContentSelected() {
        capture(
            SettingsState(imagePickerMode = ImagePickerMode.PHOTO_PICKER_GET_CONTENT),
            "settings_image_picker_photo_get_content",
        )
    }

    @Test
    fun documentPickerSelected() {
        capture(
            SettingsState(imagePickerMode = ImagePickerMode.DOCUMENT_PICKER),
            "settings_image_picker_documents",
        )
    }

    private fun capture(state: SettingsState, name: String) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val rows = buildSettingsCatalogue(
                    state = state,
                    onIntent = {},
                    startScreenState = StartScreenSettingsState(),
                    onStartScreenIntent = {},
                    onOpenProxy = {},
                    onOpenMaintenance = {},
                    onOpenDisplay = {},
                    onOpenImages = {},
                    onOpenAccountAbout = {},
                    onOpenBlacklist = {},
                ).first { it.id == "editing" }.items
                Surface {
                    Column(modifier = Modifier.fillMaxWidth().testTag("image_picker_group")) {
                        rows.first { it.searchable.id == "image_picker_photo" }.render()
                        rows.first { it.searchable.id == "image_picker_photo_get_content" }.render()
                        rows.first { it.searchable.id == "image_picker_documents" }.render()
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("image_picker_group")
            .captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }
}
