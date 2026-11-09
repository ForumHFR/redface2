package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h480dp-xxhdpi")
class EditorActionBarLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorActionBarFixture()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun headerBoundary() = bounds("editor-bar-submit").width + EditorChromeMetrics.horizontalGutters +
        EditorChromeMetrics.minimumTargetSize + EditorChromeMetrics.formatMinimumWidth

    @Test
    fun `ED21 ED30 ED38 two formats or three normal actions fit at 360 and 1_3 times L`() {
        host.scale.value = 1.3f
        host.shortcut.value = EditorBarShortcut(R.drawable.ic_editor_bold, "Raccourci", {})
        compose.setContent { host.Content() }
        val submit = bounds("editor-bar-submit"); val scroll = bounds("editor-bar-scroll")
        listOf("Mise en forme", "Smileys", "Raccourci").forEach { label ->
            val target = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(target.left >= scroll.left && target.right <= scroll.right)
            assertTrue(target.width >= 48.dp && target.height >= 48.dp)
        }
        compose.onNodeWithContentDescription("Options").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { host.bar.value = EditorBarState(EditorBarMode.Format) }
        val viewport = bounds("editor-bar-scroll")
        listOf("Gras", "Italique").forEach { label ->
            val target = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(target.left >= viewport.left && target.right <= viewport.right)
            assertEquals(48f, target.width.value, 0.5f)
        }
        val trigger = compose.onNodeWithContentDescription("Mise en forme").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().assertIsDisplayed()
        assertEquals(trigger, compose.onNodeWithContentDescription("Mise en forme").getUnclippedBoundsInRoot())
        assertEquals(submit, bounds("editor-bar-submit"))
    }

    @Test
    fun `ED25 compact fixed slots overflow only by width while the event summary shrinks`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f; host.width.value = 760.dp
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", {})
        host.bar.value = EditorBarState(EditorBarMode.Format); compose.setContent { host.Content() }
        val preview = compose.onNodeWithContentDescription("Afficher l’aperçu")
        assertTrue(preview.getUnclippedBoundsInRoot().right <= bounds("editor-bar-scroll").left)
        compose.runOnIdle { host.width.value = 360.dp }; assertTrue(bounds("editor-bar-scroll").width >= 48.dp)
        preview.revealEditorControl()
        compose.onNodeWithContentDescription("12 citations").revealEditorControl()
        compose.onNodeWithContentDescription("Brouillon retrouvé").performScrollTo().assertIsDisplayed()
        // Cross the Format allocation boundary using measured Send, independently of the event.
        val headerBoundary = headerBoundary()
        // Stay 1 dp either side to avoid pixel/Dp rounding at the exact boundary.
        compose.runOnIdle {
            host.width.value = headerBoundary + 1.dp
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))
        }
        preview.assertDoesNotExist()
        val header = compose.onNodeWithContentDescription("Titre et sous-catégorie").assertIsDisplayed()
        val eventControl = compose.onNode(hasClickAction() and hasContentDescription("Titre manquant, Compléter"))
        val retainedEvent = eventControl.getUnclippedBoundsInRoot()
        assertEquals(16f, header.getUnclippedBoundsInRoot().left.value, 0.5f)
        assertEquals(64f, retainedEvent.left.value, 0.5f)
        assertTrue(header.getUnclippedBoundsInRoot().right <= retainedEvent.left)
        assertTrue(retainedEvent.width >= 48.dp && retainedEvent.width < 185.dp)
        assertTrue(bounds("editor-bar-submit").left - retainedEvent.right >= 7.9.dp)
        compose.runOnIdle { host.width.value = headerBoundary - 1.dp }
        header.assertDoesNotExist()
        preview.assertDoesNotExist()
        compose.onNodeWithContentDescription("12 citations").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé").assertDoesNotExist()
        val event = eventControl.getUnclippedBoundsInRoot(); val send = bounds("editor-bar-submit")
        assertEquals(12f, event.left.value, 0.5f); assertTrue(event.width >= 48.dp)
        assertTrue(send.left - event.right >= 7.9.dp); assertEquals(48f, event.height.value, 0.5f)
        assertEquals(48f, bounds("bar").height.value, 0.5f)
    }

    @Test
    fun `ED44 first gesture only acknowledges information in fixed and overflow locations`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", { host.calls += "quotes" })
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon",
            { host.calls += "draft" })
        compose.setContent { host.Content() }
        listOf(760.dp, headerBoundary() - 1.dp).forEach { width ->
            listOf("Titre et sous-catégorie" to "header", "Afficher l’aperçu" to "preview",
                "Citations" to "quotes", "Brouillon" to "draft").forEach { (label, call) ->
                listOf(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)),
                    EditorEventContent.Problem(EditorErrors.noImageReceived)).forEach { content ->
                    assertInformationGesture(width, label, call, controlEvent(content))
                }
            }
        }
    }

    private fun assertInformationGesture(width: Dp, label: String, call: String, information: EditorEvent) {
        compose.runOnIdle {
            host.width.value = width; host.bar.value = EditorBarState(EditorBarMode.Format)
            host.calls.clear(); host.eventActions.clear(); host.event.value = information
        }
        val target = compose.onNodeWithContentDescription(label)
        if (width == 760.dp) target.performTouchInput { click() }
        else {
            target.assertDoesNotExist()
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("editor-bar-event")))
                .performTouchInput { click() }
        }
        assertTrue(host.calls.isEmpty()); assertEquals(EditorBarMode.Format, host.bar.value.mode)
        val action = if (width == 760.dp) EditorEventAction.Dismiss else information.action
        assertEquals(listOf(information.key to action), host.eventActions)
        target.revealEditorControl().performTouchInput { click() }
        assertEquals(listOf(call), host.calls); assertEquals(EditorBarMode.Normal, host.bar.value.mode)
    }

    @Test
    fun `ED25 ED41 fixed slots keep their measured positions through an event and its acknowledgement`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", {})
        compose.setContent { host.Content() }
        val labels = listOf("Titre et sous-catégorie", "Afficher l’aperçu", "12 citations", "Brouillon retrouvé")
        listOf(360.dp, 760.dp).forEach { width ->
            EditorBarMode.entries.forEach { mode ->
                compose.runOnIdle { host.width.value = width; host.bar.value = EditorBarState(mode) }
                assertFixedSlotsThroughEvents(labels)
            }
        }
    }

    private fun assertFixedSlotsThroughEvents(labels: List<String>) {
        val fixed = labels.mapNotNull { label ->
            val node = compose.onNodeWithContentDescription(label)
            if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) null
            else label to node.getUnclippedBoundsInRoot()
        }.toMap()
        assertTrue(fixed.isNotEmpty())
        val send = bounds("editor-bar-submit"); val body = bounds("body")
        val events = listOf(host.finished(), controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle)))
        events.forEach { event ->
            compose.runOnIdle { host.event.value = event }
            val retained = labels.filter { label ->
                compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
            }
            // The oracle is the measured allocation at rest, never a special case for narrow events.
            assertEquals(fixed.keys.toList(), retained)
            fixed.forEach { (label, position) ->
                assertEquals(position, compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot())
            }
            val envelope = bounds("editor-bar-event")
            assertTrue(envelope.width >= 48.dp)
            assertEquals(fixed.values.last().right.value, envelope.left.value, 0.5f)
            assertEquals(8f, (send.left - envelope.right).value, 0.5f)
            assertEquals(send, bounds("editor-bar-submit")); assertEquals(body, bounds("body"))
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("editor-bar-event")))
                .performTouchInput { click() }
            fixed.forEach { (label, position) ->
                assertEquals(position, compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot())
            }
            assertEquals(send, bounds("editor-bar-submit")); assertEquals(body, bounds("body"))
            assertEquals(event.key to event.action, host.eventActions.last())
        }
    }

    @Test
    fun `ED95 independent status nodes survive absence and both overflow boundaries`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.bar.value = EditorBarState(EditorBarMode.Format); compose.setContent { host.Content() }
        val tags = listOf("editor-bar-FirstStatus", "editor-bar-SecondStatus")
        val statuses = listOf(EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", {}, 12),
            EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon", {}))
        val ids = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().id }
        listOf(false to false, false to true, true to true, true to false, false to false).forEach { (a, b) ->
            compose.runOnIdle {
                host.width.value = 760.dp
                host.firstStatus.value = statuses[0].takeIf { a }; host.secondStatus.value = statuses[1].takeIf { b }
            }
            val visible = listOf(a, b)
            // Wide status right edge already includes the 16 dp start gutter and preceding fixed slots.
            val boundaries = tags.map { bounds("editor-bar-submit").width + 16.dp + 96.dp + bounds(it).right }
            (listOf(760.dp, 360.dp) + boundaries.flatMap { listOf(it - 1.dp, it + 1.dp) } + 760.dp).forEach { width ->
                compose.runOnIdle { host.width.value = width }
                tags.forEachIndexed { index, tag ->
                    assertStatusAnchor(tag, ids[index], visible[index], width < boundaries[index])
                    if (visible[index]) compose.onNodeWithContentDescription(statuses[index].label)
                        .revealEditorControl()
                }
            }
        }
    }

    private fun assertStatusAnchor(tag: String, id: Int, visible: Boolean, overflow: Boolean) {
        listOf(false, true).forEach { unmerged ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode()
            assertEquals(id, node.id); assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
            assertFalse(node.config.isMergingSemanticsOfDescendants)
            if (!visible) listOf(SemanticsProperties.ContentDescription, SemanticsProperties.Text,
                SemanticsProperties.Focused, SemanticsProperties.Role, SemanticsActions.OnClick,
                SemanticsActions.OnLongClick, SemanticsActions.RequestFocus).forEach {
                assertFalse("Absent status exposes $it", node.config.contains(it))
            }
            else assertEquals(overflow, hasAnyAncestor(hasScrollAction()).matches(node))
        }
    }

    @Test
    fun `ED41 ED46 ED95 modes events arm and progress keep geometry and a permanent live node`() {
        compose.setContent { host.Content() }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
            hasAnyAncestor(hasTestTag("editor-bar-event"))
        val liveId = compose.onNode(live).fetchSemanticsNode().id
        val body = bounds("body"); val send = bounds("editor-bar-submit")
        EditorBarMode.entries.forEach { mode ->
            compose.runOnIdle { host.bar.value = EditorBarState(mode) }
            assertEquals(body, bounds("body")); assertEquals(send, bounds("editor-bar-submit"))
            assertEquals(56f, bounds("bar").height.value, 0.5f)
        }
        compose.runOnIdle { host.bar.value = host.bar.value.copy(arm = host.arm) }
        assertEquals(send, bounds("editor-bar-submit"))
        compose.onNodeWithTag("editor-bar-submit").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Confirmation requise"))
        compose.runOnIdle {
            host.uploading.value = true; host.progress.value = UploadProgress(1, 3)
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        }
        assertEquals(liveId, compose.onNode(live).fetchSemanticsNode().id)
        compose.onNode(live).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        val progress = bounds("editor-bar-progress")
        assertEquals(bounds("bar").top, progress.top); assertEquals(2f, progress.height.value, 0.5f)
        assertEquals(body, bounds("body")); assertEquals(send, bounds("editor-bar-submit"))
        compose.runOnIdle { host.event.value = null; host.uploading.value = false }
        assertEquals(liveId, compose.onNode(live).fetchSemanticsNode().id)
    }

    @Test
    fun `ED91 ED92 ED133 event ranks share an envelope extended only without preceding fixed slots`() {
        host.scale.value = 1.3f; compose.setContent { host.Content() }
        val events = listOf(controlEvent(EditorEventContent.Problem(EditorErrors.draftWriteFailed)),
            controlEvent(EditorEventContent.Problem(EditorErrors.network)),
            controlEvent(EditorEventContent.InProgress(EditorText(R.string.editor_event_sending))), host.finished(),
            controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted))))
        listOf(240.dp, 360.dp).forEach { width -> listOf(false, true).forEach { compact ->
            assertEventEnvelopes(width, compact, events)
        } }
    }

    private fun assertEventEnvelopes(width: Dp, compact: Boolean, events: List<EditorEvent>) {
        compose.runOnIdle { host.width.value = width; host.compact.value = compact; host.event.value = events.first() }
        val event = bounds("editor-bar-event"); val send = bounds("editor-bar-submit")
        val retainsPreview = compact && width >= send.width + EditorChromeMetrics.horizontalGutters +
            EditorChromeMetrics.minimumTargetSize + EditorChromeMetrics.minimumContentWidth
        val preview = compose.onNodeWithContentDescription("Afficher l’aperçu")
        if (retainsPreview) preview.assertIsDisplayed() else preview.assertDoesNotExist()
        assertEquals(if (retainsPreview) 64f else 12f, event.left.value, 0.5f)
        assertTrue(event.width >= 48.dp)
        if (!retainsPreview && width == 360.dp) assertTrue(event.width >= 185.dp)
        assertTrue(send.left - event.right >= 7.9.dp)
        assertEquals(if (compact) 48f else 56f, bounds("bar").height.value, 0.5f)
        compose.onNodeWithContentDescription("Brouillon non écrit, Afficher les détails").assertExists()
        events.forEach { item ->
            compose.runOnIdle { host.event.value = item }
            assertEquals(event, bounds("editor-bar-event")); assertEquals(send, bounds("editor-bar-submit"))
        }
    }

    @Test
    fun `ED85 system back closes color format and image without invoking a surface or send`() {
        lateinit var back: OnBackPressedDispatcher
        host.bar.value = EditorBarState(EditorBarMode.Color)
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            host.Content()
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Format, host.bar.value.mode) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.bar.value.mode) }
        compose.onNodeWithContentDescription("Image").performClick(); compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, host.bar.value.mode); assertTrue(host.calls.isEmpty()) }
        compose.runOnIdle {
            host.bar.value = EditorBarState(EditorBarMode.Format)
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(EditorBarMode.Normal, host.bar.value.mode)
            assertTrue(host.event.value != null) // Closing the stored mode never acknowledges the event.
        }
    }
}
