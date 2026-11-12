package fr.forumhfr.redface2.feature.editor

import android.app.Activity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EDITOR_DRAFT_MIN_HEIGHT
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicFormScreenLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = TopicFormChromeFixture()
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var inputMode: InputModeManager
    private lateinit var previewLabel: String

    @Composable private fun Content() {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        inputMode = LocalInputModeManager.current
        previewLabel = stringResource(UiR.string.editor_preview_label)
        RedfaceTheme(darkTheme = false, dynamicColor = false) {
            TopicFormTestContent(fixture.state, fixture::dispatch, fixture.body, fixture.events, fixture.memory)
        }
    }

    @Test fun normalGeometryAndPanelsKeepTheLiveBody() = checkGeometry(horizontalPreview = false)
    @Test @Config(qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
    fun compactGeometryAndPanelsKeepTheLiveBody() = checkGeometry(horizontalPreview = true)

    private fun checkGeometry(horizontalPreview: Boolean) {
        compose.setContent { Content() }
        val field = compose.onNode(hasSetTextAction())
        field.assertHeightIsAtLeast(EDITOR_DRAFT_MIN_HEIGHT - 8.dp)
        val id = field.fetchSemanticsNode().id
        val bounds = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        field.performClick()
        repeat(50) { field.performTextInput("a") }
        assertEquals(bounds, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle { fixture.adapter.openHeader() }
        compose.onNodeWithText("Titre du sujet").assertIsFocused()
        compose.onNodeWithText("Retour au message").performClick()
        field.assertIsFocused()
        compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed().performClick()
        compose.onNodeWithTag("topic-form-preview").assertExists()
        // ED-60/62: both windows have room for a split; the live source stays alongside the preview.
        field.assertIsDisplayed()
        assertEquals(id, field.fetchSemanticsNode().id)
        compose.onNodeWithTag("editor-preview-divider-target").assertIsDisplayed()
        val source = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("editor-preview-rendered").getUnclippedBoundsInRoot()
        if (horizontalPreview) {
            assertEquals(bounds.height, source.height)
            assertTrue(source.width < bounds.width)
            assertTrue(preview.left > source.right)
        } else {
            assertEquals(bounds.width, source.width)
            assertTrue(source.height < bounds.height)
            assertTrue(preview.top > source.bottom)
        }
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(id, field.fetchSemanticsNode().id)
        assertEquals(bounds, compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot())
        assertEquals(send, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertTrue(fixture.body.text.length >= 50) }
    }

    @Test fun normalRestoreActionsRespectLoadingUploadAndUncertainty() = checkRestoreActions()
    @Test @Config(qualifiers = "fr-rFR-w360dp-h330dp-xxhdpi")
    fun compactRestoreActionsRespectLoadingUploadAndUncertainty() = checkRestoreActions()

    private fun checkRestoreActions() {
        fixture.state = fixture.state.copy(restorableDraft = "Brouillon", isLoadingForm = true)
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val restore = compose.onNodeWithText("Restaurer").assertHeightIsAtLeast(48.dp)
        val discard = compose.onNodeWithText("Ignorer").assertHeightIsAtLeast(48.dp)
        for (action in listOf(restore, discard)) {
            val bounds = action.getUnclippedBoundsInRoot()
            assertEquals(bounds, action.getBoundsInRoot())
            val window = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= window.left)
            assertTrue(bounds.top >= window.top)
            assertTrue(bounds.right <= window.right)
            assertTrue(bounds.bottom <= window.bottom)
        }
        assertTrue(discard.getUnclippedBoundsInRoot().left - restore.getUnclippedBoundsInRoot().right >= 8.dp)
        restore.assertIsNotEnabled(); discard.assertIsEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(isLoadingForm = false) }
        restore.assertIsEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(isUploading = true) }
        restore.assertIsNotEnabled(); discard.assertIsEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(isUploading = false,
            submission = EditorSubmissionState.Uncertain(null, accepted = true)) }
        restore.assertIsNotEnabled()
    }

    @Test fun firstPostUnknownBodyKeepsReloadAndLocksOnlyInsertionsUntilHydration() {
        val state = mutableStateOf(fixture.state.copy(mode = TopicFormMode.EditFirstPost,
            draftHydratedFromServer = false, isLoadingForm = true))
        val intents = mutableListOf<TopicFormIntent>()
        compose.setContent {
            inputMode = LocalInputModeManager.current
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                TopicFormTestContent(state.value, { intents += it }, fixture.body)
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        assertInsertionsLocked()
        compose.onNodeWithContentDescription("Options").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isLoadingForm = false,
            submitError = EditorErrors.formNetwork) }
        assertInsertionsLocked(event = true)
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        assertInsertionsLocked()
        compose.runOnIdle { state.value = state.value.copy(submitError = EditorErrors.uncertain) }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(List(2) { TopicFormIntent.ReloadRequested }, intents)
            assertEquals("Bonjour", fixture.body.text.toString())
            // Mirror the VM's server hydration: replace the buffer and park the caret at its end.
            // The shared fixture starts with a reversed selection for the surface-return tests.
            fixture.body.edit {
                replace(0, length, "Bonjour")
                selection = TextRange(length)
            }
            state.value = state.value.copy(draftHydratedFromServer = true, submitError = null, isLoadingForm = true)
        }
        compose.runOnIdle { assertEquals(TextRange("Bonjour".length), fixture.body.selection) }
        compose.onNode(hasSetTextAction()).performTextInput(" typed")
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.runOnIdle { assertEquals("Bonjour typed", fixture.body.text.toString()) }
        compose.runOnIdle { state.value = state.value.copy(mode = TopicFormMode.New, draftHydratedFromServer = false) }
        compose.onNode(hasSetTextAction()).performTextInput(" new")
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        for (label in listOf("Gras", "Citation")) compose.onNodeWithContentDescription(label).assertIsEnabled()
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Image").performScrollTo().performClick()
        for (label in listOf("Insérer par URL", "Uploader une image")) {
            compose.onNodeWithContentDescription(label).assertIsEnabled()
        }
    }

    private fun assertInsertionsLocked(event: Boolean = false) {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        for (label in listOf("Mise en forme", "Smileys", "Image")) {
            val control = compose.onNodeWithContentDescription(label)
            if (event) control.assertDoesNotExist() else control.assertIsNotEnabled()
        }
    }

    @Test fun backClosesTooltipThenColorFormatHeaderPreviewAndFinallySaves() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Mise en forme").performTouchInput { longClick() }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Gras").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Options").assertExists()
        compose.runOnIdle { fixture.adapter.openHeader() }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithContentDescription(previewLabel).performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("topic-form-preview").assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        assertEquals(listOf(TopicFormIntent.TogglePreview, TopicFormIntent.TogglePreview,
            TopicFormIntent.CloseRequested), fixture.calls)
    }

    @Test fun keyboardBackClosesTooltipThenRestoresTheParentTrigger() {
        compose.setContent { Content() }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        for (label in listOf("Mise en forme", "Couleur")) {
            barControl(label).performScrollTo().requestFocus().performKeyInput { pressKey(Key.Enter) }
        }
        barControl("Retour").assertIsFocused()
        compose.runOnIdle { back.onBackPressed() }
        barControl("Retour").assertIsFocused() // Tooltip consumed the first Back, level stays open.
        compose.runOnIdle { back.onBackPressed() }
        barControl("Couleur").assertIsFocused()
        barControl("Mise en forme").requestFocus()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { back.onBackPressed() }
        barControl("Mise en forme").assertIsFocused()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    private fun barControl(label: String) = compose.onNode(hasContentDescription(label) and
        hasAnyAncestor(hasTestTag("editor-scaffold-action-bar")))

    @Test fun eventTapKeepsDevOpenSurfaceAndBackSkipsMaskedModeBeforeLeaving() {
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle {
            fixture.state = fixture.state.copy(submission = EditorSubmissionState.Armed(
                ArmToken(1, fixture.session, Long.MAX_VALUE / 4, 60_000L)))
            fixture.error(EditorErrors.network)
        }
        compose.onNodeWithTag("editor-bar-event").performClick()
        compose.runOnIdle { assertEquals(null, fixture.state.armToken) }
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithContentDescription("Options").assertExists() // OpenSurface returned Format to Normal.
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle { fixture.error(EditorErrors.noImageReceived) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertTrue(fixture.events.visibleEvent != null)
            assertEquals(listOf(TopicFormIntent.SubmitConfirmationDismissed, TopicFormIntent.CloseRequested),
                fixture.calls)
        }
    }

    // Robolectric #8460 (https://github.com/robolectric/robolectric/issues/8460): a platform-width Dialog holding a
    // text field never idles above the default 320 dp. Box probe of 2026-11-10: the production ImageUrlDialog
    // alone, without any editor chrome, also freezes at 360 and 411 dp (its window lays out again on every frame)
    // and idles at 320 dp, as does a text field Dialog without the platform default width at 360 dp; the chrome
    // is inert here. Geometry is covered separately at 360 dp.
    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h640dp-xxhdpi")
    fun optionsAndImageUrlReturnTheSameSourceSelectionAndSmileysOpenTheirSheet() {
        compose.setContent { Content() }
        val source = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        val selection = fixture.body.selection
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Ton du message").assertExists()
        compose.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}16").assertExists()
        optionRow("Activer votre signature").assertIsDisplayed().assertIsEnabled().assertIsOff().performClick()
        compose.runOnIdle { fixture.adapter.host.closeSurface() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithContentDescription("Image").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Insérer par URL").performScrollTo().performClick()
        compose.onNodeWithText("URL de l’image").assertExists()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(source, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, fixture.body.selection)
            assertEquals(listOf(TopicFormIntent.ToggleSignature(true)), fixture.calls)
        }
        compose.onNodeWithContentDescription("Smileys").performScrollTo().performClick()
        compose.onNodeWithTag("smiley_picker_sheet").assertExists()
        compose.onNodeWithText("Standard").assertExists()
    }

    // EditorOptions makes each option row one toggleable node named by its label (R1b A1).
    private fun optionRow(label: String) = compose.onNode(isToggleable() and hasText(label))

    @Test fun pickerCancellationRestoresClosedImeAndSelection() = checkPickerCancellation(imeOpen = false)
    @Test fun pickerCancellationRestoresOpenImeAndSelection() = checkPickerCancellation(imeOpen = true)

    private fun checkPickerCancellation(imeOpen: Boolean) {
        var pendingCode: Int? = null
        lateinit var focusManager: androidx.compose.ui.focus.FocusManager
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                pendingCode = requestCode
                focusManager.clearFocus(force = true) // Model the external activity taking focus.
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var inputActive = false
        var sessions = 0
        val interceptor = PlatformTextInputInterceptor { request, next ->
            sessions++; inputActive = true
            try { next.startInputMethod(request) } finally { inputActive = false }
        }
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            focusManager = LocalFocusManager.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                InterceptPlatformTextInput(interceptor) { Content() }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val text = fixture.body.text.toString()
        val selection = fixture.body.selection
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), if (imeOpen) Insets.of(0, 0, 0, 200) else Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), imeOpen).build())
        }
        compose.onNodeWithContentDescription("Image").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Uploader une image").performScrollTo().performClick()
        val before = compose.runOnIdle {
            assertEquals(imeOpen, fixture.memory.host.focus.imeBeforeSurface)
            sessions.also { registry.dispatchResult(requireNotNull(pendingCode), Activity.RESULT_CANCELED, null) }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(text, fixture.body.text.toString())
            assertEquals(selection, fixture.body.selection)
            assertEquals(imeOpen, inputActive)
            assertEquals(before + if (imeOpen) 1 else 0, sessions)
            assertEquals(null, fixture.memory.host.surface)
            assertTrue(fixture.calls.filterIsInstance<TopicFormIntent.ImagePickerEventReceived>()
                .any { it.event is ImagePickerEvent.Cancelled })
            assertFalse(fixture.calls.any { it is TopicFormIntent.ImagesPicked })
        }
    }

}
