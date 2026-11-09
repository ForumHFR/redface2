package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
class EditorScaffoldLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorScaffoldFixture()
    private fun bounds(tag: String) = compose.onNodeWithTag("editor-scaffold-$tag").getUnclippedBoundsInRoot()
    private fun geometry() = listOf("field", "action-bar").map(::bounds) +
        compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()

    @Test
    fun `ED95 app bar retains independent status slots and the host supplied MP header label`() {
        host.chrome.value = EditorScaffoldChrome(firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        host.headerLabel.value = "Destinataires et sujet"
        compose.setContent { host.Content() }
        compose.onNodeWithTag(EDITOR_APP_BAR_TITLE).assert(hasContentDescription(
            "Destinataires et sujet : Répondre, Sujet de démonstration"))
        val second = compose.onNodeWithContentDescription("Brouillon retrouvé").fetchSemanticsNode().id
        compose.onAllNodes(hasContentDescription("12 citations")).assertCountEquals(1)
        compose.runOnIdle { host.chrome.value = host.chrome.value.copy(firstStatus = null) }
        compose.onNodeWithContentDescription("12 citations").assertDoesNotExist()
        compose.onAllNodes(hasContentDescription("Brouillon retrouvé")).assertCountEquals(1)
        assertEquals(second, compose.onNodeWithContentDescription("Brouillon retrouvé").fetchSemanticsNode().id)
        compose.runOnIdle { host.chrome.value = host.chrome.value.copy(firstStatus = host.statuses[0]) }
        assertEquals(second, compose.onNodeWithContentDescription("Brouillon retrouvé").fetchSemanticsNode().id)
    }

    @Test
    fun `ED95 compact retains the second status slot when the first disappears and reappears`() {
        host.metrics.value = EditorWindowMetrics(470.dp, 24.dp, 30.dp)
        host.width.value = 760.dp
        host.chrome.value = EditorScaffoldChrome(firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        compose.setContent { host.Content() }
        val second = compose.onNodeWithTag("editor-bar-SecondStatus")
        val id = second.fetchSemanticsNode().id
        second.assert(hasContentDescription("Brouillon retrouvé"))
        listOf(null, host.statuses[0]).forEach { first ->
            compose.runOnIdle { host.chrome.value = host.chrome.value.copy(firstStatus = first) }
            compose.onAllNodes(hasContentDescription("12 citations")).assertCountEquals(if (first == null) 0 else 1)
            compose.onAllNodes(hasContentDescription("Brouillon retrouvé")).assertCountEquals(1)
            second.assert(hasContentDescription("Brouillon retrouvé"))
            assertEquals(id, second.fetchSemanticsNode().id)
        }
    }

    @Test
    fun `ED02 ED03 field takes remainder with a 160 floor whenever 276 dp fit`() {
        compose.setContent { host.Content() }
        listOf(740 to 386, 530 to 230, 530 to 240).forEach { (height, ime) ->
            compose.runOnIdle {
                host.metrics.value = EditorWindowMetrics(height.dp, 24.dp, 0.dp)
                host.insets(nav = 0, ime = ime)
            }
            val available = height - 24 - ime
            val field = bounds("field")
            assertEquals(48f, bounds("app-bar").height.value, 0.5f)
            assertEquals(56f, bounds("action-bar").height.value, 0.5f)
            assertEquals((available - 116).toFloat(), field.height.value, 0.5f)
            assertEquals(6f, (field.top - bounds("app-bar").bottom).value, 0.5f)
            assertEquals(6f, (bounds("action-bar").top - field.bottom).value, 0.5f)
            assertEquals(available >= 276, field.height >= 159.9.dp)
        }
    }

    @Test
    fun `ED21 ED40 ED41 fifty keystrokes keep chrome geometry modes event and statuses unchanged`() {
        compose.setContent { host.Content() }
        listOf(false to 1f, false to 1.3f, true to 1f, true to 1.3f).forEach { (compact, scale) ->
            compose.runOnIdle {
                host.scale.value = scale
                host.metrics.value = EditorWindowMetrics(if (compact) 470.dp else 740.dp, 24.dp, 30.dp)
                host.insets(ime = if (compact) 220 else 386)
            }
            val initial = geometry()
            assertTypingGeometry(compact, initial)
        }
    }

    private fun assertTypingGeometry(compact: Boolean, initial: List<DpRect>) {
        EditorBarMode.entries.forEach { mode ->
            val events = listOf(null, controlEvent(EditorEventContent.Problem(EditorErrors.network)))
            events.forEach { event ->
                compose.runOnIdle {
                    host.chrome.value = EditorScaffoldChrome(bar = EditorBarState(mode), event = event,
                        firstStatus = host.statuses[0], secondStatus = host.statuses[1])
                }
                assertEquals(initial, geometry())
                val chromeBefore = host.chrome.value
                val appBar = if (compact) null else bounds("app-bar")
                repeat(50) { compose.onNode(hasSetTextAction()).performTextInput("x") }
                assertEquals(initial, geometry())
                if (appBar != null) assertEquals(appBar, bounds("app-bar"))
                compose.runOnIdle {
                    assertEquals(chromeBefore, host.chrome.value); assertTrue(host.calls.isEmpty())
                }
            }
        }
    }

    @Test
    fun `ED04 one bottom union and lateral insets apply to the whole column without changing P20 class`() {
        compose.setContent { host.Content() }
        listOf(0, 200).forEach { ime ->
            compose.runOnIdle { host.insets(left = 12, right = 24, nav = 30, ime = ime) }
            val column = bounds("column")
            assertEquals(12f, column.left.value, 0.5f)
            assertEquals(324f, column.width.value, 0.5f)
            assertEquals(24f, bounds("app-bar").top.value, 0.5f)
            assertEquals(324f, bounds("app-bar").width.value, 0.5f)
            assertEquals(324f, bounds("action-bar").width.value, 0.5f)
            assertEquals(292f, bounds("field").width.value, 0.5f)
            assertEquals((740 - maxOf(30, ime)).toFloat(), bounds("action-bar").bottom.value, 0.5f)
            assertEquals(48f, bounds("app-bar").height.value, 0.5f)
        }
    }

    @Test
    fun `ED05 ED90 ED94 compact has no app bar gap or visual label and one named scrollable field`() {
        host.metrics.value = EditorWindowMetrics(360.dp, 24.dp, 0.dp)
        host.width.value = 760.dp
        host.chrome.value = EditorScaffoldChrome(firstStatus = host.statuses[0], secondStatus = host.statuses[1])
        compose.setContent { host.Content() }
        compose.runOnIdle { host.insets(nav = 0, right = 24, ime = 186) }
        compose.onNodeWithTag("editor-scaffold-app-bar").assertDoesNotExist()
        compose.onNodeWithText("Message").assertDoesNotExist()
        val message = compose.onNode(hasSetTextAction() and hasContentDescription("Message"))
        message.assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        // BTF2 owns its viewport; it does not expose a verticalScroll modifier's semantics.
        message.performTextInputSelection(TextRange(host.body.text.length))
        compose.runOnIdle { assertTrue(host.scroll.maxValue > 0); assertTrue(host.scroll.value > 0) }
        assertEquals(24f, bounds("field").top.value, 0.5f)
        assertEquals(bounds("field").bottom, bounds("action-bar").top)
        assertEquals(48f, bounds("action-bar").height.value, 0.5f)
        compose.onNodeWithContentDescription("12 citations").assertExists()
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertTrue(field.top < bounds("action-bar").top)
    }

    @Test
    fun `ED91 ED92 ED95 event live node survives errors progress and full upload completion names`() {
        compose.setContent { host.Content() }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
            hasAnyAncestor(hasTestTag("editor-bar-event"))
        val id = compose.onNode(live).fetchSemanticsNode().id
        val initial = geometry()
        compose.runOnIdle {
            host.chrome.value = host.chrome.value.copy(
                event = controlEvent(EditorEventContent.Problem(EditorErrors.network)))
        }
        compose.onNode(live).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,
            LiveRegionMode.Assertive))
        compose.onNode(live).assert(hasClickAction())
        compose.onAllNodes(hasContentDescription("Hors connexion, Afficher les détails")).assertCountEquals(1)
        compose.runOnIdle {
            host.chrome.value = host.chrome.value.copy(
                event = controlEvent(EditorEventContent.InProgress(uploadText(1)), 2))
        }
        compose.onNode(live).assert(!hasClickAction())
        compose.runOnIdle {
            host.chrome.value = host.chrome.value.copy(
                event = controlEvent(EditorEventContent.InProgress(uploadText(2)), 2))
        }
        compose.onAllNodes(live).assertCountEquals(0) // Later progress is readable, without another live announcement.
        compose.runOnIdle { host.chrome.value = host.chrome.value.copy(event = controlEvent(finishedUpload(10), 3)) }
        compose.onNode(live).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
            listOf("10 images insérées, Masquer")))
        compose.onAllNodes(hasContentDescription("10 images insérées, Masquer")).assertCountEquals(1)
        val event = compose.onNode(hasClickAction() and hasContentDescription("10 images insérées, Masquer"))
            .getUnclippedBoundsInRoot()
        assertTrue(event.height >= 48.dp)
        assertTrue(compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot().left - event.right >= 7.9.dp)
        assertEquals(id, compose.onNode(live).fetchSemanticsNode().id)
        assertEquals(initial, geometry())
    }
}
