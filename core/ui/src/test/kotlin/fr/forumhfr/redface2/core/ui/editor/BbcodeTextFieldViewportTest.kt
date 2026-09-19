package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
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
 * #275/#410/#447 — layout contract of the bounded legacy [BbcodeTextField].
 *
 * Long text no longer grows inside an external `verticalScroll`: the field keeps the height given
 * by its host and `BasicTextField` owns the vertical range. That internal ownership is what lets
 * the legacy selection manager compensate scroll while a handle is dragged. Robolectric can pin
 * that structure and the size-change nudge's effect on the internal range; the real IME resize and
 * platform selection-handle popup remain device-only checks.
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
        const val OUTER_SCROLL_TAG = "outer_scroll"
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
    fun `long content - the field owns a vertical scroll range`() {
        setFieldContent(text = LONG_TEXT)

        assertTrue("the bounded text field owns the scroll", fieldScrollRange().maxValue() > 0f)
    }

    @Test
    fun `short content - the internal scroll range is empty`() {
        setFieldContent(text = "short")

        assertEquals(0f, fieldScrollRange().maxValue(), 0f)
    }

    @Test
    fun `scrolling long text moves the internal range and not an outer container`() {
        setFieldContent(text = LONG_TEXT, insideOuterScroll = true)

        val outerBefore = outerScrollValue()
        composeTestRule.onNodeWithTag(FIELD_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, 100_000f)
            }
        composeTestRule.waitForIdle()

        assertTrue("the internal field consumed the vertical scroll", fieldScrollValue() > 0f)
        assertEquals("the ancestor must stay still", outerBefore, outerScrollValue(), 0f)
    }

    @Test
    fun `moving a collapsed caret to the end reveals it in the internal viewport`() {
        val fixture = setFieldContent(text = LONG_TEXT, selection = TextRange.Zero)
        focusField()
        val before = fieldScrollValue()

        composeTestRule.runOnIdle {
            fixture.value.value = fixture.value.value.copy(selection = TextRange(LONG_TEXT.length))
        }
        composeTestRule.waitForIdle()

        val range = fieldScrollRange()
        assertTrue(
            "the internal viewport follows the final caret " +
                "(before=$before after=${range.value()} max=${range.maxValue()})",
            range.value() > before && range.value() >= range.maxValue() * 0.95f,
        )
    }

    @Test
    fun `shrinking a focused field reanchors a middle caret in the internal viewport`() {
        val middle = LONG_TEXT.length / 2
        val fixture = setFieldContent(text = LONG_TEXT, selection = TextRange(middle))
        focusField()
        val before = fieldScrollValue()

        composeTestRule.runOnIdle { fixture.height.value = 180.dp }
        composeTestRule.waitForIdle()

        assertTrue(
            "the size nudge must move the internal viewport after shrink " +
                "(before=$before after=${fieldScrollValue()})",
            fieldScrollValue() > before,
        )
        assertEquals(TextRange(middle), fixture.value.value.selection)
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
        val expandedScroll = fieldScrollValue()

        composeTestRule.runOnIdle { fixture.height.value = 160.dp }
        composeTestRule.waitForIdle()

        assertTrue(fieldScrollValue() > expandedScroll)
        assertEquals(TextRange(caret), fixture.value.value.selection)
    }

    @Test
    fun `floating label stays inside the bounded field after internal scroll`() {
        setFieldContent(text = LONG_TEXT, label = "BBCode content")
        focusField()
        composeTestRule.onNodeWithTag(FIELD_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                scroll(0f, 100_000f)
            }
        composeTestRule.waitForIdle()

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
        insideOuterScroll: Boolean = false,
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
                        if (insideOuterScroll) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .testTag(OUTER_SCROLL_TAG),
                            ) {
                                Field(value = value, label = label)
                            }
                        } else {
                            Field(value = value, label = label)
                        }
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

    private fun fieldScrollValue(): Float = fieldScrollRange().value()

    private fun fieldScrollRange() = composeTestRule
        .onNodeWithTag(FIELD_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun outerScrollValue(): Float = composeTestRule
        .onNodeWithTag(OUTER_SCROLL_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

    private data class FieldFixture(
        val value: MutableState<TextFieldValue>,
        val height: MutableState<Dp>,
    )
}
