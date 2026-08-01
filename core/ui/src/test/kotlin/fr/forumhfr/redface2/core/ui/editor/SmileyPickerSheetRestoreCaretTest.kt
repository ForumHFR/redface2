package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun reopenedController(query: String): SmileyPickerController {
        val controller = SmileyPickerController(
            scope = CoroutineScope(StandardTestDispatcher()),
            searchWiki = { _, _ -> emptyList() },
        )
        controller.open()
        controller.onQueryChanged(query)
        controller.dismiss()
        controller.open()
        return controller
    }

    private fun composeSheet(controller: SmileyPickerController) {
        composeTestRule.setContent {
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
