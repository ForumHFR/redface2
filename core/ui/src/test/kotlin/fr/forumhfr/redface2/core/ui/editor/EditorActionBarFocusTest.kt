package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorActionBarFocusTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorActionBarFixture()

    @Test
    fun `keyboard color and image open on Return and restore their trigger on return`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            val firstAction = if (parent == EditorBarMode.Format) "Rouge" else "Uploader une image"
            compose.onNodeWithContentDescription(firstAction).assertIsFocused().performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
            }
            compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithContentDescription(label).assertIsFocused()
            assertEquals(parent, host.bar.value.mode)
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `saved keyboard focus survives recreation in sublevels and on their restored triggers`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").assertIsFocused()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            compose.onNodeWithContentDescription(label).assertIsFocused()
            assertEquals(parent, host.bar.value.mode)
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithContentDescription(label).assertIsFocused()
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `touch color and image navigation does not capture keyboard focus`() {
        compose.setContent { host.Content() }
        // Android cannot request Touch mode. Keep keyboard focus possible so an erroneous request can steal it.
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent); host.bodyFocus.requestFocus() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription(label).revealEditorControl().performTouchInput { click() }
            assertEquals(if (parent == EditorBarMode.Format) EditorBarMode.Color else EditorBarMode.Image,
                host.bar.value.mode)
            compose.onNodeWithTag("body").assertIsFocused()
            val back = compose.onNodeWithContentDescription("Retour").assertIsNotFocused()
            // Positive control: a bad focus request really can steal focus from the field in this oracle.
            back.requestFocus().assertIsFocused()
            compose.onNodeWithTag("body").assertIsNotFocused()
            compose.runOnIdle { host.bodyFocus.requestFocus() }
            back.assertIsNotFocused().performTouchInput { click() }
            assertEquals(parent, host.bar.value.mode)
            compose.onNodeWithContentDescription(label).assertIsNotFocused()
            compose.onNodeWithTag("body").assertIsFocused()
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `color insertion after keyboard opening keeps the host body focus`() {
        host.bar.value = EditorBarState(EditorBarMode.Format)
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        openWithKeyboard("Couleur")
        compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Rouge").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("body").assertIsFocused()
        assertEquals(EditorBarMode.Format, host.bar.value.mode)
        assertEquals(listOf("bbcode"), host.calls)
    }

    @Test
    fun `system Back from a color swatch or image action restores its keyboard trigger`() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            host.Content()
        }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").performKeyInput { pressKey(Key.Tab) }
            val firstAction = if (parent == EditorBarMode.Format) "Rouge" else "Uploader une image"
            compose.onNodeWithContentDescription(firstAction).assertIsFocused()
            compose.runOnIdle { back.onBackPressed() }
            assertEquals(parent, host.bar.value.mode)
            compose.onNodeWithContentDescription(label).assertIsFocused()
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `ED85 Back reduced by the host restores the keyboard trigger of a sublevel or Format`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            val firstAction = if (parent == EditorBarMode.Format) "Rouge" else "Uploader une image"
            compose.onNodeWithContentDescription(firstAction).assertIsFocused()
            hostBack()
            assertEquals(parent, host.bar.value.mode)
            compose.onNodeWithContentDescription(label).assertIsFocused()
        }
        openWithKeyboard("Mise en forme")
        assertEquals(EditorBarMode.Format, host.bar.value.mode)
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        hostBack()
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `a surface opened from a keyboard image action is not restored as a host Back`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        openWithKeyboard("Image")
        compose.onNodeWithContentDescription("Retour").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        // Same Image -> Normal transition as Back, with focus still in the row: the bar dispatched it.
        compose.onNodeWithContentDescription("Uploader une image").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.onNodeWithContentDescription("Image").assertIsNotFocused()
        assertEquals(listOf("upload"), host.calls)
    }

    @Test
    fun `system Back after leaving color keeps body focus when closing Format`() {
        lateinit var back: OnBackPressedDispatcher
        host.bar.value = EditorBarState(EditorBarMode.Format)
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            host.Content()
        }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        openWithKeyboard("Couleur")
        compose.onNodeWithContentDescription("Retour").assertIsFocused()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Couleur").assertIsFocused()
        compose.runOnIdle { host.bodyFocus.requestFocus() }
        compose.onNodeWithTag("body").assertIsFocused()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("body").assertIsFocused()
        compose.onNodeWithContentDescription("Mise en forme").assertIsNotFocused()
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `ED82 ED85 system Back skips an event masked keyboard sublevel without restoring its focus`() {
        lateinit var back: OnBackPressedDispatcher
        var nextLevelBacks = 0
        val event = host.finished()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { nextLevelBacks++ }
            host.Content()
        }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle {
                host.bar.value = EditorBarState(parent); host.eventActions.clear(); nextLevelBacks = 0
            }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").assertIsFocused()
            val retained = host.bar.value
            compose.runOnIdle { host.event.value = event }
            compose.onNodeWithContentDescription("Retour").assertDoesNotExist()
            compose.runOnIdle { back.onBackPressed() }
            compose.runOnIdle {
                assertEquals(retained, host.bar.value)
                assertSame(event, host.event.value)
                assertEquals(1, nextLevelBacks)
                assertTrue(host.eventActions.isEmpty())
            }
            compose.runOnIdle { host.bodyFocus.requestFocus() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNode(hasClickAction() and hasContentDescription("3 images insérées, Masquer")).performClick()
            compose.onNodeWithContentDescription("Retour").assertIsNotFocused()
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription(label).assertIsNotFocused()
            assertEquals(parent, host.bar.value.mode)
            assertEquals(1, nextLevelBacks)
            assertEquals(listOf(event.key to EditorEventAction.Dismiss), host.eventActions)
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `arming a focused keyboard sublevel cannot steal body focus after disarming`() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            host.Content()
        }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            openWithKeyboard(label)
            compose.onNodeWithContentDescription("Retour").assertIsFocused()
            compose.runOnIdle { host.bar.value = host.bar.value.copy(arm = host.frozenArm) }
            compose.onNodeWithContentDescription("Retour").assertDoesNotExist()
            compose.runOnIdle { host.bodyFocus.requestFocus(); host.bar.value = host.bar.value.copy(arm = null) }
            compose.onNodeWithContentDescription("Retour").assertIsNotFocused()
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription(label).assertIsNotFocused()
            assertEquals(parent, host.bar.value.mode)
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `masking a keyboard focused Format by an event or an arm is not a host Back on unmasking`() {
        val event = host.finished()
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf<(Boolean) -> Unit>(
            { masked -> host.event.value = event.takeIf { masked } },
            { masked -> host.bar.value = host.bar.value.copy(arm = host.frozenArm.takeIf { masked }) },
        ).forEach { mask ->
            compose.runOnIdle { host.bar.value = EditorBarState() }
            openWithKeyboard("Mise en forme")
            compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
            assertEquals(EditorBarMode.Format, host.bar.value.mode)
            // Shown Format -> Normal has the shape of a Back: without `masked`, unmasking would refocus Format.
            compose.runOnIdle { mask(true) }
            compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
            compose.runOnIdle { host.bodyFocus.requestFocus() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle { mask(false) }
            compose.onNodeWithContentDescription("Gras").assertExists()
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription("Mise en forme").assertIsNotFocused()
            assertEquals(EditorBarMode.Format, host.bar.value.mode)
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `refused keyboard opening cannot later steal body focus when the host opens a sublevel`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle {
                host.bar.value = EditorBarState(parent); host.acceptGestures.value = false
            }
            openWithKeyboard(label)
            assertEquals(parent, host.bar.value.mode)
            compose.onNodeWithContentDescription(label).assertIsFocused()
            compose.runOnIdle { host.bodyFocus.requestFocus() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle {
                host.bar.value = EditorBarState(if (parent == EditorBarMode.Format) EditorBarMode.Color
                    else EditorBarMode.Image)
            }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription("Retour").assertIsNotFocused()
        }
    }

    @Test
    fun `refused keyboard Back cannot later steal body focus when the host closes a sublevel`() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            host.Content()
        }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf("Couleur" to EditorBarMode.Format, "Image" to EditorBarMode.Normal).forEach { (label, parent) ->
            compose.runOnIdle {
                host.bar.value = EditorBarState(parent); host.acceptGestures.value = true
            }
            openWithKeyboard(label)
            compose.runOnIdle { host.acceptGestures.value = false; back.onBackPressed() }
            compose.onNodeWithContentDescription("Retour").assertIsFocused()
            compose.runOnIdle { host.bodyFocus.requestFocus() }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.runOnIdle { host.bar.value = EditorBarState(parent) }
            compose.onNodeWithTag("body").assertIsFocused()
            compose.onNodeWithContentDescription(label).assertIsNotFocused()
        }
    }

    @Test
    fun `Format keeps keyboard focus through toggles and saved state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        openWithKeyboard("Mise en forme")
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
    }

    /** As the post host does: ED-85 is resolved above the bar, whose state is reduced without its handler. */
    private fun hostBack() {
        compose.runOnIdle { host.bar.value = reduceEditorBar(host.bar.value, EditorBarGesture.Back) }
    }

    private fun openWithKeyboard(label: String) {
        compose.onNodeWithContentDescription(label).revealEditorControl().requestFocus()
            .assertIsFocused().performKeyInput { pressKey(Key.Enter) }
    }
}
