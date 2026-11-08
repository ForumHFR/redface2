package fr.forumhfr.redface2.core.ui.editor

import android.view.View
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
import androidx.compose.ui.test.assertHasNoClickAction
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorEventControlTest {
    @get:Rule val compose = createComposeRule()
    private val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    private lateinit var view: View

    @Test
    fun `ED45 catalogue and terminal events expose exactly one named action and their exact key`() {
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
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
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

    @Test
    fun `ED40 ED44 ED45 event tap and next action consume one gesture and leave the second available`() {
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

    @Test
    fun `ED95 permanent live node survives absence progress replacement and unrelated recompositions`() {
        val current = mutableStateOf<EditorEvent?>(null)
        val injectedWidth = mutableStateOf(185.dp)
        compose.setContent {
            view = LocalView.current
            RedfaceTheme { EditorEventControl(current.value, injectedWidth.value, { _, _ -> error("No action") }) }
        }
        val id = compose.onNode(live).fetchSemanticsNode().id
        assertLive(id, "", LiveRegionMode.Polite)
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
            start to "Image 1/3…", update to "Image 1/3…",
            update.copy(key = update.key.copy(occurrence = 2)) to "Image 1/3…",
            masked.visibleEvent to "Envoi incertain, Afficher les détails",
            resumed to "", null to "", update to "", next to "Image 2/3…",
            next.copy(content = error.content) to "Envoi incertain, Afficher les détails",
            next.copy(content = finishedUpload(3)) to "3 images insérées, Masquer", null to "",
        )
        cases.forEach { (event, announcement) ->
            compose.runOnIdle { current.value = event }
            val mode = if (event?.rank == EditorEventRank.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            assertLive(id, announcement, mode)
            compose.onAllNodes(hasClickAction()).assertCountEquals(if (event?.action == null) 0 else 1)
            if (event?.content is EditorEventContent.InProgress && event.summary.resolve(context) != announcement) {
                compose.onNode(hasContentDescription(event.summary.resolve(context))).assertHasNoClickAction()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
            }
            compose.runOnIdle { injectedWidth.value = if (injectedWidth.value == 185.dp) 170.dp else 185.dp }
            assertLive(id, announcement, mode)
        }
    }

    @Test
    fun `ED49 states keep their icon tint live mode and full accessible name`() {
        val current = mutableStateOf<EditorEvent?>(null)
        var tints = emptyList<Color>()
        compose.setContent { RedfaceTheme {
            tints = with(MaterialTheme.colorScheme) { listOf(error, onSecondaryContainer, onSurfaceVariant) }
            EditorEventControl(current.value, 185.dp, { _, _ -> })
        } }
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
            val expectedMode = if (tint == 0) LiveRegionMode.Assertive else LiveRegionMode.Polite
            compose.onNode(live).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, expectedMode))
            compose.onNodeWithTag("editor-event-icon-$icon", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(EditorEventIconTintKey, tints[tint]))
            compose.onAllNodes(hasClickAction()).assertCountEquals(if (event.action == null) 0 else 1)
            if (event.action != null) compose.onNode(hasClickAction()).assert(hasContentDescription(
                event.accessibleSummary.resolve(context) + ", " + actionLabel(requireNotNull(event.action)),
            ))
        }
    }

    private fun assertLive(id: Int, name: String, mode: LiveRegionMode) = compose.assertLiveNode(id, name, mode, view)

    private fun actionLabel(action: EditorEventAction): String = context.getString(when (action) {
        EditorEventAction.Complete -> R.string.editor_action_complete
        EditorEventAction.Details -> R.string.editor_event_open_details
        EditorEventAction.Dismiss -> R.string.editor_action_dismiss
    })

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
}

/** Check both Compose trees and Android's pruned tree: a covered live sibling passes Compose queries alone. */
internal fun ComposeContentTestRule.assertLiveNode(id: Int, name: String, mode: LiveRegionMode, view: View) {
    val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
    listOf(false, true).forEach { unmerged ->
        onAllNodes(live, useUnmergedTree = unmerged).assertCountEquals(1)
        assertTrue(onAllNodes(hasClickAction(), useUnmergedTree = unmerged).fetchSemanticsNodes().size <= 1)
        val node = onNode(live, useUnmergedTree = unmerged)
        assertEquals(id, node.fetchSemanticsNode().id)
        node.assert(hasContentDescription(name)).assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode))
    }
    runOnIdle {
        val info = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
        assertEquals(name, info.contentDescription?.toString())
        assertEquals(if (mode == LiveRegionMode.Assertive) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else
            View.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)
        assertTrue(info.isVisibleToUser && !info.isClickable && !info.isFocusable)
    }
}

internal fun controlEvent(content: EditorEventContent, occurrence: Long = 1): EditorEvent = EditorEvent(
    EventKey(EditorSession("controls", "alice", "topic:23:42"),
        OperationRef.Local(EditorLocalOperation.Notice, 1), occurrence),
    content, occurrence,
)

internal fun uploadText(index: Int) = EditorText(R.string.editor_event_uploading, listOf(index, 3))

internal fun finishedUpload(count: Int) = EditorEventContent.Finished(
    summary = EditorText(R.plurals.editor_event_images_inserted_summary, listOf(count), quantity = count),
    accessibleSummary = EditorText(R.plurals.editor_event_images_inserted, listOf(count), quantity = count),
)
