package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #275/#410/#447 — layout contract of the bounded legacy [BbcodeTextField].
 *
 * Legacy `BasicTextField(TextFieldValue)` exposes no vertical scroll semantics. These tests prove
 * internal ownership without inventing one: the text layout overflows a bounded field, a touch
 * swipe changes which text offset is hit at the viewport centre, and caret moves/re-anchors leave
 * the target line within one viewport of that centre. Real IME resize and handle popups remain
 * device-only checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldViewportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val FIELD_TAG = "bounded_bbcode_field"
        const val HOST_TAG = "bounded_bbcode_host"
        val LONG_TEXT = (1..200).joinToString("\n") { "line $it" }
    }

    @Test
    fun `short content - the field fills its bounded host`() {
        setFieldContent(text = "short")

        assertFieldFillsHost()
    }

    @Test
    fun `long content - the field stays bounded instead of growing with text`() {
        setFieldContent(text = LONG_TEXT)

        assertFieldFillsHost()
    }

    @Test
    fun `long content - text layout overflows the bounded internal viewport`() {
        setFieldContent(text = LONG_TEXT)

        val viewportHeight = editableNodeHeight()
        assertTrue(
            "long text must overflow inside the bounded field",
            textLayout().size.height > viewportHeight,
        )
    }

    @Test
    fun `short content - text layout fits and a swipe cannot change its hit target`() {
        setFieldContent(text = "short", selection = TextRange.Zero)
        focusField()
        val before = selectionAfterCenterTap()

        swipeFieldUp()
        val after = selectionAfterCenterTap()

        assertTrue(textLayout().size.height <= editableNodeHeight())
        assertEquals(before, after)
    }

    @Test
    fun `touch scrolling long text advances the offset under the viewport centre`() {
        setFieldContent(text = LONG_TEXT, selection = TextRange.Zero)
        focusField()
        val layout = textLayout()
        val before = selectionAfterCenterTap()

        swipeFieldUp()
        val after = selectionAfterCenterTap()

        assertTrue(
            "the internal field must consume the swipe (before=$before after=$after)",
            layout.getLineForOffset(after) > layout.getLineForOffset(before),
        )
    }

    @Test
    fun `moving a collapsed caret to the end reveals it in the internal viewport`() {
        val fixture = setFieldContent(text = LONG_TEXT, selection = TextRange.Zero)
        focusField()

        composeTestRule.runOnIdle {
            fixture.value.value = fixture.value.value.copy(selection = TextRange(LONG_TEXT.length))
        }
        composeTestRule.waitForIdle()

        assertEquals(TextRange(LONG_TEXT.length), fixture.value.value.selection)
        assertCaretLineIsInsideViewport(LONG_TEXT.length)
    }

    @Test
    fun `shrinking a focused field reanchors a middle caret in the internal viewport`() {
        val middle = LONG_TEXT.length / 2
        val fixture = setFieldContent(text = LONG_TEXT, selection = TextRange(middle))
        focusField()

        composeTestRule.runOnIdle { fixture.height.value = 180.dp }
        composeTestRule.waitForIdle()

        assertEquals(TextRange(middle), fixture.value.value.selection)
        assertCaretLineIsInsideViewport(middle)
    }

    @Test
    fun `preview-like grow and shrink reanchors the same caret again`() {
        val caret = LONG_TEXT.length * 3 / 4
        val fixture = setFieldContent(
            text = LONG_TEXT,
            selection = TextRange(caret),
            height = 240.dp,
        )
        focusField()

        composeTestRule.runOnIdle { fixture.height.value = 400.dp }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { fixture.height.value = 160.dp }
        composeTestRule.waitForIdle()

        assertEquals(TextRange(caret), fixture.value.value.selection)
        assertCaretLineIsInsideViewport(caret)
    }

    @Test
    fun `floating label stays inside the bounded field after internal touch scroll`() {
        setFieldContent(text = LONG_TEXT, selection = TextRange.Zero, label = "BBCode content")
        focusField()
        swipeFieldUp()

        val field = composeTestRule.onNodeWithTag(FIELD_TAG).fetchSemanticsNode()
        val label = composeTestRule
            .onNodeWithText("BBCode content", useUnmergedTree = true)
            .fetchSemanticsNode()
        val fieldBottom = field.positionInRoot.y + field.size.height
        val labelBottom = label.positionInRoot.y + label.size.height
        assertTrue(
            "the floating label must remain within the bounded decoration",
            label.positionInRoot.y >= field.positionInRoot.y && labelBottom <= fieldBottom,
        )
    }

    private fun assertFieldFillsHost() {
        val host = composeTestRule.onNodeWithTag(HOST_TAG).fetchSemanticsNode()
        val field = composeTestRule.onNodeWithTag(FIELD_TAG).fetchSemanticsNode()
        assertEquals(host.size.width, field.size.width)
        assertEquals(host.size.height, field.size.height)
    }

    private fun setFieldContent(
        text: String,
        selection: TextRange = TextRange(text.length),
        height: Dp = 400.dp,
        label: String = "Message",
    ): FieldFixture {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var fieldHeight: MutableState<Dp>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    value = remember { mutableStateOf(TextFieldValue(text, selection)) }
                    fieldHeight = remember { mutableStateOf(height) }
                    Box(
                        modifier = Modifier
                            .size(320.dp, fieldHeight.value)
                            .testTag(HOST_TAG),
                    ) {
                        Field(value = value, label = label)
                    }
                }
            }
        }
        return FieldFixture(value = value, height = fieldHeight)
    }

    @Composable
    private fun Field(value: MutableState<TextFieldValue>, label: String) {
        BbcodeTextField(
            value = value.value,
            onValueChange = { value.value = it },
            label = label,
            modifier = Modifier
                .fillMaxSize()
                .testTag(FIELD_TAG),
        )
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private fun swipeFieldUp() {
        composeTestRule.onNodeWithTag(FIELD_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
    }

    private fun selectionAfterCenterTap(): Int {
        composeTestRule.onNodeWithTag(FIELD_TAG).performTouchInput { click(center) }
        composeTestRule.waitForIdle()
        return composeTestRule.onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]
            .end
    }

    private fun assertCaretLineIsInsideViewport(caret: Int) {
        val layout = textLayout()
        val centreOffset = selectionAfterCenterTap()
        val caretLine = layout.getLineForOffset(caret)
        val centreLine = layout.getLineForOffset(centreOffset)
        val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        val visibleLineCount = ceil(editableNodeHeight() / firstLineHeight).toInt() + 2
        assertTrue(
            "caret line must be in the internal viewport " +
                "(caretLine=$caretLine centreLine=$centreLine visibleLines=$visibleLineCount)",
            abs(caretLine - centreLine) <= visibleLineCount,
        )
    }

    private fun textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val readLayout = requireNotNull(
            composeTestRule.onNode(hasSetTextAction())
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue("the editable text layout must be readable", readLayout(layouts))
        return layouts.single()
    }

    private fun editableNodeHeight(): Int = composeTestRule
        .onNode(hasSetTextAction())
        .fetchSemanticsNode()
        .size
        .height

    private data class FieldFixture(
        val value: MutableState<TextFieldValue>,
        val height: MutableState<Dp>,
    )
}
