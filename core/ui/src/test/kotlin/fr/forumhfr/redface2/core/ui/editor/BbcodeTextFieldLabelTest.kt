package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldLabelTest {
    @get:Rule val compose = createComposeRule()
    private val value = mutableStateOf(TextFieldValue("Message existant", TextRange(7)))
    private val state = TextFieldState("Message existant", TextRange(7))
    private var layout: androidx.compose.ui.text.TextLayoutResult? = null
    private fun field(api: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(api)))

    @Test
    fun `measured decorator reports the same viewport as BTF2 for labelled and compact fields`() {
        val showLabel = mutableStateOf(true)
        val scroll = ScrollState(0)
        var measuredViewport = 0
        compose.setContent {
            RedfaceTheme(dynamicColor = false) {
                BbcodeTextField(state, "Message", Modifier.height(180.dp), scrollState = scroll,
                    showLabel = showLabel.value, onViewportMeasured = { measuredViewport = it }) { result ->
                    layout = result()
                }
            }
        }
        listOf(true, false).forEach { visible ->
            compose.runOnIdle { showLabel.value = visible }
            compose.runOnIdle {
                assertTrue(measuredViewport > 0)
                assertEquals(scroll.viewportSize, measuredViewport)
                assertNotNull(layout)
            }
        }
    }

    @Test
    fun `both public overloads keep a visible label its headroom and no autofocus by default`() {
        compose.setContent {
            RedfaceTheme(dynamicColor = false) {
                Surface { Column {
                    // Intentionally omit showLabel: exercise the historical public defaults.
                    BbcodeTextField(value.value, { value.value = it }, "Message",
                        Modifier.height(180.dp).testTag("value"))
                    BbcodeTextField(state, "Message", Modifier.height(180.dp).testTag("state")) { result ->
                        layout = result()
                    }
                } }
            }
        }
        compose.onAllNodesWithText("Message").assertCountEquals(2)
        listOf("value", "state").forEach { api ->
            field(api).assertIsNotFocused()
            val hostTop = compose.onNodeWithTag(api).getUnclippedBoundsInRoot().top
            assertEquals(8f, (field(api).getUnclippedBoundsInRoot().top - hostTop).value, 0.5f)
        }
        compose.runOnIdle { assertFalse(state.undoState.canUndo); assertEquals(TextRange(7), state.selection) }
        assertNotNull(layout)
    }

    @Test
    fun `ED05 ED94 both overloads remove visual label and reserve while preserving name and controlled edits`() {
        compose.setContent {
            RedfaceTheme(dynamicColor = false) {
                Surface { Column {
                    BbcodeTextField(value.value, { value.value = it }, "Message",
                        Modifier.height(180.dp).testTag("value"), showLabel = false)
                    BbcodeTextField(state, "Message", Modifier.height(180.dp).testTag("state"), showLabel = false)
                } }
            }
        }
        compose.onAllNodesWithText("Message").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Message").assertCountEquals(2)
        listOf("value", "state").forEach { api ->
            assertEquals(compose.onNodeWithTag(api).getUnclippedBoundsInRoot().top,
                field(api).getUnclippedBoundsInRoot().top)
            field(api).performTextInput("Nouveau ")
        }
        compose.runOnIdle {
            assertEquals("MessageNouveau  existant", value.value.text)
            assertEquals(value.value.text, state.text.toString())
            assertEquals(value.value.selection, state.selection)
        }
    }
}
