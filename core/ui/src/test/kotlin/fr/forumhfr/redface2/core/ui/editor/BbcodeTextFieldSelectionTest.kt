package fr.forumhfr.redface2.core.ui.editor

import android.widget.Magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** #447 — touch word and paragraph selection in the state-based BBCode field. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [NoopBbcodeTextFieldShadowMagnifier::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldSelectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var density: Density
    private lateinit var textLayout: TextLayoutResult

    @Test
    fun doubleTapSelectsOrdinaryWord() {
        setFieldContent(ORDINARY_TEXT)

        doubleTap(offset = 68)

        assertSelection(TextRange(67, 71))
    }

    @Test
    fun doubleTapSelectsHyphenSeparatedWordWithoutCrossingNewlines() {
        setFieldContent(NO_SPACE_TEXT)

        doubleTap(offset = 43)

        assertSelection(TextRange(42, 46))
    }

    @Test
    fun tripleTapStillSelectsCurrentLine() {
        setFieldContent(NO_SPACE_TEXT)
        val position = touchPosition(offset = 43)

        composeTestRule.onNode(hasSetTextAction()).performTouchInput {
            repeat(3) { index ->
                down(0, position)
                advanceEventTime(20)
                up(0)
                if (index < 2) advanceEventTime(80)
            }
        }
        composeTestRule.waitForIdle()

        assertSelection(TextRange(38, 56))
    }

    private fun setFieldContent(text: String) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    density = LocalDensity.current
                    val value = remember { mutableStateOf(TextFieldValue(text, TextRange.Zero)) }
                    val scrollState = rememberScrollState()
                    Box(Modifier.size(320.dp, 240.dp)) {
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { value.value = it },
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            scrollState = scrollState,
                            onTextLayout = { getResult ->
                                getResult()?.let { textLayout = it }
                            },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun doubleTap(offset: Int) {
        composeTestRule.onNode(hasSetTextAction()).performTouchInput {
            doubleClick(position = touchPosition(offset))
        }
        composeTestRule.waitForIdle()
    }

    private fun touchPosition(offset: Int): Offset {
        val characterCenter = textLayout.getBoundingBox(offset).center
        // OutlinedTextFieldDefaults.decorator uses 16 dp content padding on both axes. The
        // editable semantics node starts below BbcodeTextField's separate floating-label headroom.
        val contentPadding = with(density) { 16.dp.toPx() }
        return characterCenter + Offset(contentPadding, contentPadding)
    }

    private fun assertSelection(expected: TextRange) {
        val config = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
        assertEquals(expected, config[SemanticsProperties.TextSelectionRange])
    }

    private companion object {
        val ORDINARY_TEXT = List(6) { "le chat dort sur le tapis rouge" }.joinToString("\n")
        val NO_SPACE_TEXT = (1..40).joinToString("\n") { line ->
            "L${line.toString().padStart(2, '0')}-aaaa-bbbb-cccc"
        }
    }
}

/** Robolectric's [Magnifier] has no Surface; the platform magnifier is proven on device. */
@Implements(Magnifier::class)
class NoopBbcodeTextFieldShadowMagnifier {

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter") // Robolectric matches the platform signature.
    fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
        magnifierCenterX: Float,
        magnifierCenterY: Float,
    ) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
