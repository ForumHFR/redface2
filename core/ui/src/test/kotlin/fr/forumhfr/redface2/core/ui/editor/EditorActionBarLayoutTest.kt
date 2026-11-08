package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h480dp-xxhdpi")
class EditorActionBarLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorActionBarFixture()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun `ED21 ED30 ED38 two formats or three normal actions fit at 360 and 1_3 times L`() {
        host.scale.value = 1.3f
        host.shortcut.value = EditorBarShortcut(R.drawable.ic_editor_bold, "Raccourci", {})
        compose.setContent { host.Content() }
        val submit = bounds("editor-bar-submit")
        val scroll = bounds("editor-bar-scroll")
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
    fun `ED25 compact fixed slots overflow by width and are hidden before an event is squeezed`() {
        host.compact.value = true; host.header.value = true; host.scale.value = 1.3f
        host.width.value = 760.dp
        host.statuses.value = listOf(EditorAppBarStatus(R.drawable.ic_editor_quote, "12 citations", {}, 12),
            EditorAppBarStatus(R.drawable.ic_editor_restore, "Brouillon retrouvé", {}))
        host.bar.value = EditorBarState(EditorBarMode.Format)
        compose.setContent { host.Content() }
        val preview = compose.onNodeWithContentDescription("Afficher l’aperçu")
        assertTrue(preview.getUnclippedBoundsInRoot().right <= bounds("editor-bar-scroll").left)
        compose.runOnIdle { host.width.value = 360.dp }
        assertTrue(bounds("editor-bar-scroll").width >= 48.dp)
        preview.revealEditorControl()
        compose.onNodeWithContentDescription("12 citations").revealEditorControl()
        compose.onNodeWithContentDescription("Brouillon retrouvé").performScrollTo().assertIsDisplayed()
        // Cross the ED-25/92 boundary using measured Send, not an assumed font-dependent width.
        // ERROR's 4 dp gutter extension increases both available width and event reserve equally.
        val headerBoundary = bounds("editor-bar-submit").width + 32.dp + 48.dp + 189.dp
        // Stay 1 dp either side to avoid pixel/Dp rounding at the exact boundary.
        compose.runOnIdle {
            host.width.value = headerBoundary + 1.dp
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.missingTitle))
        }
        preview.assertDoesNotExist()
        val header = compose.onNodeWithContentDescription("Titre et sous-catégorie").assertIsDisplayed()
        val eventControl = compose.onNode(hasClickAction() and hasContentDescription("Titre manquant, Compléter"))
        val retainedEvent = eventControl.getUnclippedBoundsInRoot()
        assertTrue(header.getUnclippedBoundsInRoot().right <= retainedEvent.left)
        assertTrue(retainedEvent.width >= 185.dp)
        assertTrue(bounds("editor-bar-submit").left - retainedEvent.right >= 7.9.dp)
        compose.runOnIdle { host.width.value = headerBoundary - 1.dp }
        header.assertDoesNotExist()
        preview.assertDoesNotExist()
        compose.onNodeWithContentDescription("12 citations").assertDoesNotExist()
        compose.onNodeWithContentDescription("Brouillon retrouvé").assertDoesNotExist()
        val event = eventControl.getUnclippedBoundsInRoot()
        val send = bounds("editor-bar-submit")
        assertEquals(12f, event.left.value, 0.5f)
        assertTrue(event.width >= 185.dp)
        assertTrue(send.left - event.right >= 7.9.dp)
        assertEquals(48f, event.height.value, 0.5f)
        assertEquals(48f, bounds("bar").height.value, 0.5f)
    }

    @Test
    fun `ED41 ED46 ED95 modes events arm and progress keep geometry and a permanent live node`() {
        compose.setContent { host.Content() }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
            hasAnyAncestor(hasTestTag("bar")) and !hasTestTag("editor-bar-submit")
        val liveId = compose.onNode(live).fetchSemanticsNode().id
        val body = bounds("body")
        val send = bounds("editor-bar-submit")
        EditorBarMode.entries.forEach { mode ->
            compose.runOnIdle { host.bar.value = EditorBarState(mode) }
            assertEquals(body, bounds("body"))
            assertEquals(send, bounds("editor-bar-submit"))
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
        assertEquals(bounds("bar").top, progress.top)
        assertEquals(2f, progress.height.value, 0.5f)
        assertEquals(body, bounds("body"))
        assertEquals(send, bounds("editor-bar-submit"))
        compose.runOnIdle { host.event.value = null; host.uploading.value = false }
        assertEquals(liveId, compose.onNode(live).fetchSemanticsNode().id)
    }

    @Test
    fun `ED91 ED92 narrow event keeps its full name and the gap without shrinking font or targets`() {
        host.width.value = 240.dp; host.scale.value = 1.3f
        host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.draftWriteFailed))
        compose.setContent { host.Content() }
        val event = compose.onNode(
            hasClickAction() and hasContentDescription("Brouillon non écrit, Afficher les détails"))
            .getUnclippedBoundsInRoot()
        assertTrue(event.width >= 48.dp && event.height >= 48.dp)
        assertTrue(bounds("editor-bar-submit").left - event.right >= 7.9.dp)
        assertEquals(56f, bounds("bar").height.value, 0.5f)
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
        compose.onNodeWithContentDescription("Image").performClick()
        compose.runOnIdle { back.onBackPressed() }
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
