package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The common header/details overlay must apply the bottom union outside its scrolling viewport. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPanelInsetsTest {
    @get:Rule val compose = createComposeRule()
    private val body = TextFieldState("Message")
    private val title = TextFieldState("Titre fourni")
    private val titleFocus = FocusRequester()
    private lateinit var view: View
    private lateinit var density: Density

    @Test
    fun `ED04 ED101 panel scrolling keeps the focused title above the navigation and IME union`() {
        compose.setContent {
            view = LocalView.current
            density = LocalDensity.current
            RedfaceTheme(dynamicColor = false) {
                Box(Modifier.size(360.dp, 400.dp)) {
                    EditorHeaderPanel(true, "Titre et sous-catégorie", body, {}, {},
                        modifier = Modifier.fillMaxSize(), initialFocusRequester = titleFocus, body = {}) {
                        repeat(20) { Text("Ligne de panneau $it") }
                        BbcodeTextField(title, "Titre", Modifier.height(80.dp).focusRequester(titleFocus)
                            .testTag("panel-title"))
                    }
                }
            }
        }
        listOf(0, 20, 200).forEach { ime ->
            compose.runOnIdle { dispatchInsets(ime) }
            val titleNode = compose.onNodeWithTag("panel-title").performScrollTo()
            val viewport = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .getUnclippedBoundsInRoot()
            val field = titleNode.getUnclippedBoundsInRoot()
            val heading = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .getUnclippedBoundsInRoot()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            // The title stays pinned above the scrolling content (8 dp gap); the outer column owns the
            // union and the 16 dp padding, so the viewport ends 16 dp above the union exactly once.
            assertEquals((400 - maxOf(30, ime) - 16).toFloat(), viewport.bottom.value, 0.5f)
            assertEquals(heading.bottom.value + 8f, viewport.top.value, 0.5f)
            assertTrue("IME=$ime: field $field must start inside viewport $viewport", field.top >= viewport.top)
            // ScrollTo reveals the target, not the trailing content padding. The whole field must
            // fit above the union; the viewport height above catches missing or double insets.
            assertTrue("IME=$ime: field $field must end inside viewport $viewport", field.bottom <= viewport.bottom)
        }
    }

    private fun dispatchInsets(ime: Int) {
        val insets = with(density) {
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 30.dp.roundToPx()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime.dp.roundToPx()))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
        }
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }
}
