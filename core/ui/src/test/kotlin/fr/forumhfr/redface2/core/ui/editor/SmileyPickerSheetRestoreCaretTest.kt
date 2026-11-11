package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #901 — the #824 restore-on-reopen must land with the caret at the END of the restored
 * word, so the user can either complete it or erase it in one gesture (tinc/nicko consensus
 * on the DEV thread). The regression came from the String overload of [OutlinedTextField],
 * whose internal TextFieldValue defaults its selection to TextRange(0).
 *
 * The reopen is driven through a real [SmileyPickerController] (open → type → dismiss →
 * open), exactly the sequence of an insertion or an accidental swipe-down ; the sheet is
 * then composed from the restored state, like every host does. The search job armed by
 * typing stays parked on a [StandardTestDispatcher] (never advanced) and is cancelled by
 * dismiss(), so no fake network resolution can interfere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerSheetRestoreCaretTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var inputStarts = 0

    private fun reopenedController(
        query: String,
        intent: SmileyPickerOpenIntent = SmileyPickerOpenIntent.SEARCH,
    ): SmileyPickerController {
        val controller = SmileyPickerController(
            scope = CoroutineScope(StandardTestDispatcher()),
            searchWiki = { _, _ -> emptyList() },
        )
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        controller.onQueryChanged(query)
        controller.dismiss()
        controller.open(intent)
        return controller
    }

    @Test
    fun `consultation of an interrupted restored query never asks for three characters already entered`() {
        val controller = reopenedController("jap", SmileyPickerOpenIntent.CONSULTATION)
        assertEquals(WikiSearchState.Idle, (controller.state.value as SmileyPickerState.Open).wiki)
        composeSheet(controller)
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_search_hint)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_search_resume)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("smiley_selected_tab").assertIsFocused()
    }

    @Test
    fun `a restored short query still explains the three character threshold`() {
        composeSheet(reopenedController("ja", SmileyPickerOpenIntent.CONSULTATION))
        val context = RuntimeEnvironment.getApplication()
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_search_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_search_resume)).assertDoesNotExist()
    }

    @Test
    fun `consultation focuses the selected Wiki tab without starting input or acting on Enter Space`() {
        val controller = reopenedController("jap", SmileyPickerOpenIntent.CONSULTATION)
        composeSheet(controller)
        assertConsultationFocus(controller, "Wiki")
        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `consultation focuses the selected Standard tab without starting input or acting on Enter Space`() {
        val controller = reopenedController("", SmileyPickerOpenIntent.CONSULTATION)
        composeSheet(controller)
        assertConsultationFocus(controller, "Standard")
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `consultation focuses the selected Favorites tab without starting input or acting on Enter Space`() {
        val controller = SmileyPickerController(
            scope = CoroutineScope(StandardTestDispatcher()),
            searchWiki = { _, _ -> error("Consultation must not search") },
            favoriteSources = SmileyPickerFavoriteSources(favoriteSmileys = { listOf(BUILTIN_HFR_SMILEYS.first()) }),
        )
        controller.open(SmileyPickerOpenIntent.CONSULTATION)
        composeSheet(controller)
        assertConsultationFocus(controller, "Favoris")
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun assertConsultationFocus(controller: SmileyPickerController, label: String) {
        val tab = composeTestRule.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        val config = tab.fetchSemanticsNode().config
        assertEquals(Role.Tab, config[SemanticsProperties.Role])
        assertEquals(true, config[SemanticsProperties.Selected])
        assertEquals(label, config[SemanticsProperties.Text].single().text)
        assertNull(config.getOrNull(SemanticsActions.Dismiss))
        val before = controller.state.value
        tab.performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        tab.assertIsFocused().performClick().assertIsFocused()
        assertEquals(before, controller.state.value)
        composeTestRule.onNodeWithText("Smileys").assertDoesNotExist()
        assertEquals(0, inputStarts)
    }

    @Test
    fun `changing tabs after consultation lets Wiki take search focus`() {
        composeSheet(reopenedController("jap", SmileyPickerOpenIntent.CONSULTATION))
        composeTestRule.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        composeTestRule.onNodeWithText("Standard").performClick()
        composeTestRule.onNodeWithText("Wiki").performClick()
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
        composeTestRule.onNodeWithTag("smiley_selected_tab").assertIsNotFocused()
    }

    @Test
    fun `typing three characters leaves no contradictory message or spinner during debounce`() {
        val controller = reopenedController("", SmileyPickerOpenIntent.SEARCH)
        composeSheet(controller)
        listOf("j", "a", "p").forEach { composeTestRule.onNode(hasSetTextAction()).performTextInput(it) }
        assertNeutralDebounce(controller, "jap")
    }

    @Test
    fun `editing an interrupted restored query removes the resume hint during debounce`() {
        val controller = reopenedController("jap", SmileyPickerOpenIntent.CONSULTATION)
        composeSheet(controller)
        composeTestRule.onNode(hasSetTextAction()).performClick().performTextInput("o")
        assertNeutralDebounce(controller, "japo")
    }

    private fun assertNeutralDebounce(controller: SmileyPickerController, query: String) {
        val open = controller.state.value as SmileyPickerState.Open
        assertEquals(query, open.query)
        assertEquals(WikiSearchState.Idle, open.wiki)
        assertNull(open.restoredQuery)
        val context = RuntimeEnvironment.getApplication()
        listOf(R.string.editor_smiley_search_hint, R.string.editor_smiley_search_resume,
            R.string.editor_smiley_search_loading, R.string.editor_smiley_search_empty,
            R.string.editor_smiley_search_error).forEach {
            composeTestRule.onNodeWithText(context.getString(it)).assertDoesNotExist()
        }
        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun `explicit consultation keeps a restored Wiki query without taking search focus`() {
        composeSheet(reopenedController("jap", SmileyPickerOpenIntent.CONSULTATION))
        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
        val node = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("jap", node.config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(3), node.config[SemanticsProperties.TextSelectionRange])
        // Consultation changes only initial focus: the restored search remains directly editable.
        composeTestRule.onNode(hasSetTextAction()).performClick().assertIsFocused().performTextInput("o")
        assertEquals("japo", composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `explicit search takes focus even with no restored query`() {
        composeSheet(reopenedController("", SmileyPickerOpenIntent.SEARCH))
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun composeSheet(controller: SmileyPickerController) {
        composeTestRule.setContent {
            InterceptPlatformTextInput(PlatformTextInputInterceptor { request, next ->
                inputStarts++
                next.startInputMethod(request)
            }) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    val state by controller.state.collectAsState()
                    (state as? SmileyPickerState.Open)?.let { open ->
                        SmileyPickerSheet(
                            state = open,
                            onDismiss = controller::dismiss,
                            onQueryChange = controller::onQueryChanged,
                            onSmileyClicked = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `reopen restores the query with the caret at the end`() {
        composeSheet(reopenedController("jap"))

        // The restored non-empty query auto-selects the Wiki tab (#824), whose search field
        // is the only node with a set-text action in the sheet.
        val node = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("jap", node.config[SemanticsProperties.EditableText].text)
        assertEquals(
            "the caret must sit at the END of the restored word",
            TextRange(3),
            node.config[SemanticsProperties.TextSelectionRange],
        )
    }

    @Test
    fun `typing after a reopen appends to the restored word`() {
        val controller = reopenedController("jap")
        composeSheet(controller)

        // Functional proof of the caret position : the committed character lands AFTER the
        // restored word (a caret at 0 would have produced "ojap"), and the controller sees
        // the appended query.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("o")

        val node = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("japo", node.config[SemanticsProperties.EditableText].text)
        assertEquals(
            "japo",
            (controller.state.value as SmileyPickerState.Open).query,
        )
    }
}
