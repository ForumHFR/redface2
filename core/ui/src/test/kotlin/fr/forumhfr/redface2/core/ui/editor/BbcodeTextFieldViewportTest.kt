package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #275/#410 — layout contract of the `fillViewport` mode of [BbcodeTextField]:
 *
 * - with content SHORTER than the bounded viewport, the field still fills it (v108 dogfooding:
 *   no blank under the field, tapping anywhere in the area focuses) and the wrapping column has
 *   nothing to scroll;
 * - with content TALLER than the viewport, the field GROWS (no internal text scroll) and the
 *   wrapping column becomes the scrollable — the ancestor-scrollable machinery that keeps the
 *   cursor visible under the IME only exists on that path.
 *
 * The IME interaction itself (resize re-anchoring, bring-into-view on refocus) is platform
 * behaviour that Robolectric cannot exercise — device dogfooding covers it; these tests pin the
 * structure that behaviour depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldViewportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `short content - the field fills the viewport and nothing scrolls`() {
        setFieldContent(text = "court")

        val viewport = composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
        val scrollRange = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("nothing to scroll when the field fits", scrollRange.maxValue() == 0f)

        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(
            "the field stretches to the whole bounded viewport (got ${field.size.height} " +
                "vs viewport ${viewport.size.height})",
            field.size.height >= viewport.size.height,
        )
    }

    @Test
    fun `long content - the field grows and the wrapping column scrolls`() {
        setFieldContent(text = (1..200).joinToString("\n") { "ligne $it" })

        val viewport = composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG)
            .fetchSemanticsNode()
        val scrollRange = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the wrapping column owns the scroll", scrollRange.maxValue() > 0f)

        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(
            "the field grows with its content instead of scrolling internally",
            field.size.height > viewport.size.height,
        )
    }

    @Test
    fun `default mode keeps the plain bounded field`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        BbcodeTextField(
                            value = TextFieldValue("court"),
                            onValueChange = {},
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(BBCODE_FIELD_VIEWPORT_TAG).assertDoesNotExist()
    }

    private fun setFieldContent(text: String) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        BbcodeTextField(
                            value = TextFieldValue(text),
                            onValueChange = {},
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            fillViewport = true,
                        )
                    }
                }
            }
        }
    }
}
