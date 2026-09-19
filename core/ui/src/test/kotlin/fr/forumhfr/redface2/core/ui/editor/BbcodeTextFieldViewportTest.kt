package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
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

/** #275/#410/#447 — BTF2 owns the bounded BBCode viewport and caret following. */
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

    private lateinit var scrollState: ScrollState
    private var latestTextLayout: TextLayoutResult? = null

    @Test
    fun fieldFillsItsBoundedHost() {
        setFieldContent(text = "short", height = 240.dp)

        val host = composeTestRule.onNodeWithTag(HOST_TAG).fetchSemanticsNode()
        val field = composeTestRule.onNodeWithTag(FIELD_TAG).fetchSemanticsNode()
        assertEquals(host.size.width, field.size.width)
        assertEquals(host.size.height, field.size.height)
    }

    @Test
    fun longContentHasAnInternalScrollRange() {
        setFieldContent(text = LONG_TEXT)

        assertTrue("long text must overflow inside BTF2", scrollState.maxValue > 0)
    }

    @Test
    fun shortContentHasNoInternalScrollRange() {
        setFieldContent(text = "short")

        assertEquals(0, scrollState.maxValue)
    }

    @Test
    fun shrinkingHostKeepsMiddleCaretLineInsideViewport() {
        val caret = LONG_TEXT.length / 2
        val fixture = setFieldContent(
            text = LONG_TEXT,
            selection = TextRange(caret),
            height = 400.dp,
        )
        focusField()

        composeTestRule.runOnIdle { fixture.height.value = 180.dp }
        composeTestRule.waitForIdle()

        val layout = requireNotNull(latestTextLayout)
        val viewportTop = scrollState.value.toFloat()
        val viewportHeight = (layout.size.height - scrollState.maxValue).toFloat()
        val viewportBottom = viewportTop + viewportHeight
        val caretLine = layout.getLineForOffset(caret)
        val caretTop = layout.getLineTop(caretLine)
        val caretBottom = layout.getLineBottom(caretLine)
        assertTrue(
            "caret line starts above the BTF2 viewport " +
                "(top=$caretTop viewportTop=$viewportTop)",
            caretTop >= viewportTop - 1f,
        )
        assertTrue(
            "caret line ends below the BTF2 viewport " +
                "(bottom=$caretBottom viewportBottom=$viewportBottom)",
            caretBottom <= viewportBottom + 1f,
        )
    }

    private fun setFieldContent(
        text: String,
        selection: TextRange = TextRange(text.length),
        height: Dp = 240.dp,
    ): FieldFixture {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var fieldHeight: MutableState<Dp>
        latestTextLayout = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    value = remember { mutableStateOf(TextFieldValue(text, selection)) }
                    fieldHeight = remember { mutableStateOf(height) }
                    scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .size(320.dp, fieldHeight.value)
                            .testTag(HOST_TAG),
                    ) {
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            label = "Message",
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(FIELD_TAG),
                            scrollState = scrollState,
                            onTextLayout = { getResult -> latestTextLayout = getResult() },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        return FieldFixture(height = fieldHeight)
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private data class FieldFixture(val height: MutableState<Dp>)
}
