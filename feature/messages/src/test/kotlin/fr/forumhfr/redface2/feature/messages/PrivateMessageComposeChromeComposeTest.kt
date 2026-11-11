package fr.forumhfr.redface2.feature.messages

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.model.persoSmileyUrl
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrorSurface
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorEvent
import fr.forumhfr.redface2.core.ui.editor.EditorEventContent
import fr.forumhfr.redface2.core.ui.editor.EditorEventInput
import fr.forumhfr.redface2.core.ui.editor.EditorLocalOperation
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffoldChrome
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.EventKey
import fr.forumhfr.redface2.core.ui.editor.OperationRef
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerOpenIntent
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageComposeChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageComposeChromeFixture()
    private val uncertain = EditorErrors.uncertain(EditorErrorSurface.MessageCompose)
    private val scroll = ScrollState(0)
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var focus: FocusManager
    private lateinit var view: View
    private var renderedChrome: EditorScaffoldChrome? = null
    private var hides = 0
    private var shows = 0
    private var bodyInputStarts = 0
    private val bodyInputs = mutableSetOf<InputConnection>()
    private val input = PlatformTextInputInterceptor { request, next ->
        val info = EditorInfo()
        val connection = request.createInputConnection(info)
        // Header fields are single-line; only the multiline body must stay out of the IME on empty entry.
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            bodyInputStarts++
            bodyInputs += connection
        }
        try { next.startInputMethod(request) } finally {
            bodyInputs -= connection
            connection.closeConnection()
        }
    }
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }
    @Composable private fun Content(
        window: EditorWindowMetrics = EditorWindowMetrics(740.dp, 0.dp, 0.dp),
        modifier: Modifier = Modifier,
    ) {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        focus = LocalFocusManager.current
        view = LocalView.current
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        f.openSmileys = { smileys.open(SmileyPickerOpenIntent.CONSULTATION) }
        // The production chrome of this state, as the screen passes it to EditorScaffold.
        renderedChrome = f.adapter.chrome()
        CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
            InterceptPlatformTextInput(input) {
                RedfaceTheme {
                    PrivateMessageComposeContent(f.state, f.body, InputTransformation {}, f.recipients, f.subject,
                        f.actions, smileys, modifier, bodyScrollState = scroll, chromeEvents = f.events,
                        chromeMemory = f.memory, windowMetrics = window)
                }
            }
        }
    }
    private fun bodyField() = compose.onNode(
        hasSetTextAction() and hasAnyAncestor(hasTestTag("editor-scaffold-field")))
    private fun prefill() {
        f.state = f.state.copy(recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet privé")
    }
    /** Real IME insets, as the platform reports them for the focused body; never the scaffold's flag. */
    private fun showIme() {
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 200))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
    }
    @Test fun `ED113 empty entry opens chips and Next preserves buffer then returns selection and closed IME`() {
        val generation = mutableStateOf(0)
        compose.setContent { key(generation.value) { Content() } }
        val recipients = compose.onNode(hasSetTextAction() and hasText("Destinataires"))
        recipients.assertIsFocused().performTextInput("sans virgule")
        recipients.performImeAction()
        compose.onNode(hasSetTextAction() and hasText("Sujet")).assertIsFocused()
        compose.runOnIdle {
            assertFalse(f.adapter.focusMemory.imeBeforeSurface)
            assertEquals(0, bodyInputStarts)
            hides = 0
            shows = 0
        }
        compose.onNodeWithText("Retour au message").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals("sans virgule", f.state.recipientState.buffer)
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue(hides > 0)
            assertEquals(0, shows)
            assertEquals(0, bodyInputStarts)
            assertTrue(bodyInputs.isEmpty())
            assertFalse(f.adapter.consumeReturn())
            generation.value++
        }
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        // H restores the focused body after recreation, without replaying entry or starting the IME.
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), f.body.selection)
            assertEquals(0, shows)
            assertEquals(0, bodyInputStarts)
            assertTrue(bodyInputs.isEmpty())
            assertFalse(f.adapter.consumeReturn())
            focus.clearFocus(force = true)
        }
        bodyField().assertIsNotFocused()
        compose.runOnIdle { generation.value++ }
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        bodyField().assertIsNotFocused()
        compose.runOnIdle {
            assertEquals("sans virgule", f.state.recipientState.buffer)
            assertEquals(0, bodyInputStarts)
            assertTrue(bodyInputs.isEmpty())
        }
    }
    @Test fun `ED113 prefilled summary has no recipients status and completion focuses the cause field`() {
        prefill()
        compose.setContent { Content() }
        compose.onNodeWithText("Nouveau MP").assertExists()
        compose.onNodeWithText("1 destinataire · Sujet privé").assertExists()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertNull(requireNotNull(renderedChrome).secondStatus)
            assertNull(f.adapter.surface)
            assertTrue(bodyInputs.isNotEmpty())
        }
        // The completion gesture captures the IME the focused body actually shows (ED-86, real path).
        showIme()
        listOf(EditorErrors.unknownRecipient to "Destinataires", EditorErrors.missingSubject to "Sujet")
            .forEach { (error, label) ->
                compose.runOnIdle {
                    f.transition(f.state.copy(submitError = error))
                }
                val summary = error.summary.resolve(RuntimeEnvironment.getApplication())
                compose.onNodeWithContentDescription("$summary, Compléter").performClick()
                compose.onNode(hasSetTextAction() and hasText(label)).assertIsFocused()
                compose.runOnIdle { assertTrue(f.adapter.focusMemory.imeBeforeSurface); hides = 0 }
                compose.onNodeWithText("Retour au message").performClick()
                bodyField().assertIsFocused()
                compose.runOnIdle {
                    assertEquals(0, hides)
                    assertEquals(TextRange(6, 1), f.body.selection)
                    assertTrue(bodyInputs.isNotEmpty())
                }
            }
    }
    @Test fun `ED86 title block captures the open body IME and returns it with the reversed selection once`() {
        prefill()
        val headerAction = "Modifier les destinataires et le sujet : Nouveau MP, 1 destinataire · Sujet privé"
        compose.setContent { Content() }
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertTrue("Body input must be active before opening the header", bodyInputs.isNotEmpty())
            assertFalse(f.adapter.focusMemory.imeBeforeSurface)
            f.body.edit { selection = TextRange(6, 1) }
        }
        showIme()
        compose.onNodeWithContentDescription(headerAction).performClick()
        compose.onNodeWithText("Retour au message").assertExists()
        compose.runOnIdle {
            assertTrue("Opening from the title must capture the visible IME", f.adapter.focusMemory.imeBeforeSurface)
            assertEquals(PrivateMessageComposeChromeSurface.Header, f.adapter.surface)
            shows = 0
            hides = 0
        }
        compose.onNodeWithText("Retour au message").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue("Returning to the body must restart its input session", bodyInputs.isNotEmpty())
            assertEquals(0, hides)
            assertNull(f.adapter.surface)
            assertFalse(f.adapter.consumeReturn())
        }
    }
    @Test fun `ED40 restore discard and reload detail reach their ports and return body focus exactly once`() {
        prefill()
        f.state = f.state.copy(restorableDraft = "brouillon", restorableSubject = "titre",
            restorableRecipients = "bob")
        compose.setContent { Content() }
        listOf("Restaurer", "Ignorer").forEachIndexed { index, label ->
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            bodyField().assertDoesNotExist()
            compose.onNodeWithText(label).performClick()
            // The panel itself returns focus; Restore is not a returnsToBody surface, so no second return.
            bodyField().assertIsFocused()
            compose.runOnIdle {
                assertEquals(listOf("restore", "discard").take(index + 1), f.calls)
                assertNull(f.adapter.surface)
                assertEquals(0, f.adapter.returnGeneration)
                assertFalse(f.adapter.consumeReturn())
            }
        }
        compose.runOnIdle {
            f.transition(f.state.copy(submitError = EditorErrors.invalidForm))
        }
        val summary = EditorErrors.invalidForm.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        bodyField().assertDoesNotExist()
        compose.onNodeWithText("Recharger").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("restore", "discard", "reload"), f.calls)
            assertNull(f.adapter.surface)
            assertNull(f.adapter.detail)
            assertEquals(0, f.adapter.returnGeneration)
            assertFalse(f.adapter.consumeReturn())
        }
    }
    @Test fun `ED45 explicit Masquer in the MP detail forwards acknowledgement without sending`() {
        prefill()
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        compose.setContent { Content() }
        val summary = uncertain.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.runOnIdle { assertTrue(f.calls.isEmpty()) }
        compose.onNodeWithText("Masquer").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("acknowledge"), f.calls)
            assertNull(f.adapter.detail)
            assertNull(f.events.visibleEvent)
        }
    }
    @Test fun `ED45 explicit Masquer of the MP failed attempt write forwards only the local acknowledgement`() {
        prefill()
        f.transition(f.state.copy(submitError = EditorErrors.draftWriteFailed))
        compose.setContent { Content() }
        val summary = EditorErrors.draftWriteFailed.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.runOnIdle { assertTrue(f.calls.isEmpty()) }
        compose.onNodeWithText("Masquer").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("acknowledge local"), f.calls)
            assertNull(f.adapter.detail)
            assertNull(f.events.visibleEvent)
        }
    }
    @Test fun `ED45 ED85 system Back on the MP uncertainty detail and the header return never acknowledge`() {
        prefill()
        val attempt = AttemptToken("alice", f.session.key, "attempt", 1)
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(attempt), submitError = uncertain))
        compose.setContent { Content() }
        val summary = uncertain.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.onNodeWithText("Masquer").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Masquer").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(f.adapter.detail)
            assertNull(f.events.visibleEvent)
            f.adapter.openHeader()
        }
        compose.onNodeWithText("Retour au message").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun `ED45 Masquer of an uncertainty without a durable attempt only hides it`() {
        prefill()
        f.transition(f.state.copy(submission = EditorSubmissionState.Uncertain(null), submitError = uncertain))
        compose.setContent { Content() }
        val summary = uncertain.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.onNodeWithText("Masquer").performClick()
        bodyField().assertIsFocused()
        compose.runOnIdle {
            // No attempt is bound: Masquer stays visual and never reaches the durable port.
            assertTrue(f.calls.isEmpty())
            assertNull(f.adapter.detail)
            assertNull(f.events.visibleEvent)
        }
    }
    @Test fun `ED113 summary ignores blank chips and counts only nonblank recipients`() {
        f.state = f.state.copy(recipientState = EditorRecipientState(listOf("", " ", "\t")), subject = "Sujet privé")
        compose.setContent { RedfaceTheme { Text(f.adapter.context()) } }
        compose.onNodeWithText("Ajouter des destinataires · Sujet privé").assertExists()
        compose.runOnIdle {
            f.state = f.state.copy(recipientState = EditorRecipientState(listOf("", "alice", " ", "bob", "\t")))
        }
        compose.onNodeWithText("2 destinataires · Sujet privé").assertExists()
    }
    @Test fun `ED71 options have only three toggles and header inputs freeze during sending`() {
        prefill()
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("editor-options-sheet")))
            .assertCountEquals(3)
        compose.runOnIdle { f.adapter.closeSurface(); f.adapter.openHeader() }
        compose.runOnIdle { f.state = f.state.copy(isSubmitting = true) }
        compose.onNodeWithText("Destinataires").assertIsNotEnabled()
        compose.onNodeWithText("Sujet").assertIsNotEnabled()
    }
    @Test fun `ED70 smiley surface renders the common sheet`() {
        prefill()
        compose.setContent { Content() }
        compose.runOnIdle { f.adapter.barActions.onSmileys() }
        compose.onNodeWithTag("smiley_picker_sheet").assertExists()
    }
    @Test fun `information delays initial header without consuming its saved entry latch`() {
        val info = EditorEvent(EventKey(f.session, OperationRef.Local(EditorLocalOperation.Notice, 1), 0),
            EditorEventContent.Information(EditorErrors.imageLimit.summary), 1)
        f.events.dispatch(EditorEventInput.Received(info))
        compose.setContent { Content() }
        compose.onNodeWithTag("editor-scaffold-field").assertExists()
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(f.saved.keys().isEmpty())
            assertEquals(info, f.events.visibleEvent)
            f.events.dispatch(EditorEventInput.NextGesture(f.session))
        }
        compose.onNode(hasSetTextAction() and hasText("Destinataires")).assertIsFocused()
        compose.runOnIdle { assertEquals(true, f.saved.get<Boolean>(
            PrivateMessageComposeChromeMemory.INITIAL_HEADER_HANDLED)) }
    }

    @Test fun `loading and error retain the retry screen before the first automatic header`() {
        f.state = f.state.copy(isLoadingForm = true, formAvailable = false)
        compose.setContent { Content() }
        compose.onNodeWithText("Nouveau MP").assertExists()
        compose.onNodeWithTag("editor-scaffold-field").assertDoesNotExist()
        compose.runOnIdle { assertTrue(f.saved.keys().isEmpty()) }
        compose.runOnIdle { f.state = f.state.copy(isLoadingForm = false, formError = true) }
        compose.onNodeWithText("Impossible d'ouvrir le formulaire de nouveau message. Réessayez.").assertExists()
        compose.onNodeWithText("Réessayer").performClick()
        compose.runOnIdle {
            assertEquals(listOf("reload"), f.calls)
            f.state = f.state.copy(formError = false, formAvailable = true)
        }
        compose.onNode(hasSetTextAction() and hasText("Destinataires")).assertIsFocused()
        compose.runOnIdle { assertEquals(0, bodyInputStarts) }
        compose.runOnIdle { back.onBackPressed() }
        bodyField().assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("reload"), f.calls)
            assertEquals(0, bodyInputStarts)
            assertTrue(bodyInputs.isEmpty())
            assertFalse(f.adapter.consumeReturn())
        }
    }

    @Test fun `ED130 a full-screen form failure closes the header instead of restoring it after the retry`() {
        prefill()
        compose.setContent { Content() }
        compose.runOnIdle { f.adapter.openHeader() }
        compose.onNodeWithText("Retour au message").assertExists()
        compose.runOnIdle { f.state = f.state.copy(formError = true, formAvailable = false) }
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            f.state = f.state.copy(formError = false, formAvailable = true)
        }
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        bodyField().assertExists()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun `normal geometry reserves 48 dp header and 56 dp bar`() = checkGeometry(compact = false)
    @Test fun `compact geometry moves header action into the 48 dp bar`() = checkGeometry(compact = true)

    private fun checkGeometry(compact: Boolean) {
        prefill()
        val height = if (compact) 330.dp else 740.dp
        compose.setContent { Content(EditorWindowMetrics(height, 0.dp, 0.dp), Modifier.size(760.dp, height)) }
        compose.onNodeWithTag("editor-scaffold-action-bar").assertHeightIsEqualTo(if (compact) 48.dp else 56.dp)
        val appBar = compose.onNodeWithTag("editor-scaffold-app-bar")
        if (compact) {
            appBar.assertDoesNotExist()
            compose.onNodeWithContentDescription("Modifier les destinataires et le sujet").assertExists()
        } else appBar.assertHeightIsEqualTo(48.dp)
        val field = compose.onNodeWithTag("editor-scaffold-field").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("editor-scaffold-action-bar").getUnclippedBoundsInRoot()
        assertTrue(field.height >= 160.dp)
        assertTrue(field.bottom <= bar.top)
        compose.onNodeWithTag("editor-bar-submit").assertIsDisplayed()
    }

    @Test fun `activity Back closes detail before disarm then visible tools preview and awaited exit`() {
        prefill()
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Image").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, f.memory.host.bar.mode) }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Couleur").performScrollTo().performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(EditorBarMode.Format, f.memory.host.bar.mode)
            f.transition(f.state.copy(submitError = EditorErrors.antiFlood))
        }
        val summary = EditorErrors.antiFlood.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.runOnIdle {
            // The real event tap emits OpenSurface, as in dev (ED-23 pending).
            assertEquals(EditorBarMode.Normal, f.memory.host.bar.mode)
            f.state = f.state.copy(submission = EditorSubmissionState.Armed(ArmToken(1, f.session, 0, 4_000)))
        }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertNull(f.adapter.detail); assertTrue(f.calls.isEmpty()); back.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf("disarm"), f.calls)
            f.state = f.state.copy(submission = EditorSubmissionState.Ready)
            assertNull(f.events.visibleEvent)
        }
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(EditorBarMode.Normal, f.memory.host.bar.mode) }
        compose.runOnIdle { f.state = f.state.copy(isPreviewVisible = true) }
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertFalse(f.state.isPreviewVisible); back.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf("disarm", "close"), f.calls) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun `C7 header overlays the mounted body without changing its identity or height`() {
        prefill()
        f.body.edit { replace(0, length, "Bonjour privé"); selection = TextRange(6, 1) }
        compose.setContent { Content() }
        compose.runOnIdle { f.body.edit { selection = TextRange(6, 1) } }
        val field = compose.onNodeWithTag("editor-scaffold-field")
        val id = field.fetchSemanticsNode().id
        val inputId = bodyField().fetchSemanticsNode().id
        val bounds = field.getUnclippedBoundsInRoot()
        val viewport = compose.runOnIdle { scroll.viewportSize }
        compose.runOnIdle { f.adapter.openHeader() }
        compose.onNodeWithText("Retour au message").assertExists()
        compose.onNodeWithTag("editor-scaffold-field").assertDoesNotExist()
        compose.runOnIdle { assertTrue(viewport > 0); assertEquals(viewport, scroll.viewportSize) }
        compose.onNode(hasSetTextAction() and hasText("Destinataires")).performTextInput("bob,tail")
        compose.onNodeWithText("Retour au message").performClick()
        bodyField().assertIsFocused()
        assertEquals(id, field.fetchSemanticsNode().id)
        assertEquals(inputId, bodyField().fetchSemanticsNode().id)
        assertEquals(bounds, field.getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals("Bonjour privé", f.body.text.toString())
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue(f.body.undoState.canUndo)
            f.body.undoState.undo()
            assertEquals("Bonjour", f.body.text.toString())
        }
    }
    @OptIn(DelicateCoilApi::class)
    @Test fun `ED60 preview image requests disable disk reads and writes`() {
        val url = "https://example.com/private-preview.png"
        val smiley = requireNotNull(persoSmileyUrl("[:test private compose]"))
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val observed = CopyOnWriteArrayList<CachePolicy>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val data = chain.request.data
                if (data == url || data == smiley) {
                    observed += chain.request.diskCachePolicy
                    seen += data.toString()
                }
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(width = 320, height = 240))
            .intercept(smiley, ColorImage(width = 20, height = 20)).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .components { add(recorder); add(engine) }.build())
        try {
            prefill()
            f.state = f.state.copy(isPreviewVisible = true, preview = PostContent(listOf(PostBlock.Image(url, null),
                PostBlock.Paragraph(listOf(PostInline.Smiley(SmileyKind.Perso("test private compose"), smiley))))))
            compose.setContent { Content() }
            compose.waitUntil(timeoutMillis = 5_000) { seen.size == 2 }
            assertTrue(observed.all { it == CachePolicy.DISABLED })
        } finally { SingletonImageLoader.reset() }
    }
}
