package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    fun `ED21 ED30 ED38 four formats or five normal actions fit at 360 and 1_3 times L`() {
        host.scale.value = 1.3f
        host.shortcut.value = EditorBarShortcut(EditorShortcut.BOLD, EditorShortcutSurface.POST)
        compose.setContent { host.Content() }
        val submit = bounds("editor-bar-submit"); val scroll = bounds("editor-bar-scroll")
        assertEquals(48.dp, submit.width)
        listOf("Mise en forme", "Smileys", "Gras", "Image", "Options").forEach { label ->
            val target = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue(target.left >= scroll.left && target.right <= scroll.right)
            assertTrue(target.width >= 48.dp && target.height >= 48.dp)
        }
        compose.onNodeWithContentDescription("Options").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { host.bar.value = EditorBarState(EditorBarMode.Format) }
        val viewport = bounds("editor-bar-scroll")
        listOf("Gras", "Italique", "Souligné", "Barré").forEach { label ->
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
    fun `ED21 send circle leaves five complete format icons at 411 dp`() {
        host.width.value = 411.dp
        host.bar.value = EditorBarState(EditorBarMode.Format)
        compose.setContent { host.Content() }
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle { host.scale.value = scale }
            val submit = bounds("editor-bar-submit")
            val viewport = bounds("editor-bar-scroll")
            assertEquals(48.dp, submit.width)
            assertEquals(283.dp, viewport.width)
            listOf("Gras", "Italique", "Souligné", "Barré", "Lien").forEach { label ->
                val target = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
                assertEquals(48.dp, target.width)
                assertTrue(target.left >= viewport.left && target.right <= viewport.right)
                assertTrue(target.right <= submit.left)
            }
            val sixth = compose.onNodeWithContentDescription("Spoiler").getUnclippedBoundsInRoot()
            assertTrue(sixth.right > viewport.right)
        }
    }

    @Test
    fun `ED25 ED30 event reserve masks fixed slots before shrinking the summary`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f; host.width.value = 760.dp
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon retrouvé", {})
        host.bar.value = EditorBarState(EditorBarMode.Format); compose.setContent { host.Content() }
        val preview = compose.onNodeWithContentDescription("Aperçu")
        assertTrue(preview.targetBounds().right <= bounds("editor-bar-scroll").left)
        compose.runOnIdle { host.width.value = 360.dp }; assertTrue(bounds("editor-bar-scroll").width >= 48.dp)
        preview.revealEditorControl()
        compose.onNodeWithContentDescription("12 citations").revealEditorControl()
        compose.onNodeWithContentDescription("Brouillon retrouvé").performScrollTo().assertIsDisplayed()
        // The event reserve excludes the gutter extension when a fixed slot is retained.
        val headerBoundary = bounds("editor-bar-submit").width + 32.dp + 48.dp + 185.dp + 8.dp
        // Stay 1 dp either side to avoid pixel/Dp rounding at the exact boundary.
        compose.runOnIdle {
            host.width.value = headerBoundary + 1.dp
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))
        }
        preview.assertDoesNotExist()
        val header = compose.onNodeWithContentDescription("Titre et sous-catégorie").assertIsDisplayed()
        val eventControl = compose.onNode(hasClickAction() and hasContentDescription("Titre manquant, Compléter"))
        val retainedEvent = eventControl.getUnclippedBoundsInRoot()
        // Compact icons expose a 40 dp layer; the 48 dp slot is their native touch target.
        assertEquals(16f, header.targetBounds().left.value, 0.5f)
        assertEquals(64f, retainedEvent.left.value, 0.5f)
        assertTrue(header.targetBounds().right <= retainedEvent.left)
        assertTrue(retainedEvent.width >= 185.dp)
        assertTrue(bounds("editor-bar-submit").left - retainedEvent.right >= 7.9.dp)
        compose.runOnIdle { host.width.value = headerBoundary - 1.dp }
        header.assertDoesNotExist()
        preview.assertDoesNotExist()
        compose.onNodeWithContentDescription("12 citations").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé").assertDoesNotExist()
        val event = eventControl.getUnclippedBoundsInRoot(); val send = bounds("editor-bar-submit")
        assertEquals(12f, event.left.value, 0.5f); assertTrue(event.width >= 185.dp)
        assertTrue(send.left - event.right >= 7.9.dp); assertEquals(48f, event.height.value, 0.5f)
        assertEquals(48f, bounds("bar").height.value, 0.5f)
    }

    @Test
    fun `ED44 first gesture only acknowledges information in fixed and overflow locations`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "Citations", { host.calls += "quotes" })
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon",
            { host.calls += "draft" })
        compose.setContent { host.Content() }
        listOf(760.dp, headerBoundary() - 1.dp).forEach { width ->
            listOf("Titre et sous-catégorie" to "header", "Aperçu" to "preview",
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
    fun `ED25 ED30 events mask lower priorities and retained slots never shift through acknowledgement`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon retrouvé", {})
        compose.setContent { host.Content() }
        val labels = listOf("Titre et sous-catégorie", "Aperçu", "12 citations", "Brouillon retrouvé")
        listOf(1f, 1.3f).forEach { scale ->
            listOf(360.dp, 420.dp, 468.dp, 516.dp, 564.dp, 760.dp).forEach { width ->
                EditorBarMode.entries.forEach { mode ->
                    compose.runOnIdle {
                        host.scale.value = scale; host.width.value = width; host.bar.value = EditorBarState(mode)
                    }
                    assertFixedSlotsThroughEvents(labels)
                }
            }
        }
    }

    private fun assertFixedSlotsThroughEvents(labels: List<String>) {
        val resting = labels.associateWith { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
        // Slots are 48 dp targets; a compact icon's own node is only its 40 dp state layer.
        val fixed = labels.mapNotNull { label ->
            val node = compose.onNodeWithContentDescription(label)
            if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) null
            else label to node.targetBounds()
        }.toMap()
        assertTrue(fixed.isNotEmpty())
        val send = bounds("editor-bar-submit"); val body = bounds("body")
        // Independent oracle: keep only the resting priority prefix that leaves 185 dp + 8 dp.
        val expected = fixed.entries.takeWhile { send.left - it.value.right >= 193.dp }.associate { it.toPair() }
        val retainedIds = expected.keys.associateWith {
            compose.onNodeWithContentDescription(it).fetchSemanticsNode().id
        }
        val events = listOf(host.finished(), controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle)))
        events.forEach { event ->
            compose.runOnIdle { host.event.value = event }
            val retained = labels.filter { label ->
                compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(expected.keys.toList(), retained)
            expected.forEach { (label, position) ->
                val node = compose.onNodeWithContentDescription(label)
                assertEquals(position, node.targetBounds())
                assertEquals(retainedIds.getValue(label), node.fetchSemanticsNode().id)
            }
            val envelope = bounds("editor-bar-event")
            assertTrue(envelope.width >= 185.dp)
            assertEquals(expected.values.lastOrNull()?.right?.value ?: 12f, envelope.left.value, 0.5f)
            assertEquals(8f, (send.left - envelope.right).value, 0.5f)
            assertEquals(send, bounds("editor-bar-submit")); assertEquals(body, bounds("body"))
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("editor-bar-event")))
                .performTouchInput { click() }
            resting.forEach { (label, position) ->
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
            EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon", {}))
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
        compose.onNodeWithTag("editor-bar-submit-shape").assert(
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `ED91 ED92 ED133 full summaries fit the 360 dp bar in every mode height and supported scale`() {
        host.header.value = true
        host.firstStatus.value = EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12)
        host.secondStatus.value = EditorAppBarStatus(R.drawable.ic_ms_restore_page, "Brouillon retrouvé", {})
        compose.setContent { host.Content() }
        val events = editorErrorSamples.map { controlEvent(EditorEventContent.Problem(it)) } + listOf(
            controlEvent(EditorEventContent.InProgress(EditorText(R.string.editor_event_sending))),
            controlEvent(EditorEventContent.InProgress(uploadText(2))), host.finished(),
            controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted))),
        )
        listOf(1f, 1.3f).forEach { scale ->
            listOf(false, true).forEach { compact ->
                EditorBarMode.entries.forEach { mode ->
                    compose.runOnIdle { host.scale.value = scale; host.bar.value = EditorBarState(mode) }
                    assertEventEnvelopes(compact, events)
                }
            }
        }
    }

    private fun assertEventEnvelopes(compact: Boolean, events: List<EditorEvent>) {
        compose.runOnIdle { host.compact.value = compact; host.event.value = events.first() }
        val event = bounds("editor-bar-event"); val send = bounds("editor-bar-submit")
        val header = compose.onNodeWithContentDescription("Titre et sous-catégorie")
        if (compact) {
            header.assertIsDisplayed()
            assertEquals(16f, header.targetBounds().left.value, 0.5f)
            assertEquals(64f, header.targetBounds().right.value, 0.5f)
            assertTrue(header.targetBounds().right <= event.left)
        } else header.assertDoesNotExist()
        listOf("Aperçu", "12 citations", "Brouillon retrouvé").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        assertEquals(if (compact) 64f else 12f, event.left.value, 0.5f)
        assertTrue(event.width >= 185.dp)
        assertTrue(send.left - event.right >= 7.9.dp)
        assertEquals(if (compact) 48f else 56f, bounds("bar").height.value, 0.5f)
        events.forEach { item ->
            compose.runOnIdle { host.event.value = item }
            assertEquals(event, bounds("editor-bar-event")); assertEquals(send, bounds("editor-bar-submit"))
            assertFullSummary(item)
        }
    }

    private fun assertFullSummary(event: EditorEvent) {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithTag(EDITOR_EVENT_SUMMARY_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(requireNotNull(action).invoke(layouts))
        val text = layouts.single()
        assertEquals(event.summary.resolve(ApplicationProvider.getApplicationContext()), text.layoutInput.text.text)
        assertEquals(host.scale.value, text.layoutInput.density.fontScale, 0.001f)
        assertEquals(11f * FontScalePreference.L.factor, text.layoutInput.style.fontSize.value, 0.001f)
        assertEquals(1, text.lineCount)
        assertFalse(text.layoutInput.text.text, text.isLineEllipsized(0))
        assertEquals(text.layoutInput.text.length, text.getLineEnd(0, visibleEnd = true))
        assertTrue(text.multiParagraph.intrinsics.maxIntrinsicWidth <= text.size.width)
        assertTrue(text.getLineLeft(0) >= 0f && text.getLineRight(0) <= text.size.width)
    }

    @Test
    fun `ED85 system back closes visible color format and image without invoking a surface or send`() {
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
    }

    @Test
    fun `ED82 ED85 back skips every event masked mode and acknowledgement restores its Back level`() {
        lateinit var back: OnBackPressedDispatcher
        var nextLevelBacks = 0
        val event = host.finished()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            // Registered before the bar: a masked tool mode must let the next Back level run.
            BackHandler { nextLevelBacks++ }
            host.Content()
        }
        val modes = listOf(EditorBarMode.Format to "Gras", EditorBarMode.Color to "Rouge",
            EditorBarMode.Image to "Uploader une image")
        modes.forEach { (mode, label) ->
            compose.runOnIdle {
                host.bar.value = EditorBarState(mode); host.event.value = event
                host.eventActions.clear(); nextLevelBacks = 0
            }
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            compose.runOnIdle { back.onBackPressed() }
            compose.runOnIdle {
                assertEquals(mode, host.bar.value.mode)
                assertSame(event, host.event.value)
                assertEquals(1, nextLevelBacks)
                assertTrue(host.eventActions.isEmpty())
                assertTrue(host.calls.isEmpty())
            }
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
            compose.onNode(hasClickAction() and hasContentDescription("3 images insérées, Masquer")).performClick()
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(mode, host.bar.value.mode)
                assertEquals(listOf(event.key to EditorEventAction.Dismiss), host.eventActions)
                assertTrue(host.calls.isEmpty())
            }
            compose.runOnIdle { back.onBackPressed() }
            compose.runOnIdle {
                val parent = if (mode == EditorBarMode.Color) EditorBarMode.Format else EditorBarMode.Normal
                assertEquals(parent, host.bar.value.mode)
                assertEquals(1, nextLevelBacks)
            }
        }
    }
}
