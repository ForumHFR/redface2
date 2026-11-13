package fr.forumhfr.redface2.feature.editor

import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** #447 — real editor layouts keep the draft and restore actions usable on an IME viewport. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    // A ModalBottomSheet composes in its own dialog window: its density follows the system configuration,
    // as in production, never the host LocalDensity. Tests at a font scale set both, then reset the system.
    @After fun resetSystemFontScale() = RuntimeEnvironment.setFontScale(1f)

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun postEditLocksBodyAndInsertionsThroughFailureUntilHydration() {
        val state = mutableStateOf(PostEditorState(PostEditorMode.Edit, 23, 42, 101, 1, 0,
            isLoadingForm = true))
        val body = TextFieldState("seed")
        val intents = mutableListOf<PostEditorIntent>()
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(state.value, { intents += it }, body)
            }
        }
        // Panel close buttons can accept focus only outside touch mode (Focusability.SystemDefined).
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(isLoadingForm = false, submitError = EditorErrors.formNetwork)
        }
        // The event masks the tools until its detail closes, as required by the new chrome.
        assertPostBodyAndInsertionsLocked(toolsVisible = false)
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(PostEditorIntent.ReloadRequested), intents) }
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        // A blank unresolved attempt keeps its uncertainty detail and still offers the form GET.
        compose.runOnIdle { state.value = state.value.copy(submitError = EditorErrors.uncertain) }
        assertPostBodyAndInsertionsLocked(toolsVisible = false)
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Envoi incertain").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        assertPostBodyAndInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(List(2) { PostEditorIntent.ReloadRequested }, intents)
            assertEquals("seed", body.text.toString())
            state.value = state.value.copy(draftHydratedFromForm = true, isLoadingForm = true, submitError = null)
        }
        compose.onNode(hasSetTextAction()).performTextInput(" typed")
        assertPostInsertionsEnabled()
        compose.runOnIdle { assertEquals("seed typed", body.text.toString()) }
        // Reply is editable even with an unknown body and an initial GET still running.
        compose.runOnIdle {
            state.value = state.value.copy(mode = PostEditorMode.Reply, draftHydratedFromForm = false)
        }
        compose.onNode(hasSetTextAction()).performTextInput(" reply")
        assertPostInsertionsEnabled()
        compose.runOnIdle { assertEquals("seed typed reply", body.text.toString()) }
    }

    @Test
    fun `post ED02 ED05 ED12 ED167 field and send stay fixed after fifty nearby touch edits`() {
        val host = PostEditorChromeFixture()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val bounds = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        // Compact mode removes only the visual label, retaining the field's accessible name.
        compose.onNodeWithContentDescription("Message").assertExists()
        field.performTouchInput { click(Offset(width - 12f, height - 12f)) }
        repeat(50) { field.performTextInput("a") }
        assertEquals(bounds, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertTrue(host.calls.isEmpty()); assertTrue(host.body.text.length >= 50) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun `post ED85 system back closes color format preview then requests saved close`() {
        val host = PostEditorChromeFixture()
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        lateinit var previewLabel: String
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            previewLabel = stringResource(UiR.string.editor_preview_label)
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - FIELD_LABEL_HEADROOM)
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Options").assertExists()
        compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed().performClick()
        compose.onNodeWithTag("post-editor-preview").assertExists()
        compose.onNodeWithTag("editor-preview-divider-target").assertExists()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(listOf(PostEditorIntent.TogglePreview, PostEditorIntent.TogglePreview,
            PostEditorIntent.CloseRequested), host.calls)
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
    fun `post restore sheet states its offer once and never ellipsizes it at font scale 1_3`() {
        val host = PostEditorChromeFixture()
        host.state = host.state.copy(restorableDraft = "Brouillon conservé")
        setScaledContent { PostEditorTestContent(host.state, host::dispatch, host.body, host.events) }
        assertRestoreOfferStatedOnce()
        compose.onNode(isDialog()).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
    fun `topic restore sheet states its offer once and never ellipsizes it at font scale 1_3`() {
        val fixture = TopicFormChromeFixture()
        fixture.state = fixture.state.copy(restorableDraft = "Brouillon conservé")
        setScaledContent {
            TopicFormTestContent(fixture.state, fixture::dispatch, fixture.body, fixture.events, fixture.memory)
        }
        assertRestoreOfferStatedOnce()
        compose.onNode(isDialog()).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun `post restore sheet offer and actions are reachable above a landscape keyboard at font scale 1_0`() {
        val host = PostEditorChromeFixture()
        host.state = host.state.copy(restorableDraft = "Brouillon conservé")
        assertCompactRestoreSheet(1.0f) { PostEditorTestContent(host.state, host::dispatch, host.body, host.events) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun `post restore sheet offer and actions are reachable above a landscape keyboard at font scale 1_3`() {
        val host = PostEditorChromeFixture()
        host.state = host.state.copy(restorableDraft = "Brouillon conservé")
        assertCompactRestoreSheet(1.3f) { PostEditorTestContent(host.state, host::dispatch, host.body, host.events) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun `topic restore sheet offer and actions are reachable above a landscape keyboard at font scale 1_3`() {
        val fixture = TopicFormChromeFixture()
        fixture.state = fixture.state.copy(restorableDraft = "Brouillon conservé")
        assertCompactRestoreSheet(1.3f) {
            TopicFormTestContent(fixture.state, fixture::dispatch, fixture.body, fixture.events, fixture.memory)
        }
    }

    private fun setScaledContent(fontScale: Float = 1.3f, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale) // Before the sheet's dialog window is created.
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) { content() }
            }
        }
    }

    /** Gate R1b T3 r1 m3: a short panel title, then the offer once, uncapped above both 48 dp actions. */
    private fun assertRestoreOfferStatedOnce() {
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
        compose.onAllNodesWithText(RESTORE_OFFER, useUnmergedTree = true).assertCountEquals(1)
        assertOfferLaidOutWhole(1.3f)
        for (label in listOf("Restaurer", "Ignorer")) compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
    }

    /** The expanded offer sheet scrolls its whole sentence and both 48 dp actions above the IME. */
    private fun assertCompactRestoreSheet(fontScale: Float, content: @Composable () -> Unit) {
        lateinit var view: View
        lateinit var density: Density
        setScaledContent(fontScale) {
            view = LocalView.current
            density = LocalDensity.current
            content()
        }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle {
            val ime = with(density) { LANDSCAPE_KEYBOARD.roundToPx() }
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            // A ModalBottomSheet owns a separate window: the activity's insets alone cannot test it.
            ViewCompat.dispatchApplyWindowInsets(ShadowDialog.getLatestDialog().window!!.decorView, insets)
        }
        compose.onAllNodesWithText(RESTORE_OFFER, useUnmergedTree = true).assertCountEquals(1)
        val offer = assertOfferLaidOutWhole(fontScale).performScrollTo().assertIsDisplayed()
        assertEquals("The offer must be entirely in view", offer.getUnclippedBoundsInRoot(), offer.getBoundsInRoot())
        val keyboardTop = compose.onNode(isDialog()).getUnclippedBoundsInRoot().bottom - LANDSCAPE_KEYBOARD
        for (label in listOf("Restaurer", "Ignorer")) {
            val action = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            assertTrue("$label must stay above the keyboard", action.getUnclippedBoundsInRoot().bottom <= keyboardTop)
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            assertEquals("$label must be entirely in view", text.getUnclippedBoundsInRoot(), text.getBoundsInRoot())
        }
    }

    /** The offer is laid out whole at [fontScale]: no line cap, no ellipsis, every character. */
    private fun assertOfferLaidOutWhole(fontScale: Float): SemanticsNodeInteraction {
        val offer = compose.onNodeWithText(RESTORE_OFFER, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        val read = requireNotNull(offer.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action)
        assertTrue(read(layouts))
        val layout = layouts.single()
        val last = layout.lineCount - 1
        assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
        assertFalse("The offer must not exceed a line cap", layout.multiParagraph.didExceedMaxLines)
        assertFalse("The offer must not be ellipsized", layout.isLineEllipsized(last))
        assertEquals("Every character must be laid out", RESTORE_OFFER.length,
            layout.getLineEnd(last, visibleEnd = true))
        return offer
    }

    @Test
    @Config(sdk = [29, 34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
    fun `post ED85 back dismisses action and status tooltips without requesting close`() {
        val host = PostEditorChromeFixture()
        host.state = host.state.copy(restorableDraft = "Brouillon")
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events)
            }
        }
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        for (label in listOf("Mise en forme", "Brouillon retrouvé, restaurer ou ignorer")) {
            compose.onNodeWithContentDescription(label).performTouchInput { longClick() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(field, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
            compose.runOnIdle { assertTrue(host.calls.isEmpty()) }
        }
    }

    private fun assertPostBodyAndInsertionsLocked(toolsVisible: Boolean = true) {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            val control = compose.onNodeWithContentDescription(label)
            if (toolsVisible) control.assertIsNotEnabled() else control.assertDoesNotExist()
        }
        for (label in listOf("Gras", "Lien", "Puce", "Citation", "E-mail", "Insérer par URL", "Uploader une image")) {
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }

    private fun assertPostInsertionsEnabled() {
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.onNodeWithContentDescription("Mise en forme").performScrollTo().performClick()
        for (label in listOf("Gras", "Lien", "Puce", "Citation", "E-mail")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Image").performScrollTo().performClick()
        for (label in listOf("Insérer par URL", "Uploader une image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        // The app bar also exposes « Retour »: target the action bar's sublevel return only.
        compose.onNode(hasContentDescription("Retour") and hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))
            .performScrollTo().performClick()
    }

}

/** The editable node sits under the floating-label headroom inside the 160 dp slot. */
private val FIELD_LABEL_HEADROOM = 8.dp

private const val RESTORE_OFFER = "Un brouillon non envoyé a été retrouvé pour ce message."

/** Same keyboard as EditorBaselineRoborazziTest's landscape post scenes. */
private val LANDSCAPE_KEYBOARD = 190.dp
