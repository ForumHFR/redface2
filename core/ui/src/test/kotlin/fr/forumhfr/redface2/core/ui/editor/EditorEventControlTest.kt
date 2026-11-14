package fr.forumhfr.redface2.core.ui.editor

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as Action
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.editor.EditorSession
import fr.forumhfr.redface2.core.domain.editor.EditorUploadToken
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers.callInstanceMethod

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorEventControlTest {
    @get:Rule val compose = createComposeRule()
    private val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    private lateinit var view: View
    @Test fun `ED45 catalogue and terminal events expose exactly one named action and their exact key`() {
        val current = mutableStateOf<EditorEvent?>(null)
        val actions = mutableListOf<Pair<EventKey, EditorEventAction>>()
        compose.setContent { RedfaceTheme {
            EditorEventControl(current.value, 185.dp, { key, action -> actions += key to action })
        } }
        val cases = listOf(
            EditorEventContent.Problem(EditorErrors.missingSubcategory) to EditorEventAction.Complete,
            EditorEventContent.Problem(EditorErrors.unknownRecipient) to EditorEventAction.Complete,
            EditorEventContent.Problem(EditorErrors.quoteFetchFailed) to EditorEventAction.Details,
            EditorEventContent.Problem(EditorErrors.formNetwork) to EditorEventAction.Details,
            EditorEventContent.Problem(EditorErrors.empty) to EditorEventAction.Details,
            EditorEventContent.Problem(EditorErrors.noImageReceived) to EditorEventAction.Details,
            EditorEventContent.Information(EditorErrors.network.summary) to EditorEventAction.Dismiss,
            EditorEventContent.Information(EditorErrors.network.summary, EditorErrors.network.detail) to
                EditorEventAction.Details,
            EditorEventContent.Finished(EditorErrors.network.summary) to EditorEventAction.Dismiss,
            EditorEventContent.Finished(EditorErrors.network.summary, EditorErrors.network.detail) to
                EditorEventAction.Details,
        )
        cases.forEachIndexed { index, (content, expected) ->
            val event = controlEvent(content, index.toLong())
            compose.runOnIdle { current.value = event }
            compose.onNode(hasClickAction()).assertIsEnabled()
                .assert(hasContentDescription(event.accessibleSummary.resolve(context) + ", " + actionLabel(expected)))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            assertEquals(actionLabel(expected),
                compose.onNode(hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
            assertEquals(event.key to expected, actions.last())
        }
        assertEquals(cases.size, actions.size)
    }
    @Test fun `ED40 ED44 ED45 event tap and next action consume one gesture and leave the second available`() {
        val information = controlEvent(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        val state = mutableStateOf(EditorEventState(information.key.session, events = listOf(information)))
        val actions = mutableListOf<String>()
        val consumed = mutableListOf<Boolean>()
        fun dispatch(input: EditorEventInput, action: String) {
            val transition = reduceEditorEvents(state.value, input)
            state.value = transition.state
            consumed += transition.gestureConsumed
            if (!transition.gestureConsumed) actions += action
            assertTrue(transition.commands.isEmpty())
        }
        compose.setContent { RedfaceTheme {
            Row(Modifier.clickable { actions += "parent" }) {
                Box(Modifier.width(185.dp).testTag("slot")) {
                    EditorEventControl(state.value.visibleEvent, 185.dp, { key, _ ->
                        dispatch(EditorEventInput.Activate(key), "event fallthrough")
                    })
                    if (state.value.visibleEvent == null) EditorActionIcon(R.drawable.ic_editor_options,
                        "Options", { dispatch(EditorEventInput.NextGesture(state.value.session), "Options") })
                }
                EditorActionIcon(R.drawable.ic_editor_done, "Envoyer", {
                    dispatch(EditorEventInput.NextGesture(state.value.session), "Envoyer")
                })
            }
        } }
        val cases = listOf(information to "slot", controlEvent(finishedUpload(3)) to "slot", information to "Envoyer")
        cases.forEach { (event, target) ->
            compose.runOnIdle { state.value = EditorEventState(event.key.session, events = listOf(event)) }
            val node = if (target == "slot") compose.onNodeWithTag(target, useUnmergedTree = true) else {
                compose.onNode(hasContentDescription(target))
            }
            actions.clear()
            consumed.clear()
            node.performTouchInput { click(Offset(24f, center.y)) }
            assertEquals(listOf(true), consumed)
            assertTrue(actions.isEmpty())
            assertEquals(null, state.value.visibleEvent)
            node.performTouchInput { click(Offset(24f, center.y)) }
            assertEquals(listOf(true, false), consumed)
            assertEquals(listOf(if (target == "slot") "Options" else "Envoyer"), actions)
        }
    }
    @Test fun `ED95 control survives absence and only starts and terminations make progress live`() {
        val current = mutableStateOf<EditorEvent?>(null)
        val injectedWidth = mutableStateOf(185.dp)
        compose.setContent {
            view = LocalView.current
            RedfaceTheme { EditorEventControl(current.value, injectedWidth.value, { _, _ -> error("No action") }) }
        }
        val id = compose.onNode(live).fetchSemanticsNode().id
        compose.assertLiveNode(id, "", LiveRegionMode.Polite, view)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        val session = controlEvent(EditorEventContent.InProgress(uploadText(1))).key.session
        fun upload(id: Long, index: Int) = EditorEvent(
            EventKey(session, OperationRef.Upload(EditorUploadToken(id, session)), 1),
            EditorEventContent.InProgress(uploadText(index), UploadProgress(index - 1, 3)), 1,
        )
        val start = upload(1, 1)
        val update = upload(1, 2)
        val error = controlEvent(EditorEventContent.Problem(EditorErrors.uncertain))
        val masked = reduceEditorEvents(EditorEventState(session, events = listOf(update)),
            EditorEventInput.Received(error)).state
        val resumed = reduceEditorEvents(masked, EditorEventInput.Acknowledge(error.key)).state.visibleEvent
        assertEquals(update, resumed)
        val next = upload(2, 2)
        val cases = listOf(
            start to LiveRegionMode.Polite, update to null,
            update.copy(key = update.key.copy(occurrence = 2)) to null,
            masked.visibleEvent to LiveRegionMode.Assertive, resumed to null,
            null to LiveRegionMode.Polite, update to null, next to LiveRegionMode.Polite,
            next.copy(content = error.content) to LiveRegionMode.Assertive,
            next.copy(content = finishedUpload(3)) to LiveRegionMode.Polite, null to LiveRegionMode.Polite,
        )
        cases.forEach { (event, mode) ->
            compose.runOnIdle { current.value = event }
            val name = event?.let { it.accessibleSummary.resolve(context) +
                (it.action?.let { action -> ", " + actionLabel(action) } ?: "") }.orEmpty()
            compose.assertLiveNode(id, name, mode, view)
            compose.onAllNodes(hasClickAction()).assertCountEquals(if (event?.action == null) 0 else 1)
            compose.runOnIdle { injectedWidth.value = if (injectedWidth.value == 185.dp) 170.dp else 185.dp }
            compose.assertLiveNode(id, name, mode, view)
        }
    }
    @Test fun `ED49 states keep their icon tint live mode and full accessible name`() {
        val current = mutableStateOf<EditorEvent?>(null)
        var tints = emptyList<Color>()
        compose.setContent { RedfaceTheme {
            view = LocalView.current
            tints = with(MaterialTheme.colorScheme) { listOf(error, onSecondaryContainer, onSurfaceVariant) }
            EditorEventControl(current.value, 185.dp, { _, _ -> })
        } }
        val id = compose.onNode(live).fetchSemanticsNode().id
        val cases = editorErrorSamples.map { error ->
            val isError = error.errorClass != EditorErrorClass.Information
            Triple(EditorEventContent.Problem(error),
                if (isError) R.drawable.ic_editor_submission_warning else R.drawable.ic_editor_info,
                if (isError) 0 else 1)
        } + listOf(
            Triple(EditorEventContent.InProgress(uploadText(1)), R.drawable.ic_editor_progress, 2),
            Triple(EditorEventContent.Finished(EditorText(R.string.editor_event_sending)),
                R.drawable.ic_editor_done, 1),
            Triple(EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)),
                R.drawable.ic_editor_info, 1),
        )
        cases.forEach { (content, icon, tint) ->
            val event = controlEvent(content)
            compose.runOnIdle { current.value = event }
            val name = event.accessibleSummary.resolve(context) +
                (event.action?.let { ", " + actionLabel(it) } ?: "")
            compose.assertLiveNode(id, name, if (tint == 0) LiveRegionMode.Assertive else LiveRegionMode.Polite, view)
            val control = compose.onNode(SemanticsMatcher.expectValue(EditorEventIconKey, icon), useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(EditorEventIconTintKey, tints[tint]))
            assertEquals(id, control.fetchSemanticsNode().id)
            compose.onAllNodes(hasClickAction()).assertCountEquals(if (event.action == null) 0 else 1)
        }
    }
    private fun actionLabel(action: EditorEventAction): String = context.getString(when (action) {
        EditorEventAction.Complete -> R.string.editor_action_complete
        EditorEventAction.Details -> R.string.editor_event_open_details
        EditorEventAction.Dismiss -> R.string.editor_action_dismiss
    })
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
}
/** Keyboard focus says nothing about TalkBack; inspect every node exposed by Android's provider. */
internal fun ComposeContentTestRule.assertLiveNode(id: Int, name: String, mode: LiveRegionMode?, view: View) {
    listOf(false, true).forEach { unmerged ->
        if (name.isNotEmpty()) onAllNodes(hasContentDescription(name), useUnmergedTree = unmerged).assertCountEquals(1)
    }
    runOnIdle {
        val nodes = view.editorAccessibilityNodes()
        val info = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
        assertEquals(if (name.isEmpty()) 0 else 1, nodes.count { it.isScreenReaderFocusable })
        assertEquals(name.isNotEmpty(), info.isScreenReaderFocusable)
        assertEquals(name, info.contentDescription?.toString().orEmpty())
        assertEquals("The live node must be an accessibility leaf", 0, info.childCount)
        if (name.isEmpty()) assertTrue(nodes.all { !it.isFocusable && it.actionList.all { action ->
            action == Action.ACTION_ACCESSIBILITY_FOCUS || action == Action.ACTION_CLEAR_ACCESSIBILITY_FOCUS } })
        assertTrue(info.isVisibleToUser && info.isImportantForAccessibility)
        val liveMode = if (mode == null) View.ACCESSIBILITY_LIVE_REGION_NONE else
            if (mode == LiveRegionMode.Assertive) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        assertEquals(liveMode, info.liveRegion)
        if (name.isNotEmpty()) assertEquals(1, nodes.count { it.contentDescription?.toString() == name })
    }
}
private fun View.editorAccessibilityNodes(id: Int = View.NO_ID): List<AccessibilityNodeInfo> {
    val info = requireNotNull(accessibilityNodeProvider.createAccessibilityNodeInfo(id))
    // Robolectric getChild() returns a placeholder; use Android's stored virtual child IDs instead.
    return listOf(info) + (0 until info.childCount).flatMap { index ->
        val childId = callInstanceMethod<Long>(info, "getChildId", ClassParameter.from(Int::class.java, index))
        editorAccessibilityNodes((childId shr 32).toInt())
    }
}
internal fun controlEvent(content: EditorEventContent, occurrence: Long = 1): EditorEvent = EditorEvent(
    EventKey(EditorSession("controls", "alice", "topic:23:42"),
        OperationRef.Local(EditorLocalOperation.Notice, 1), occurrence), content, occurrence,
)
internal fun uploadText(index: Int) = EditorText(R.string.editor_event_uploading, listOf(index, 3))
internal fun finishedUpload(count: Int) = EditorEventContent.Finished(
    summary = EditorText(R.plurals.editor_event_images_inserted_summary, listOf(count), quantity = count),
    accessibleSummary = EditorText(R.plurals.editor_event_images_inserted, listOf(count), quantity = count),
)
