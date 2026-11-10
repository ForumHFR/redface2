package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.unit.DpRect
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
        .layoutBounds()
    private val submit get() = compose.onNodeWithTag("editor-bar-submit")
    private val event get() = compose.onNode(hasClickAction() and hasContentDescription("Titre manquant, Compléter"))
    private val statusButton get() = compose.onNode(hasClickAction() and
        hasAnyAncestor(hasTestTag("editor-bar-FirstStatus")), useUnmergedTree = true)

    // The node's own placed size; native minimum-touch-target expansion only shows in touchBoundsInRoot.
    private fun SemanticsNodeInteraction.layoutBounds(): DpRect {
        val node = fetchSemanticsNode()
        val position = node.positionInRoot
        return with(host.density) {
            DpRect(position.x.toDp(), position.y.toDp(),
                (position.x + node.size.width).toDp(), (position.y + node.size.height).toDp())
        }
    }

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

    @Test
    fun `ED05 compact icon layers stay 40 dp when pressed and accept taps 2 dp outside at both scales`() {
        mount(compact = true)
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle { host.scale.value = scale }
            listOf(true, false).forEach { top -> assertIconMarginActions(top) }
        }
    }

    private fun assertIconMarginActions(top: Boolean) {
        val chrome = EditorScaffoldChrome(firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        show(chrome)
        host.calls.clear()
        tapIconMargin(compose.onNode(hasClickAction() and hasContentDescription("Smileys")), top)
        assertEquals(listOf("smileys"), host.calls)
        show(chrome)
        host.calls.clear()
        tapIconMargin(statusButton, top)
        assertEquals(listOf("quotes"), host.calls)
        show(chrome.copy(bar = EditorBarState(EditorBarMode.Format)))
        val format = compose.onNode(hasClickAction() and hasContentDescription("Mise en forme")).assertIsSelected()
        assertEquals(format.layoutBounds(), bounds("editor-bar-format-shape"))
        tapIconMargin(format, top)
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.chrome.value.bar.mode) }
        show(chrome.copy(bar = EditorBarState(EditorBarMode.Color)))
        tapIconMargin(compose.onNode(hasClickAction() and hasContentDescription("Rouge")), top)
        compose.runOnIdle { assertEquals(EditorBarMode.Format, host.chrome.value.bar.mode) }
        show(chrome.copy(bar = EditorBarState(EditorBarMode.Image)))
        host.calls.clear()
        tapIconMargin(compose.onNode(hasClickAction() and hasContentDescription("Uploader une image")), top)
        assertEquals(listOf("upload"), host.calls)
    }

    private fun assertIconLayerBounds(target: SemanticsNodeInteraction) {
        val shape = target.layoutBounds()
        val touch = target.fetchSemanticsNode().touchBoundsInRoot
        val minimumTouchSize = with(host.density) { 48.dp.toPx() }
        val bar = bounds("editor-scaffold-action-bar")
        assertEquals(40f, shape.width.value, 0.5f)
        assertEquals(40f, shape.height.value, 0.5f)
        assertEquals(4f, (shape.top - bar.top).value, 0.5f)
        assertEquals(4f, (bar.bottom - shape.bottom).value, 0.5f)
        assertTrue("Native touch target must stay at least 48 dp wide", touch.width >= minimumTouchSize)
        assertTrue("Native touch target must stay at least 48 dp high", touch.height >= minimumTouchSize)
    }

    private fun tapIconMargin(target: SemanticsNodeInteraction, top: Boolean) {
        assertIconLayerBounds(target)
        val shape = target.layoutBounds()
        val bar = bounds("editor-scaffold-action-bar")
        val point = with(host.density) {
            val y = if (top) shape.top - 2.dp else shape.bottom + 2.dp
            Offset((shape.left + shape.width / 2 - bar.left).toPx(), (y - bar.top).toPx())
        }
        val barNode = compose.onNodeWithTag("editor-scaffold-action-bar", useUnmergedTree = true)
        barNode.performTouchInput { down(point) }
        assertIconLayerBounds(target)
        barNode.performTouchInput { up() }
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
        compose.onNodeWithTag("editor-bar-submit-shape", useUnmergedTree = true).assert(hasClickAction())
        assertControlBounds(submit, "editor-bar-submit-shape", visibleHeight)
        assertControlBounds(compose.onNodeWithContentDescription("Mise en forme"),
            "editor-bar-format-shape", visibleHeight, nativeTarget = true)
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

    /**
     * Send and the event keep a 48 dp semantics node around their shape. A [nativeTarget] icon's node is
     * its state layer: it must coincide with the shape, and Compose's touch expansion is the target.
     */
    private fun assertControlBounds(
        target: SemanticsNodeInteraction, shapeTag: String, visibleHeight: Float, nativeTarget: Boolean = false,
    ) {
        val touch = if (nativeTarget) target.targetBounds() else target.getUnclippedBoundsInRoot()
        val shape = bounds(shapeTag)
        val bar = bounds("editor-scaffold-action-bar")
        assertEquals(visibleHeight + 8f, bar.height.value, 0.5f)
        assertTrue("Interactive target must stay at least 48 dp wide", touch.width >= 48.dp)
        assertTrue("Interactive target must stay at least 48 dp high", touch.height >= 48.dp)
        assertEquals(visibleHeight, shape.height.value, 0.5f)
        assertEquals(4f, (shape.top - bar.top).value, 0.5f)
        assertEquals(4f, (bar.bottom - shape.bottom).value, 0.5f)
        assertEquals((touch.top + touch.height / 2).value, (shape.top + shape.height / 2).value, 0.5f)
        if (nativeTarget) {
            assertEquals(shape, target.layoutBounds())
            assertEquals((touch.left + touch.width / 2).value, (shape.left + shape.width / 2).value, 0.5f)
        }
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
            tapBarMargin(compose.onNodeWithContentDescription("Mise en forme"), top)
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

    /** A native-target icon's node is its 40 dp layer: tap the bar 1 dp from its edge, outside that layer. */
    private fun tapBarMargin(target: SemanticsNodeInteraction, top: Boolean) {
        val shape = target.layoutBounds()
        val bar = bounds("editor-scaffold-action-bar")
        val point = with(host.density) {
            val y = if (top) 1.dp else bar.height - 1.dp
            Offset((shape.left + shape.width / 2 - bar.left).toPx(), y.toPx())
        }
        assertTrue("Tap must land outside the 40 dp layer", (bar.top + 1.dp) < shape.top)
        compose.onNodeWithTag("editor-scaffold-action-bar", useUnmergedTree = true)
            .performTouchInput { click(point) }
    }
}
