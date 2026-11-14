package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capture diagnostics; focus, isolation, geometry and actions are asserted independently. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPanelsRoborazziTest {
    @get:Rule val compose = createComposeRule()
    private val open = mutableStateOf(false)
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf(1f)
    private lateinit var bodyFocus: FocusRequester
    private lateinit var bodyState: TextFieldState
    private lateinit var inputMode: InputModeManager
    private val event = EditorEvent(
        EventKey(EditorSession("capture", "alice", "topic:23:42"),
            OperationRef.Local(EditorLocalOperation.QuoteFetch, 1), 1),
        EditorEventContent.Problem(EditorErrors.quoteFetchFailed), 1,
    )

    @Test
    fun `ED101 header open and closed in both themes and text scales`() { record(details = false) }

    @Test
    fun `ED45 detail open and closed in both themes and text scales`() { record(details = true) }

    private fun record(details: Boolean) {
        compose.setContent {
            // Open/close is one focus-restoration scenario; isolate it from every other theme/scale pair.
            key(dark.value, scale.value) { PanelSample(details) }
        }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        listOf(false, true).forEach { isDark ->
            listOf(1f, 1.3f).forEach { fontScale ->
                compose.runOnIdle { dark.value = isDark; scale.value = fontScale; open.value = true }
                compose.onNodeWithText("Message conservé").assertDoesNotExist()
                compose.onNodeWithTag("body", useUnmergedTree = true).assertExists().assertIsNotFocused()
                compose.runOnIdle { assertEquals(TextRange.Zero, bodyState.selection) }
                compose.onAllNodes(hasClickAction()).assertCountEquals(if (details) 3 else 2)
                val pane = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
                pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
                val bounds = pane.getUnclippedBoundsInRoot()
                assertEquals(360f, bounds.width.value, 0.5f)
                assertEquals(480f, bounds.height.value, 0.5f)
                capture(details, isDark, fontScale, "open")
                compose.onNodeWithText(if (details) "Masquer" else "Retour au message").performClick()
                compose.onNodeWithTag("body").assertIsFocused()
                compose.onNodeWithText("Message conservé").assertExists()
                compose.onAllNodes(hasClickAction()).assertCountEquals(1)
                pane.assertDoesNotExist()
                capture(details, isDark, fontScale, "closed")
            }
        }
    }

    @Composable
    private fun PanelSample(details: Boolean) {
        val density = LocalDensity.current
        inputMode = LocalInputModeManager.current
        bodyFocus = remember { FocusRequester() }
        bodyState = rememberTextFieldState("Corps fourni", TextRange.Zero)
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
            RedfaceTheme(darkTheme = dark.value, dynamicColor = false) {
                val modifier = Modifier.size(360.dp, 480.dp).testTag("panel-sample")
                if (details) EditorEventDetails(
                    event = event.takeIf { open.value }, bodyState = bodyState,
                    onReturnToBody = { bodyFocus.requestFocus() },
                    onClose = { open.value = false }, onReload = {}, onIgnoreQuote = {},
                    onDismiss = { open.value = false },
                    modifier = modifier, body = { Body() },
                ) else EditorHeaderPanel(
                    isOpen = open.value, title = "Titre et sous-catégorie",
                    bodyState = bodyState,
                    onReturnToBody = { bodyFocus.requestFocus() },
                    onClose = { open.value = false }, modifier = modifier, body = { Body() },
                ) {
                    OutlinedTextField("Un titre fourni", {}, Modifier.fillMaxWidth(), label = { Text("Titre") })
                    Text("Sous-catégorie fournie par l’hôte")
                }
            }
        }
    }

    private fun capture(details: Boolean, dark: Boolean, scale: Float, state: String) {
        // Both closed panels render the same body; verify both against one unchanged reference.
        val panel = if (details || state == "closed") "details" else "header"
        compose.onNodeWithTag("panel-sample").captureRoboImage(
            "src/test/screenshots/editor_panel_${panel}_${dark}_${scale}_$state.png",
        )
    }

    @Composable
    private fun Body() {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Text("Message conservé")
                BasicTextField(bodyState, Modifier.focusRequester(bodyFocus).testTag("body"))
            }
        }
    }
}
