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
 * that is being MOVED, not always `selection.end`.
 *
 * The three editors share this contract through [BbcodeTextField]: the full-screen post and MP
 * editors use `fillViewport` (the field owns its scrollable column), `TopicFormScreen` uses the
 * default mode inside the caller's `verticalScroll`. Both are covered here.
 *
 * What Robolectric can NOT exercise — and what stays out of this lot — is the CONTINUOUS
 * drag-to-scroll: the selection handles are rendered in their own `Popup` window and the legacy
 * `TextFieldSelectionManager` accumulates raw pointer deltas on a drag origin captured in the text
 * layout's coordinate space, so no amount of ancestor scrolling makes the drag itself advance.
 * See the KDoc of [BbcodeTextField].
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
    }

    @Test
    fun `extending the selection backwards scrolls back up to the moving start edge`() {
        val value = setFillViewportContent()
        focusField()

        // Caret at the very end: the viewport is parked at the bottom (#447 point 1).
        setSelection(value, TextRange(LONG_TEXT.length))
        val scrollAtEnd = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue("precondition: the viewport is parked at the bottom", scrollAtEnd > 0f)

        // Start handle dragged all the way up: `end` never moves, only `start` does.
        setSelection(value, TextRange(0, LONG_TEXT.length))

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

        setSelection(value, TextRange(LONG_TEXT.length))
        val scrollAtEnd = scrollValue(OUTER_SCROLL_TAG)
        assertTrue("precondition: the outer column is parked at the bottom", scrollAtEnd > 0f)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        val scrollAfter = scrollValue(OUTER_SCROLL_TAG)
        val maxValue = maxScrollValue(OUTER_SCROLL_TAG)
        assertTrue(
            "the outer column follows the moving START edge back to the top " +
                "(before=$scrollAtEnd after=$scrollAfter max=$maxValue)",
            scrollAfter < scrollAtEnd && scrollAfter <= maxValue * 0.05f,
        )
    }

    @Test
    fun `extending the selection forward reveals one line beyond the moving end edge`() {
        val value = setFillViewportContent()
        focusField()

        // Collapsed caret in the middle of the text: revealed WITHOUT lookahead, so it sits on
        // the last visible line of the viewport.
        val middle = LONG_TEXT.indexOf("ligne 100") + "ligne 10".length
        setSelection(value, TextRange(middle))
        val caretScroll = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val maxValue = maxScrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        assertTrue(
            "precondition: the caret is revealed mid-text, away from both ends " +
                "(scroll=$caretScroll max=$maxValue)",
            caretScroll > 0f && caretScroll < maxValue * 0.9f,
        )

        // Same line, but now the END edge is being extended forward: the viewport must show what
        // comes NEXT instead of parking the edge flush against its bottom border.
        setSelection(value, TextRange(middle, middle + 1))

        val extendedScroll = scrollValue(BBCODE_FIELD_VIEWPORT_TAG)
        val viewportHeight = composeTestRule
            .onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
            .size
            .height
        assertTrue(
            "the viewport scrolled ahead of the moving END edge " +
                "(caret=$caretScroll extended=$extendedScroll)",
            extendedScroll > caretScroll,
        )
        assertTrue(
            "the lookahead stays around one text line, it is not a jump " +
                "(delta=${extendedScroll - caretScroll} viewport=$viewportHeight)",
            extendedScroll - caretScroll <= viewportHeight * 0.25f,
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

        // Caret moved, then a selection extended forward — both within the first visible lines.
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
