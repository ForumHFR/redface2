package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.domain.editor.EditorSession
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

    @Test
    fun `ED45 catalogue and terminal events expose exactly one named action and their exact key`() {
        val current = mutableStateOf<EditorEvent?>(null)
        val actions = mutableListOf<Pair<EventKey, EditorEventAction>>()
        compose.setContent {
            RedfaceTheme { EditorEventControl(current.value, 185.dp, { key, action -> actions += key to action }) }
        }
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
        compose.setContent {
            RedfaceTheme {
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
            }
        }
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
            RedfaceTheme { EditorEventControl(current.value, injectedWidth.value, { _, _ -> error("No action") }) }
        }
        val id = compose.onNode(live).fetchSemanticsNode().id
        assertLive(id, "", LiveRegionMode.Polite)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        val start = controlEvent(EditorEventContent.InProgress(uploadText(1), UploadProgress(0, 3)))
        compose.runOnIdle { current.value = start }
        assertLive(id, "Image 1/3…", LiveRegionMode.Polite)
        val update = start.copy(content = EditorEventContent.InProgress(uploadText(2), UploadProgress(1, 3)))
        compose.runOnIdle { current.value = update }
        assertLive(id, "Image 1/3…", LiveRegionMode.Polite)
        compose.onNode(hasContentDescription("Image 2/3…")).assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { current.value = update.copy(key = update.key.copy(occurrence = 2)) }
        assertLive(id, "Image 2/3…", LiveRegionMode.Polite)
        compose.runOnIdle { injectedWidth.value = 170.dp }
        assertLive(id, "Image 2/3…", LiveRegionMode.Polite)
        compose.runOnIdle { current.value = start.copy(content = EditorEventContent.Problem(EditorErrors.uncertain)) }
        assertLive(id, "Envoi incertain, Afficher les détails", LiveRegionMode.Assertive)
        compose.runOnIdle { current.value = start.copy(content = finishedUpload(3)) }
        assertLive(id, "3 images insérées, Masquer", LiveRegionMode.Polite)
        compose.runOnIdle { current.value = null }
        assertLive(id, "", LiveRegionMode.Polite)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `ED49 states keep their icon tint live mode and full accessible name`() {
        val current = mutableStateOf<EditorEvent?>(null)
        var tints = emptyList<Color>()
        compose.setContent {
            RedfaceTheme {
                tints = with(MaterialTheme.colorScheme) { listOf(error, onSecondaryContainer, onSurfaceVariant) }
                EditorEventControl(current.value, 185.dp, { _, _ -> })
            }
        }
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

    private fun assertLive(id: Int, name: String, mode: LiveRegionMode) {
        val node = compose.onNode(live)
        assertEquals(id, node.fetchSemanticsNode().id)
        node.assert(hasContentDescription(name)).assertHasNoClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode))
    }

    private fun actionLabel(action: EditorEventAction): String = context.getString(when (action) {
        EditorEventAction.Complete -> R.string.editor_action_complete
        EditorEventAction.Details -> R.string.editor_event_open_details
        EditorEventAction.Dismiss -> R.string.editor_action_dismiss
    })

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
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
