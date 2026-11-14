package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
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

/** ED-62: an independently rendered three-line Text is the oracle, not the production equation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldMinimumHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `empty field floor excludes unused space`() = checkMinimum("")
    @Test fun `one line field floor excludes unused space`() = checkMinimum("Bonjour")
    @Test fun `long field floor includes decoration and three actual lines`() = checkMinimum(LONG_TEXT)

    @Test
    fun `zero height reports a completed measurement without inventing a floor`() {
        val state = TextFieldState("Bonjour")
        val height = mutableStateOf(0.dp)
        val heights = mutableListOf<Int>()
        var minimum: Int? = null
        compose.setContent {
            CompositionLocalProvider(LocalEditorPreviewFieldHeight provides { heights += it }) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    BbcodeTextField(state, "Message", Modifier.size(320.dp, height.value),
                        onMinimumHeightMeasured = { measured, _ -> minimum = measured })
                }
            }
        }
        compose.runOnIdle {
            assertTrue("a zero layout must publish a terminal measurement", heights.contains(0))
            assertEquals(null, minimum)
            height.value = 400.dp
        }
        compose.runOnIdle {
            assertTrue(heights.last() > 0)
            assertTrue(requireNotNull(minimum) > 0)
        }
    }

    private fun checkMinimum(initialText: String) {
        val state = TextFieldState(initialText)
        val height = mutableStateOf(400.dp)
        val scale = mutableStateOf(1f)
        val label = mutableStateOf(true)
        val scroll = ScrollState(0)
        var minimum = 0
        var threeLines = 0
        lateinit var density: Density
        compose.setContent {
            val physical = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(physical.density, scale.value)) {
                density = LocalDensity.current
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    Column {
                        BbcodeTextField(state, "Message", Modifier.size(320.dp, height.value),
                            showLabel = label.value, scrollState = scroll,
                            onMinimumHeightMeasured = { measured, _ -> minimum = measured })
                        // Same text style/font resolver, measured by a separate real text node.
                        Text("A\nB\nC", style = LocalTextStyle.current,
                            modifier = Modifier.onSizeChanged { threeLines = it.height })
                    }
                }
            }
        }
        listOf(1f, 1.3f).forEach { fontScale ->
            listOf(true, false).forEach { showLabel ->
                compose.runOnIdle {
                    scale.value = fontScale
                    label.value = showLabel
                    height.value = 400.dp
                    state.edit { replace(0, length, initialText) }
                }
                val at400 = compose.runOnIdle { minimum }
                assertTrue(at400 > threeLines)
                compose.runOnIdle { height.value = 280.dp }
                compose.runOnIdle {
                    assertEquals("unused space must not count as decoration", at400, minimum)
                    height.value = with(density) { at400.toDp() }
                    state.edit { replace(0, length, LONG_TEXT) }
                }
                compose.runOnIdle {
                    assertEquals("text length must not change the floor", at400, minimum)
                    assertEquals("the floor must leave exactly three real lines",
                        threeLines.toDouble(), scroll.viewportSize.toDouble(), 1.0)
                }
            }
        }
    }
}

private val LONG_TEXT = (1..40).joinToString("\n") { "Ligne $it" }
