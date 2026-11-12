package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionController
import fr.forumhfr.redface2.core.domain.editor.SubmissionRequest
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.EditorShortcutSurface
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
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
class EditorActionBarTest {
    @get:Rule val compose = createComposeRule()
    private val host = EditorActionBarFixture()
    private fun click(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).revealEditorControl().performClick()

    @Test
    fun `ED20 ED21 ED22 ED27 opening and scrolling preserve selection and commands use keep`() {
        compose.setContent { host.Content() }; click("Mise en forme")
        compose.onNodeWithContentDescription("Mise en forme").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Développé"))
        compose.runOnIdle { assertEquals(TextRange(3, 8), host.body.selection) }
        compose.onNodeWithContentDescription("Lien").performScrollTo()
        compose.runOnIdle { assertEquals(TextRange(3, 8), host.body.selection); assertTrue(host.calls.isEmpty()) }
        click("Gras")
        compose.runOnIdle {
            assertEquals("Un [b]texte[/b] choisi", host.body.text.toString())
            assertEquals(TextRange(6, 11), host.body.selection); assertEquals(EditorBarMode.Format, host.bar.value.mode)
        }
        listOf("Gras", "Italique", "Souligné", "Barré", "Citation", "Code", "Bloc fixé", "Spoiler", "Lien")
            .forEach { name ->
                val node = compose.onNodeWithContentDescription(name).performScrollTo()
                node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).performClick()
            }
        assertEquals(10, host.calls.count { it == "bbcode" })
    }

    @Test
    fun `ED23 ED26 ED90 ED93 keyboard insertion returns focus and fifty keystrokes never send`() {
        compose.setContent { host.Content() }
        compose.runOnIdle {
            assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)); host.bodyFocus.requestFocus()
        }
        compose.onNodeWithTag("body").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Gras").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("body").assertIsFocused()
        val geometry = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        repeat(50) { compose.onNodeWithTag("body").performTextInput("a") }
        assertEquals(geometry, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("body").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Gras").assertIsFocused().performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft)
        }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Mise en forme").assertIsFocused()
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.onNodeWithContentDescription("Mise en forme").performKeyInput { pressKey(Key.Enter) }
        assertEquals(EditorBarMode.Format, host.bar.value.mode); assertEquals(listOf("bbcode"), host.calls)
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-xxhdpi")
    fun `ED32 compact preview eye dispatches once per Enter and Space key`() {
        host.compact.value = true; host.width.value = androidx.compose.ui.unit.Dp(760f)
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        val preview = compose.onNodeWithContentDescription("Aperçu").revealEditorControl()
        preview.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("preview"), host.calls)
        preview.assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("preview", "preview"), host.calls)
    }

    @Test
    fun `ED24 ED85 color and image return one level and color inserts without opening a surface`() {
        compose.setContent { host.Content() }; click("Mise en forme"); click("Couleur"); click("Retour")
        assertEquals(EditorBarMode.Format, host.bar.value.mode); click("Couleur")
        click("Bleu"); assertEquals(EditorBarMode.Format, host.bar.value.mode)
        assertTrue(host.body.text.contains("[#0000FF]texte[/#0000FF]"))
        click("Mise en forme"); click("Image"); click("Retour"); assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        assertEquals(listOf("bbcode"), host.calls)
    }

    @Test
    fun `ED31 image capabilities keep upload URL and MP tags distinct and close the level`() {
        compose.setContent { host.Content() }
        click("Image"); click("Uploader une image"); assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        assertEquals(listOf("upload"), host.calls)
        // No picker result is synthesized by the bar, including after the host closes the picker.
        assertTrue(host.eventActions.isEmpty()); compose.onNodeWithTag("body").assertExists()
        click("Image"); click("Insérer par URL"); assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.onNodeWithContentDescription("Baliser la sélection en [img]").assertDoesNotExist()
        compose.runOnIdle { host.imageUrl.value = false }
        click("Image"); compose.onNodeWithContentDescription("Insérer par URL").assertDoesNotExist()
        click("Baliser la sélection en [img]"); assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        assertEquals("Un [img]texte[/img] choisi", host.body.text.toString())
        assertEquals(listOf("upload", "url", "bbcode"), host.calls)
    }

    @Test
    fun `ED23 opening a surface closes format and arm masking restores the stored mode`() {
        host.compact.value = true; host.width.value = androidx.compose.ui.unit.Dp(760f)
        compose.setContent { host.Content() }
        click("Mise en forme"); compose.onNodeWithContentDescription("Aperçu").revealEditorControl().performClick()
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.runOnIdle { host.bar.value = EditorBarState(EditorBarMode.Format, host.arm) }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mise en forme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Confirmer").assertExists()
        compose.runOnIdle { host.bar.value = host.bar.value.copy(arm = null) }
        compose.onNodeWithContentDescription("Gras").assertExists(); assertFalse(host.calls.contains("disarm"))
    }

    @Test
    fun `ED38 shortcut is third only in normal mode and does not own a preference`() {
        host.shortcut.value = EditorBarShortcut(EditorShortcut.BOLD, EditorShortcutSurface.POST)
        compose.setContent { host.Content() }; click("Gras"); assertEquals(listOf("bbcode"), host.calls)
        click("Mise en forme"); click("Gras"); assertEquals(listOf("bbcode", "bbcode"), host.calls)
        compose.runOnIdle { host.bar.value = EditorBarState(arm = host.arm) }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.runOnIdle { host.bar.value = EditorBarState(); host.event.value = host.finished() }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.runOnIdle { host.event.value = null }
        compose.runOnIdle { host.bar.value = EditorBarState(); host.uploading.value = true }
        compose.onNodeWithContentDescription("Gras").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Mise en forme").assertIsNotEnabled()
    }

    @Test
    fun `ED36 unavailable image shortcut is absent even when the host exposes wrapping`() {
        host.imageUrl.value = false
        host.shortcut.value = EditorBarShortcut(EditorShortcut.IMAGE_SELECTION, EditorShortcutSurface.POST)
        compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Baliser la sélection en [img]").assertDoesNotExist()
        compose.runOnIdle {
            host.shortcut.value = EditorBarShortcut(
                EditorShortcut.IMAGE_SELECTION, EditorShortcutSurface.PRIVATE_MESSAGE_REPLY,
            )
        }
        click("Baliser la sélection en [img]")
        assertEquals("Un [img]texte[/img] choisi", host.body.text.toString())
        assertEquals(EditorBarMode.Normal, host.bar.value.mode)
        compose.runOnIdle { host.shortcut.value = EditorBarShortcut(EditorShortcut.NONE, EditorShortcutSurface.POST) }
        compose.onNodeWithContentDescription("Baliser la sélection en [img]").assertDoesNotExist()
    }

    @Test
    fun `ED40 ED45 ED95 terminal gesture dispatches its exact key once without opening Options`() {
        val terminal = host.finished(); host.event.value = terminal
        compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Options").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasContentDescription("3 images insérées, Masquer"))
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Options").assertExists()
        assertEquals(listOf(terminal.key to EditorEventAction.Dismiss), host.eventActions)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `ED40 ED44 information consumes Send before any arming validation or submission`() {
        host.event.value = controlEvent(
            EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        val key = host.event.value!!.key; compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Envoyer").performTouchInput { click() }
        assertTrue(host.calls.isEmpty()); assertEquals(listOf(key to EditorEventAction.Dismiss), host.eventActions)
        compose.onNodeWithContentDescription("Envoyer").performTouchInput { click() }; assertEquals(listOf("send"), host.calls)
    }

    @Test
    fun `ED34 ED90 submit keeps its named live button and focus while swapping decorative icons`() {
        compose.setContent { host.Content() }
        compose.runOnIdle { assertTrue(host.inputMode.requestInputMode(InputMode.Keyboard)) }
        val button = compose.onNodeWithTag("editor-bar-submit-shape")
        button.requestFocus().assertIsFocused()
        val id = button.fetchSemanticsNode().id
        listOf(false, true, false).forEach { armed ->
            compose.runOnIdle { host.bar.value = EditorBarState(arm = if (armed) host.frozenArm else null) }
            val label = if (armed) "Confirmer" else "Envoyer"
            button.assert(hasContentDescription(label)).assertIsFocused().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    if (armed) "Confirmation requise" else "Prêt à envoyer")).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals(id, button.fetchSemanticsNode().id)
            compose.onAllNodes(hasContentDescription(label) and hasClickAction()).assertCountEquals(1)
            val icon = if (armed) "editor-bar-submit-confirm-icon" else "editor-bar-submit-send-icon"
            val absent = if (armed) "editor-bar-submit-send-icon" else "editor-bar-submit-confirm-icon"
            compose.onNodeWithTag(icon, useUnmergedTree = true).assertExists().assert(
                SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)).assert(
                SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            compose.onNodeWithTag(absent, useUnmergedTree = true).assertDoesNotExist()
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `ED34 long press shows Send tooltip without arming or sending`() = assertSubmitTooltip(armed = false)

    @Test
    fun `ED34 armed long press shows confirmation tooltip without confirming or sending`() =
        assertSubmitTooltip(armed = true)

    private fun assertSubmitTooltip(armed: Boolean) {
        if (armed) host.bar.value = EditorBarState(arm = host.frozenArm)
        host.event.value = controlEvent(
            EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        val initial = host.bar.value
        compose.setContent { host.Content() }
        compose.onNodeWithTag("editor-bar-submit-shape").performTouchInput { longClick() }
        compose.onNodeWithText(if (armed) "Confirmer l’envoi" else "Envoyer", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(initial, host.bar.value)
            assertTrue(host.calls.isEmpty())
            assertTrue(host.eventActions.isEmpty())
        }
    }

    @Test
    fun `ED34 confirming through the icon still requires 400 ms on the controller clock`() {
        var nanos = android.os.SystemClock.elapsedRealtimeNanos()
        val controller = EditorSubmissionController(DiagnosticsClock { nanos }, host.arm.session)
        fun sync() {
            host.bar.value = host.bar.value.withSubmission(controller.state)
            host.sending.value = controller.blocksBodyMutation
        }
        host.submitActions = ArmedSubmitActions({ host.calls += "fallback" }, {
            if (controller.confirm(requireNotNull(host.bar.value.arm)) is SubmissionRequest.Accepted) {
                host.calls += "post"
            }
            sync()
        }, { host.calls += "legacy-disarm" }, { timeout ->
            controller.requestSubmit(timeout, true); sync()
        })
        compose.setContent { host.Content() }
        compose.onNodeWithContentDescription("Envoyer").performClick()
        compose.runOnIdle { nanos += 399_000_000L }
        compose.onNodeWithContentDescription("Confirmer").performClick()
        compose.runOnIdle { assertTrue(host.calls.isEmpty()); nanos += 1_000_000L }
        compose.onNodeWithContentDescription("Confirmer").performClick()
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        assertEquals(listOf("post"), host.calls)
    }

    @Test
    fun `ED34 ED44 ED95 keyboard submit confirms once and host expiry honors the accessible timeout`() {
        var nanos = android.os.SystemClock.elapsedRealtimeNanos()
        val controller = EditorSubmissionController(DiagnosticsClock { nanos }, host.arm.session)
        fun sync() {
            host.bar.value = host.bar.value.withSubmission(controller.state)
            host.sending.value = controller.blocksBodyMutation
        }
        host.submitActions = ArmedSubmitActions({ host.calls += "fallback" }, {
            host.calls += "confirm"
            val request = controller.confirm(requireNotNull(host.bar.value.arm))
            if (request is SubmissionRequest.Accepted) host.calls += "post"
            sync()
        }, { host.calls += "legacy-disarm" }, { timeout ->
            host.calls += "send:$timeout"; controller.requestSubmit(timeout, true); sync()
        })
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long,
                containsIcons: Boolean, containsText: Boolean, containsControls: Boolean): Long {
                assertEquals(4_000L, originalTimeoutMillis)
                assertTrue(containsIcons && containsText && containsControls)
                return 8_000L
            }
        }
        compose.setContent { CompositionLocalProvider(LocalAccessibilityManager provides manager) { host.Content() } }
        compose.runOnIdle { host.inputMode.requestInputMode(InputMode.Keyboard) }
        val button = compose.onNodeWithTag("editor-bar-submit-shape")
        fun state(description: String) = button.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        state("Prêt à envoyer")
        button.requestFocus().performKeyInput { pressKey(Key.Enter) }
        state("Confirmation requise"); button.assertIsFocused()
        compose.runOnIdle { nanos += 4_000_000_000L; controller.expireArm(); sync() }
        state("Confirmation requise")
        compose.runOnIdle { nanos += 4_000_000_000L; controller.expireArm(); sync() }
        state("Prêt à envoyer"); button.assertIsFocused(); assertEquals(listOf("send:8000"), host.calls)
        button.performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { host.event.value = controlEvent(
            EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted))) }
        val key = host.event.value!!.key
        button.performKeyInput { pressKey(Key.Enter) }
        state("Confirmation requise"); assertEquals(listOf(key to EditorEventAction.Dismiss), host.eventActions)
        assertEquals(listOf("send:8000", "send:8000"), host.calls); compose.runOnIdle { nanos += 400_000_000L }
        button.performKeyInput { pressKey(Key.NumPadEnter) }
        button.assertIsNotEnabled().performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        button.performTouchInput { click(); click() }
        assertEquals(listOf("send:8000", "send:8000", "confirm", "post"), host.calls)
    }

    @Test
    fun `ED23 ED40 ED41 ED82 acknowledging an event restores format and its selection`() {
        host.bar.value = EditorBarState(EditorBarMode.Format); compose.setContent { host.Content() }
        val geometry = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            host.event.value = controlEvent(
                EditorEventContent.Information(EditorText(R.string.editor_event_already_quoted)))
        }
        compose.onNodeWithContentDescription("Gras").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasContentDescription("Déjà citée, Masquer")).performTouchInput { click() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        assertEquals(EditorBarMode.Format, host.bar.value.mode); assertEquals(TextRange(3, 8), host.body.selection)
        assertEquals(geometry, compose.onNodeWithTag("bar").getUnclippedBoundsInRoot())
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `ED40 ED45 complete details and dismiss never dispatch a tool or a remedy`() {
        compose.setContent { host.Content() }
        listOf(EditorErrors.missingTitle to "Titre manquant, Compléter",
            EditorErrors.network to "Hors connexion, Afficher les détails").forEach { (error, label) ->
            val event = controlEvent(EditorEventContent.Problem(error)); compose.runOnIdle { host.event.value = event }
            compose.onNode(hasClickAction() and hasContentDescription(label)).performTouchInput { click() }
            assertEquals(event.key to event.action, host.eventActions.last())
            compose.onNodeWithContentDescription("Options").assertExists(); assertTrue(host.calls.isEmpty())
        }
        assertEquals(2, host.eventActions.size)
    }

    @Test
    fun `ED40 ED46 progress is non actionable under an error and form load creates no event`() {
        host.loading.value = true
        compose.setContent { host.Content() }; compose.onNodeWithTag("editor-bar-progress").assertExists()
        compose.onNodeWithContentDescription("Mise en forme").assertExists()
        compose.onNodeWithContentDescription("Envoyer").assertIsNotEnabled()
        compose.runOnIdle {
            host.loading.value = false; host.uploading.value = true; host.progress.value = UploadProgress(1, 3)
            host.event.value = controlEvent(EditorEventContent.Problem(EditorErrors.network))
        }
        compose.onNodeWithTag("editor-bar-progress").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Envoyer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Options").assertDoesNotExist()
        compose.runOnIdle { host.event.value = controlEvent(EditorEventContent.InProgress(uploadText(2))) }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("bar")))
            .assertCountEquals(1) // disabled Send only; the body is outside the bar
        compose.onNodeWithTag("editor-bar-submit-shape").assertIsNotEnabled()
        compose.onAllNodes(hasContentDescription("Image 2/3…") and hasAnyAncestor(hasTestTag("bar")))
            .assertAny(!hasClickAction()).assertAll(!hasClickAction())
        assertTrue(host.calls.isEmpty())
    }
}
