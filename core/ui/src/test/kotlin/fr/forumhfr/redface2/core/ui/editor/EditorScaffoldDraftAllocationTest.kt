package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldDraftAllocationTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()

    @Test
    fun `J S10e landscape allocates three complete lines at fontScale 1 and two at 1_3 times L`() {
        host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
        host.width.value = 760.dp
        host.fontPreset.value = FontScalePreference.M
        host.body.edit { selection = TextRange.Zero }
        compose.setContent { host.Content() }
        compose.runOnIdle { host.insets(right = 24, nav = 0, ime = 198) }
        // ED-05/J: the reference is fs=1 (M), then 1.3 x L, not L at both OS scales.
        // L at fs=1 has 27.6 dp lines: a 74 dp viewport really holds only two whole lines.
        assertMeasuredAllocation(minimumLines = 3)
        compose.runOnIdle {
            host.scale.value = 1.3f
            host.fontPreset.value = FontScalePreference.L
        }
        assertMeasuredAllocation(minimumLines = 2)
    }

    private fun assertMeasuredAllocation(minimumLines: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        val layout = layouts.single()
        val field = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            val viewport = with(host.density) { host.scroll.viewportSize.toDp() }
            val lineHeight = with(host.density) { (layout.getLineBottom(1) - layout.getLineTop(1)).toDp() }
            val decoration = field.height - viewport
            val metrics = EditorChromeMetrics(compactHeight = true)
            val allocation = metrics.draftAllocation(138.dp, lineHeight, decoration, minimumLines)
            assertEquals(90f, field.height.value, 0.5f)
            assertTrue("The real BTF2 text viewport must be at least 74 dp", viewport >= 74.dp)
            assertEquals(16f, decoration.value, 0.5f)
            assertEquals(field.height, allocation.height)
            assertEquals(0, host.scroll.value)
            val completeLines = (0 until layout.lineCount).count { line ->
                layout.getLineTop(line) >= 0 && layout.getLineBottom(line) <= host.scroll.viewportSize
            }
            assertEquals(allocation.completeLines, completeLines)
            assertTrue("J: viewport=$viewport, measured line=$lineHeight, decoration=$decoration: $allocation",
                allocation.meetsLineMinimum)
            assertTrue(completeLines >= minimumLines)
        }
    }
}
