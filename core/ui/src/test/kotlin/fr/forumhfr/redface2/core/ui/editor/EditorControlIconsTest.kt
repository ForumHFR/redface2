package fr.forumhfr.redface2.core.ui.editor

import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as Action
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
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
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers.callInstanceMethod

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorControlIconsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `ED32 and ED33 action vectors render with their French labels and no selection semantics`() {
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
        compose.setContent { RedfaceTheme { Surface {
            EditorActionIcon(icons[index.value].first, stringResource(icons[index.value].second), {})
        } } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        icons.forEachIndexed { position, (_, label) ->
            compose.runOnIdle { index.value = position }
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            compose.onNode(hasClickAction()).assert(hasContentDescription(context.getString(label)))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .captureRoboImage("build/outputs/roborazzi/editor_action_$position.png")
        }
    }
    @Test fun `ED32 action name and tooltip agree while only the mode trigger has a state`() {
        val expanded = mutableStateOf(false)
        val focus = FocusRequester() // IconButton accepts keyboard focus only outside touch mode.
        lateinit var inputModeManager: InputModeManager
        var clicks = 0
        compose.setContent { inputModeManager = LocalInputModeManager.current
            RedfaceTheme { EditorActionIcon(R.drawable.ic_editor_format, stringResource(R.string.editor_format),
                { clicks++ }, Modifier.focusRequester(focus), expanded = expanded.value) }
        }
        val button = compose.onNode(hasClickAction())
        button.assert(hasContentDescription("Mise en forme"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Replié"))
        button.getUnclippedBoundsInRoot().run { assertTrue(toString(), width.value >= 48f && height.value >= 48f) }
        compose.runOnIdle { expanded.value = true }
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Développé"))
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.runOnIdle { focus.requestFocus() }
        button.performTouchInput { longClick() }
        compose.onNodeWithText("Mise en forme", useUnmergedTree = true).assertIsDisplayed()
        button.assertIsFocused()
        assertEquals(0, clicks)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
    @Test fun `ED90 and ED93 icons follow visual keyboard order and disabled insertions cannot run`() {
        val first = FocusRequester()
        val enabled = mutableStateOf(true)
        lateinit var inputModeManager: InputModeManager
        var clicks = 0
        compose.setContent { inputModeManager = LocalInputModeManager.current
            RedfaceTheme { Row {
                EditorActionIcon(R.drawable.ic_editor_bold, "Gras", { clicks++ },
                    Modifier.focusRequester(first), enabled = enabled.value)
                EditorActionIcon(R.drawable.ic_editor_italic, "Italique", {})
            } }
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
    @Test fun `ED40 status arrival uses a permanent polite region and one named count target`() {
        val visible = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val count = mutableStateOf(3)
        lateinit var view: android.view.View
        var clicks = 0
        compose.setContent { view = LocalView.current
            RedfaceTheme(darkTheme = dark.value) { Surface {
                EditorStatusIcon(R.drawable.ic_editor_quote, "${count.value} citations, ouvrir le panneau citations",
                    { clicks++ }, badgeCount = count.value, visible = visible.value)
            } }
        }
        val id = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNode().id
        compose.assertLiveNode(id, "", LiveRegionMode.Polite, view)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertAbsent(view) }
        compose.runOnIdle { visible.value = true }
        compose.assertLiveNode(id, "3 citations, ouvrir le panneau citations", LiveRegionMode.Polite, view)
        val button = compose.onNode(hasClickAction())
        button.assert(hasContentDescription("3 citations, ouvrir le panneau citations")).performClick()
        assertEquals(1, clicks)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        button.getUnclippedBoundsInRoot().run { assertTrue(toString(), width.value >= 48f && height.value >= 48f) }
        val glyph = compose.onNodeWithTag(EDITOR_STATUS_GLYPH_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithTag(EDITOR_STATUS_BADGE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Badge must not cover glyph", badge.top >= glyph.bottom)
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark.value = isDark }
            button.captureRoboImage("build/outputs/roborazzi/editor_status_$isDark.png")
        }
        compose.runOnIdle { count.value = 4 }
        compose.assertLiveNode(id, "4 citations, ouvrir le panneau citations", LiveRegionMode.Polite, view)
        compose.runOnIdle { visible.value = false }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.assertLiveNode(id, "", LiveRegionMode.Polite, view)
        compose.runOnIdle { assertAbsent(view) }
    }
    @Test fun `recipient InputChip has one removal button without checked state and obeys disabled state`() {
        var removals = 0
        val enabled = mutableStateOf(true)
        compose.setContent { RedfaceTheme { Surface {
            EditorRecipientChip("Alice", { removals++ }, enabled = enabled.value)
        } } }
        val chip = compose.onNode(hasClickAction())
        listOf(true, false).forEach { active ->
            compose.runOnIdle { enabled.value = active }
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            chip.assert(hasContentDescription("Retirer Alice"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            val removal = chip.fetchSemanticsNode().config[SemanticsActions.OnClick]
            assertEquals("Retirer Alice", removal.label)
            if (active) chip.performClick() else {
                chip.assertIsNotEnabled().performTouchInput { click() }
                compose.runOnIdle { assertEquals(false, removal.action?.invoke()) }
            }
            assertEquals(1, removals)
            chip.getUnclippedBoundsInRoot().run { assertTrue(toString(), width.value >= 48f && height.value >= 48f) }
            assertEquals(48f, chip.getUnclippedBoundsInRoot().height.value, 0.5f)
            val state = if (active) "enabled" else "disabled"
            chip.captureRoboImage("build/outputs/roborazzi/editor_recipient_$state.png")
        }
    }
    private fun assertAbsent(view: android.view.View, id: Int = android.view.View.NO_ID) {
        val info = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
        assertTrue("Absent node $id must not accept input", !info.isFocusable && !info.isScreenReaderFocusable &&
            info.actionList.all { it == Action.ACTION_ACCESSIBILITY_FOCUS ||
                it == Action.ACTION_CLEAR_ACCESSIBILITY_FOCUS })
        repeat(info.childCount) { index -> // Robolectric getChild() is a placeholder; read stored virtual IDs.
            val child = callInstanceMethod<Long>(info, "getChildId", ClassParameter.from(Int::class.java, index))
            assertAbsent(view, (child shr 32).toInt())
        }
    }
}
