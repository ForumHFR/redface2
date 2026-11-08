package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorControlIconsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `ED32 and ED33 action vectors render with their French labels and no selection semantics`() {
        val icons = listOf(
            R.drawable.ic_editor_format to R.string.editor_format,
            R.drawable.ic_editor_smileys to R.string.editor_smileys,
            R.drawable.ic_editor_options to R.string.editor_options,
            R.drawable.ic_editor_bold to R.string.bbcode_action_bold,
            R.drawable.ic_editor_italic to R.string.bbcode_action_italic,
            R.drawable.ic_editor_underline to R.string.bbcode_action_underline,
            R.drawable.ic_editor_strike to R.string.bbcode_action_strike,
            R.drawable.ic_editor_quote to R.string.bbcode_action_quote,
            R.drawable.ic_editor_code to R.string.bbcode_action_cpp,
            R.drawable.ic_editor_fixed to R.string.bbcode_action_fixed,
            R.drawable.ic_editor_spoiler to R.string.bbcode_action_spoiler,
            R.drawable.ic_editor_link to R.string.bbcode_action_url,
            R.drawable.ic_editor_color to R.string.bbcode_action_color,
            R.drawable.ic_editor_image to R.string.bbcode_action_image,
            R.drawable.ic_editor_header to R.string.editor_header_topic,
            R.drawable.ic_editor_preview to R.string.editor_preview_show,
            R.drawable.ic_arrow_back to R.string.editor_return,
        )
        val index = mutableStateOf(0)
        compose.setContent {
            RedfaceTheme {
                Surface {
                    EditorActionIcon(icons[index.value].first, stringResource(icons[index.value].second), {})
                }
            }
        }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        icons.forEachIndexed { position, (_, label) ->
            compose.runOnIdle { index.value = position }
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNode(hasClickAction()).assert(hasContentDescription(context.getString(label)))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .captureRoboImage("build/outputs/roborazzi/editor_action_$position.png")
        }
    }

    @Test
    fun `ED32 action name and tooltip agree while only the mode trigger has a state`() {
        val expanded = mutableStateOf(false)
        val focus = FocusRequester()
        lateinit var inputModeManager: InputModeManager
        var clicks = 0
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            RedfaceTheme {
                EditorActionIcon(R.drawable.ic_editor_format, stringResource(R.string.editor_format),
                    { clicks++ }, Modifier.focusRequester(focus), expanded = expanded.value)
            }
        }
        val button = compose.onNode(hasClickAction())
        button.assert(hasContentDescription("Mise en forme"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Replié"))
        assertTarget(button.getUnclippedBoundsInRoot().width.value, button.getUnclippedBoundsInRoot().height.value)
        compose.runOnIdle { expanded.value = true }
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Développé"))
        // IconButton accepts keyboard focus only outside touch mode.
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.runOnIdle { focus.requestFocus() }
        button.performTouchInput { longClick() }
        compose.onNodeWithText("Mise en forme", useUnmergedTree = true).assertIsDisplayed()
        button.assertIsFocused()
        assertEquals(0, clicks)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `ED90 and ED93 icons follow visual keyboard order and disabled insertions cannot run`() {
        val first = FocusRequester()
        val enabled = mutableStateOf(true)
        lateinit var inputModeManager: InputModeManager
        var clicks = 0
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            RedfaceTheme {
                Row {
                    EditorActionIcon(R.drawable.ic_editor_bold, "Gras", { clicks++ },
                        Modifier.focusRequester(first), enabled = enabled.value)
                    EditorActionIcon(R.drawable.ic_editor_italic, "Italique", {})
                }
            }
        }
        val bold = compose.onNode(hasContentDescription("Gras"))
        val italic = compose.onNode(hasContentDescription("Italique"))
        bold.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.runOnIdle { first.requestFocus() }
        bold.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, clicks)
        bold.performKeyInput { pressKey(Key.Tab) }
        italic.assertIsFocused().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        bold.assertIsFocused()
        compose.runOnIdle { enabled.value = false }
        bold.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, clicks)
    }

    @Test
    fun `ED40 status arrival uses a permanent polite region and one named count target`() {
        val visible = mutableStateOf(false)
        val dark = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            RedfaceTheme(darkTheme = dark.value) {
                Surface {
                    EditorStatusIcon(R.drawable.ic_editor_quote, "3 citations, ouvrir le panneau citations",
                        { clicks++ }, badgeCount = 3, visible = visible.value)
                }
            }
        }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        val id = compose.onNode(live).fetchSemanticsNode().id
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { visible.value = true }
        assertEquals(id, compose.onNode(live).fetchSemanticsNode().id)
        compose.onNode(live).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite,
        ))
        val button = compose.onNode(hasClickAction())
        button.assert(hasContentDescription("3 citations, ouvrir le panneau citations")).performClick()
        assertEquals(1, clicks)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        assertTarget(button.getUnclippedBoundsInRoot().width.value, button.getUnclippedBoundsInRoot().height.value)
        val glyph = compose.onNodeWithTag(EDITOR_STATUS_GLYPH_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithTag(EDITOR_STATUS_BADGE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Badge must not cover glyph", badge.top >= glyph.bottom)
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark.value = isDark }
            button.captureRoboImage("build/outputs/roborazzi/editor_status_$isDark.png")
        }
        compose.runOnIdle { visible.value = false }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        assertEquals(id, compose.onNode(live).fetchSemanticsNode().id)
    }

    @Test
    fun `recipient InputChip has one removal target and obeys disabled state`() {
        var removals = 0
        val enabled = mutableStateOf(true)
        compose.setContent {
            RedfaceTheme { Surface { EditorRecipientChip("Alice", { removals++ }, enabled = enabled.value) } }
        }
        val chip = compose.onNode(hasClickAction())
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        chip.assert(hasContentDescription("Retirer le destinataire Alice")).performClick()
        assertEquals(1, removals)
        assertTarget(chip.getUnclippedBoundsInRoot().width.value, chip.getUnclippedBoundsInRoot().height.value)
        chip.captureRoboImage("build/outputs/roborazzi/editor_recipient_enabled.png")
        compose.runOnIdle { enabled.value = false }
        chip.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, removals)
        chip.captureRoboImage("build/outputs/roborazzi/editor_recipient_disabled.png")
    }

    private fun assertTarget(width: Float, height: Float) {
        assertTrue("Target ${width}x$height dp", width >= 48f && height >= 48f)
    }
}
