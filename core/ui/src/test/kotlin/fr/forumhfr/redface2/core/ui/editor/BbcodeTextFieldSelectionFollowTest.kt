package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #447 point 2 / #1263 — the externally scrolled viewport must follow the edge of the selection
 * that is being MOVED, and must NOT read a programmatic selection as a dragged edge.
 *
 * The three editors share this contract through [BbcodeTextField]: the full-screen post and MP
 * editors use `fillViewport` (the field owns its scrollable column), `TopicFormScreen` uses the
 * default mode inside the caller's `verticalScroll`. Both are covered here.
 *
 * A handle drag is only possible out of an EXISTING selection, so every drag scenario below starts
 * from a non-collapsed selection (gate Sol: moving an edge out of a CARET is « select all » or a
 * long press, not a handle).
 *
 * What Robolectric can NOT exercise — and what stays out of this lot — is the CONTINUOUS
 * drag-to-scroll: the selection handles are rendered in their own `Popup` window and the legacy
 * `TextFieldSelectionManager` accumulates raw pointer deltas on a drag origin captured in the text
 * layout's coordinate space, so no amount of ancestor scrolling makes the drag itself advance.
 * See the KDoc of [BbcodeTextField] and #1406.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldSelectionFollowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val OUTER_SCROLL_TAG = "outer_scroll"
        val LONG_TEXT = (1..200).joinToString("\n") { "ligne $it" }

        /** Start of an existing selection wide enough to own two handles, near the text end. */
        val SELECTION_NEAR_END = LONG_TEXT.length - 300
    }

    @Test
    fun `dragging the start handle up scrolls back to the moving start edge`() {
        val value = setFillViewportContent()
        focusField()

        // An EXISTING selection near the end: the viewport is parked at the bottom.
        setSelection(value, TextRange(SELECTION_NEAR_END, LONG_TEXT.length))
        val scrollAtEnd = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue("precondition: the viewport is parked at the bottom", scrollAtEnd > 0f)

        // Start handle dragged up to the first line: `end` never moves, only `start` does. Not a
        // whole-text selection, so it stays a drag.
        setSelection(value, TextRange(5, LONG_TEXT.length))

        val scrollAfter = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val maxValue = maxScrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue(
            "the viewport follows the moving START edge back to the top " +
                "(before=$scrollAtEnd after=$scrollAfter max=$maxValue)",
            scrollAfter < scrollAtEnd && scrollAfter <= maxValue * 0.05f,
        )
    }

    @Test
    fun `default mode in an outer scrollable also follows the moving start edge`() {
        val value = setOuterScrollContent()
        focusField()

        setSelection(value, TextRange(SELECTION_NEAR_END, LONG_TEXT.length))
        val scrollAtEnd = scrollValue(OUTER_SCROLL_TAG)
        assertTrue("precondition: the outer column is parked at the bottom", scrollAtEnd > 0f)

        setSelection(value, TextRange(5, LONG_TEXT.length))

        val scrollAfter = scrollValue(OUTER_SCROLL_TAG)
        val maxValue = maxScrollValue(OUTER_SCROLL_TAG)
        assertTrue(
            "the outer column follows the moving START edge back to the top " +
                "(before=$scrollAtEnd after=$scrollAfter max=$maxValue)",
            scrollAfter < scrollAtEnd && scrollAfter <= maxValue * 0.05f,
        )
    }

    @Test
    fun `dragging the end handle forward reveals one line beyond it`() {
        val value = setFillViewportContent()
        focusField()

        // Existing selection in the middle of the text, revealed WITHOUT lookahead (it is new), so
        // its end sits on the last visible line of the viewport.
        val middle = LONG_TEXT.indexOf("ligne 100") + "ligne 10".length
        setSelection(value, TextRange(middle, middle + 1))
        val newSelectionScroll = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val maxValue = maxScrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue(
            "precondition: revealed mid-text, away from both ends " +
                "(scroll=$newSelectionScroll max=$maxValue)",
            newSelectionScroll > 0f && newSelectionScroll < maxValue * 0.9f,
        )

        // Same line, but now the END edge is being dragged forward: the viewport must show what
        // comes NEXT instead of parking the edge flush against its bottom border.
        setSelection(value, TextRange(middle, middle + 2))

        val draggedScroll = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val viewportHeight = composeTestRule
            .onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
            .size
            .height
        assertTrue(
            "the viewport scrolled ahead of the moving END edge " +
                "(new=$newSelectionScroll dragged=$draggedScroll)",
            draggedScroll > newSelectionScroll,
        )
        assertTrue(
            "the lookahead stays around one text line, it is not a jump " +
                "(delta=${draggedScroll - newSelectionScroll} viewport=$viewportHeight)",
            draggedScroll - newSelectionScroll <= viewportHeight * 0.25f,
        )
    }

    @Test
    fun `a selection change inside the visible area requests no scroll`() {
        val value = setFillViewportContent()
        focusField()
        assertEquals(
            "precondition: the caret starts at the top, nothing scrolled",
            0f,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )

        setSelection(value, TextRange(3))
        assertEquals(
            "moving the caret inside the visible area must not move the viewport",
            0f,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )

        setSelection(value, TextRange(3, 6))
        assertEquals(
            "extending a selection inside the visible area must not move the viewport",
            0f,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )
    }

    @Test
    fun `select all from the end caret does not jump to the top`() {
        // gate Sol : `(n,n) → (0,n)` moves only `start` but is NOT a start-handle drag — the end is
        // already visible, so nothing must move.
        val value = setFillViewportContent()
        focusField()

        setSelection(value, TextRange(LONG_TEXT.length))
        val scrollAtEnd = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue("precondition: the viewport is parked at the bottom", scrollAtEnd > 0f)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        assertEquals(
            "select all keeps the end in view instead of jumping to the top",
            scrollAtEnd,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )
    }

    @Test
    fun `select all from a selection already ending at the last character does not jump either`() {
        // The case the collapsed-previous guard alone does not catch: `(k,n) → (0,n)`.
        val value = setFillViewportContent()
        focusField()

        setSelection(value, TextRange(SELECTION_NEAR_END, LONG_TEXT.length))
        val scrollAtEnd = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue("precondition: the viewport is parked at the bottom", scrollAtEnd > 0f)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        assertEquals(
            "select all keeps the end in view instead of jumping to the top",
            scrollAtEnd,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )
    }

    @Test
    fun `select all from the start caret reveals the end`() {
        val value = setFillViewportContent()
        focusField()
        assertEquals("precondition: nothing scrolled", 0f, scrollValue(BBCODE_FIELD_VIEWPORT_TAG), 0f)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        val scrollAfter = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val maxValue = maxScrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue(
            "select all reveals the focus end (after=$scrollAfter max=$maxValue)",
            maxValue > 0f && scrollAfter >= maxValue * 0.95f,
        )
    }

    @Test
    fun `a burst of selections settles on the last one`() {
        // gate Sol : the frame wait is not an atomicity guarantee, so pin what it IS meant to give
        // — several selections landing before the frame settles leave the viewport on the LAST one,
        // and it stays there (no oscillation between the transient targets).
        val value = setFillViewportContent()
        focusField()

        val quarter = LONG_TEXT.indexOf("ligne 50")
        val half = LONG_TEXT.indexOf("ligne 100")
        composeTestRule.runOnUiThread {
            value.value = value.value.copy(selection = TextRange(0, quarter))
            value.value = value.value.copy(selection = TextRange(0, half))
            value.value = value.value.copy(selection = TextRange(0, LONG_TEXT.length))
        }
        composeTestRule.waitForIdle()

        val settled = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val maxValue = maxScrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue(
            "the viewport settles on the LAST selection of the burst " +
                "(settled=$settled max=$maxValue)",
            maxValue > 0f && settled >= maxValue * 0.95f,
        )

        composeTestRule.waitForIdle()
        assertEquals(
            "and it stays there — no late scroll to a transient target",
            settled,
            scrollValue(BBCODE_FIELD_VIEWPORT_TAG),
            0f,
        )
    }

    private fun setFillViewportContent(): MutableState<TextFieldValue> {
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        value = remember { mutableStateOf(longTextValue()) }
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            fillViewport = true,
                        )
                    }
                }
            }
        }
        return value
    }

    private fun setOuterScrollContent(): MutableState<TextFieldValue> {
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        value = remember { mutableStateOf(longTextValue()) }
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .testTag(OUTER_SCROLL_TAG),
                        ) {
                            BbcodeTextField(
                                value = value.value,
                                onValueChange = { value.value = it },
                                label = "Message",
                            )
                        }
                    }
                }
            }
        }
        return value
    }

    private fun longTextValue() = TextFieldValue(text = LONG_TEXT, selection = TextRange.Zero)

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private fun setSelection(value: MutableState<TextFieldValue>, selection: TextRange) {
        composeTestRule.runOnIdle { value.value = value.value.copy(selection = selection) }
        composeTestRule.waitForIdle()
    }

    private fun scrollValue(tag: String): Float = scrollRange(tag).value()

    private fun maxScrollValue(tag: String): Float = scrollRange(tag).maxValue()

    private fun scrollRange(tag: String) = composeTestRule
        .onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
}
