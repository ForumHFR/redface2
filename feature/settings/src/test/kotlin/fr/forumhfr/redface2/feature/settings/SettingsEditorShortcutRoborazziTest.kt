package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsEditorShortcutRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private var sample by mutableStateOf(0)
    private val samples = listOf(
        listOf(EditorShortcut.NONE, EditorShortcut.BOLD, EditorShortcut.PERSONAL_SMILEY),
        listOf(EditorShortcut.RED, EditorShortcut.BLUE, EditorShortcut.GREEN,
            EditorShortcut.ORANGE, EditorShortcut.GREY),
        listOf(EditorShortcut.UPLOAD_IMAGE, EditorShortcut.IMAGE_URL,
            EditorShortcut.IMAGE_SELECTION, EditorShortcut.SEARCH_SMILEYS),
    )

    @Test fun `settings icons drawn swatches restrictions and write failure in both themes and scales`() {
        compose.setContent {
            key(dark, scale, sample) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    RedfaceTheme(darkTheme = dark, amoledTheme = false, dynamicColor = false) {
                        val choices = samples[sample]
                        val state = SettingsState(editorShortcut = choices.first(), editorShortcutError = sample == 0)
                        val rows = editorShortcutRows(state, {}).filter { row ->
                            choices.any { row.searchable.id == "editor_shortcut_${it.id}" }
                        }
                        Surface(Modifier.width(360.dp)) {
                            Column(Modifier.fillMaxWidth().testTag("shortcut_settings_sample")) {
                                EditorShortcutGroup { rows.forEach { it.render() } }
                            }
                        }
                    }
                }
            }
        }
        listOf(false, true).forEach { theme ->
            listOf(1f, 1.3f).forEach { font ->
                samples.indices.forEach { index ->
                    compose.runOnIdle { dark = theme; scale = font; sample = index }
                    compose.onNodeWithTag("shortcut_settings_sample").captureRoboImage(
                        "src/test/screenshots/settings_editor_shortcut_${index}_${theme}_$font.png")
                }
            }
        }
    }
}
