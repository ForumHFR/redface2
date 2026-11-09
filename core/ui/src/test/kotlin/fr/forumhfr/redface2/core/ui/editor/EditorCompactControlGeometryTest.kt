package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ED-05/W8-1: measure the actual visual content separately from semantics and touch targets. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorCompactControlGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
    private val submit get() = compose.onNodeWithTag("editor-bar-submit")
    private val event get() = compose.onNode(hasClickAction() and hasContentDescription("Titre manquant, Compléter"))

    @Test
    fun `ED05 compact visuals are 40 dp with measured 4 dp gaps and 48 dp targets at both scales`() {
        mount(compact = true)
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle { host.scale.value = scale }
            assertEquals(bounds("editor-scaffold-field").bottom, bounds("editor-scaffold-action-bar").top)
            assertControlStates(visibleHeight = 40f)
        }
    }

    @Test
    fun `ED05 regular height retains 48 dp visuals in the 56 dp bar at both scales`() {
        mount(compact = false)
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle { host.scale.value = scale }
            assertControlStates(visibleHeight = 48f)
        }
    }

    private fun mount(compact: Boolean) {
        host.width.value = 760.dp
        host.metrics.value = EditorWindowMetrics(if (compact) 360.dp else 740.dp, 24.dp, 0.dp)
        compose.setContent { host.Content() }
        compose.runOnIdle { host.insets(right = 24, nav = 0, ime = 198) }
    }

    private fun assertControlStates(visibleHeight: Float) {
        show(EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Format),
            firstStatus = host.statuses[0], secondStatus = host.statuses[1]))
        val initialField = bounds("editor-scaffold-field")
        assertControlBounds(submit, "editor-bar-submit-shape", visibleHeight)
        assertControlBounds(compose.onNodeWithContentDescription("Mise en forme"),
            "editor-bar-format-shape", visibleHeight)
        if (visibleHeight == 40f) assertCompactStatusBounds()
        show(EditorScaffoldChrome(bar = EditorBarState(arm = host.frozenArm)))
        compose.onNodeWithText("Confirmer").assertExists()
        assertControlBounds(submit, "editor-bar-submit-shape", visibleHeight)
        show(EditorScaffoldChrome(event = controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))))
        assertControlBounds(submit, "editor-bar-submit-shape", visibleHeight)
        assertControlBounds(event, "editor-bar-event-shape", visibleHeight)
        show(EditorScaffoldChrome(event = controlEvent(EditorEventContent.InProgress(
            EditorText(R.string.editor_event_sending)))))
        assertControlBounds(compose.onNodeWithContentDescription("Envoi…"),
            "editor-bar-event-shape", visibleHeight)
        assertEquals(initialField, bounds("editor-scaffold-field"))
    }

    private fun assertCompactStatusBounds() {
        val status = compose.onNodeWithContentDescription("12 citations").getUnclippedBoundsInRoot()
        val glyph = compose.onNode(hasTestTag(EDITOR_STATUS_GLYPH_TAG) and
            hasAnyAncestor(hasTestTag("editor-bar-FirstStatus")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = bounds(EDITOR_STATUS_BADGE_TAG)
        val bar = bounds("editor-scaffold-action-bar")
        assertTrue(status.height >= 48.dp && status.width >= 48.dp)
        assertEquals(4f, (glyph.top - bar.top).value, 0.5f)
        assertEquals(4f, (bar.bottom - badge.bottom).value, 0.5f)
    }

    private fun assertControlBounds(target: SemanticsNodeInteraction, shapeTag: String, visibleHeight: Float) {
        val touch = target.getUnclippedBoundsInRoot()
        val shape = bounds(shapeTag)
        val bar = bounds("editor-scaffold-action-bar")
        assertEquals(visibleHeight + 8f, bar.height.value, 0.5f)
        assertTrue("Interactive target must stay at least 48 dp wide", touch.width >= 48.dp)
        assertTrue("Interactive target must stay at least 48 dp high", touch.height >= 48.dp)
        assertEquals(visibleHeight, shape.height.value, 0.5f)
        assertEquals(4f, (shape.top - bar.top).value, 0.5f)
        assertEquals(4f, (bar.bottom - shape.bottom).value, 0.5f)
        assertEquals((touch.top + touch.height / 2).value, (shape.top + shape.height / 2).value, 0.5f)
    }

    @Test
    fun `ED05 both invisible 4 dp margins accept send confirm event and format taps`() {
        mount(compact = true)
        listOf(true, false).forEach { top ->
            show(EditorScaffoldChrome())
            host.calls.clear()
            tapMargin(submit, top)
            assertEquals(listOf("send"), host.calls)
            show(EditorScaffoldChrome(bar = EditorBarState(arm = host.frozenArm)))
            host.calls.clear()
            tapMargin(submit, top)
            assertEquals(listOf("confirm"), host.calls)
            show(EditorScaffoldChrome(event = controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))))
            host.calls.clear()
            tapMargin(event, top)
            assertEquals(listOf("event:Complete"), host.calls)
            compose.runOnIdle { host.panel.value = false }
            show(EditorScaffoldChrome(bar = EditorBarState(EditorBarMode.Format)))
            tapMargin(compose.onNodeWithContentDescription("Mise en forme"), top)
            compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode) }
        }
    }

    private fun show(chrome: EditorScaffoldChrome) {
        compose.runOnIdle { host.chrome.value = chrome }
    }

    private fun tapMargin(target: SemanticsNodeInteraction, top: Boolean) {
        val inset = with(host.density) { 1.dp.toPx() }
        target.performTouchInput { click(Offset(center.x, if (top) inset else height - inset)) }
    }
}
