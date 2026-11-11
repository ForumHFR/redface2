package fr.forumhfr.redface2.feature.editor

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.BbcodePreviewParser
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.EditorShortcut
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** ED-81: exercise the screen observer, Android state registry and measured BTF2 viewport together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class PostEditorRestorationScreenTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(dispatcher)
    private val store = VersionedEditorDraftStore()
    private val replies = mockk<ReplyRepository>()
    private val edits = mockk<EditPostRepository>(relaxed = true)
    private val previewParser = mockk<BbcodePreviewParser> {
        every { parsePreview(any()) } returns PostContent(emptyList())
    }
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val editors = mutableListOf<PostEditorViewModel>()
    private val request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0)
    private lateinit var focusManager: FocusManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { replies.fetchReplyForm(any()) } returns ReplyForm("hash", "topic", emptyMap(), false, "")
    }

    @After
    fun tearDown() {
        PostEditorRotationHostActivity.render = {}
        hosts.forEach { it.close() }
        editors.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun stopAndSavedStateRestoreViewportOnlyAfterOwnerReadAndFieldMeasurement() = checkViewportRestoration(false)

    @Test
    fun savedScrollBeyondShorterContentClampsToMeasuredRange() = checkViewportRestoration(true)

    @Test fun replyHydrationKeepsScrollUntilProgrammaticInsertion() = checkHydration(PostEditorMode.Reply)

    @Test fun editHydrationKeepsScrollUntilProgrammaticInsertion() = checkHydration(PostEditorMode.Edit)

    @Test fun editReloadHydrationKeepsScrollUntilProgrammaticInsertion() =
        checkHydration(PostEditorMode.Edit, reload = true)

    @Test
    fun offeredDraftRestorationKeepsScrollUntilProgrammaticInsertion() {
        val route = request.copy(mode = PostEditorMode.Edit, numreponse = 101)
        val body = "Restored editor line\n".repeat(200)
        store.preload(EditorDraftKey.editPost(23, 101), EditorDraftStore.Draft(body = body))
        coEvery { edits.fetchEditPostForm(any()) } returns
            ReplyForm("hash", "topic", emptyMap(), false, "Server editor line\n".repeat(100))
        val owner = host().get()
        val model = editor(owner, route)
        showEditor(owner, model, route)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
        compose.runOnIdle {
            model.bodyScrollState.dispatchRawDelta(80f - model.bodyScrollState.value)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(80, model.bodyScrollState.value)
            assertEquals(body, model.state.value.restorableDraft)
            model.submit(PostEditorIntent.DraftRestoreRequested)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(body, model.bodyFieldState.text.toString())
            assertEquals(TextRange(body.length), model.bodyFieldState.selection)
            assertEquals(80, model.bodyScrollState.value)
        }
        assertCaretVisible(model, expected = false)
        insertAndAssertCaretVisible(model)
    }

    @Test
    fun closedKeyboardStaysClosedAfterOfferSheetRecreationAndBackReturnsFocusOnce() {
        store.preload(EditorDraftKey.reply(23, 35421), EditorDraftStore.Draft(body = "Brouillon"))
        var owner by mutableStateOf(host().get())
        val model = editor(owner)
        val keyboard = mockk<SoftwareKeyboardController>(relaxed = true)
        var sessions = 0
        var inputActive = false
        val input = PlatformTextInputInterceptor { request, next ->
            sessions++
            inputActive = true
            try { next.startInputMethod(request) } finally { inputActive = false }
        }
        lateinit var view: android.view.View
        compose.setContent {
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboard) {
                    view = LocalView.current
                    InterceptPlatformTextInput(input) {
                        RedfaceTheme(darkTheme = false, dynamicColor = false) {
                            PostEditorScreen(request, { _, _, _, _ -> }, viewModel = model)
                        }
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertTrue(sessions > 0)
            // IME visibility is the real surface snapshot, independent of an active input connection.
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false).build())
        }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        assertPostCover(PostEditorChromeSurface.Restore)
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        val before = compose.runOnIdle {
            assertEquals(PostEditorChromeSurface.Restore, model.chromeMemory.surface)
            assertEquals(0, model.chromeMemory.returnGeneration)
            assertFalse(model.chromeMemory.focus.imeBeforeSurface)
            assertFalse(inputActive)
            sessions.also { owner = host().get() }
        }
        assertPostCover(PostEditorChromeSurface.Restore)
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(PostEditorChromeSurface.Restore, model.chromeMemory.surface)
            assertEquals(0, model.chromeMemory.returnGeneration)
            assertFalse(model.chromeMemory.focus.imeBeforeSurface)
            assertEquals(before, sessions)
            assertFalse(inputActive)
            sheetBack()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("editor-draft-offer-sheet").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertNull(model.chromeMemory.surface)
            assertEquals(1, model.chromeMemory.returnGeneration)
            assertEquals(before, sessions)
            assertFalse(inputActive)
        }
        // A later frame must not repeat the deferred return or reopen the keyboard.
        compose.mainClock.advanceTimeBy(600L)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(before, sessions); assertFalse(inputActive) }
        verify(exactly = 1) { keyboard.hide() }
        verify(exactly = 0) { keyboard.show() }
    }

    @Test fun ownerRecreationKeepsFocusedReversedBodySelection() = checkFocusedBodyRecreation(imeOpen = false)
    @Test fun ownerRecreationKeepsFocusedReversedBodySelectionWithOpenKeyboard() =
        checkFocusedBodyRecreation(imeOpen = true)

    private fun checkFocusedBodyRecreation(imeOpen: Boolean) {
        var sessions = 0
        prepareRotationHost { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var original: PostEditorRotationHostActivity
            scenario.onActivity {
                original = it
                assertEquals(InputMode.Touch, it.inputMode.inputMode)
            }
            val model = original.model
            val body = "Bonjour le forum"
            val selection = TextRange(6, 1)
            compose.runOnIdle {
                model.bodyFieldState.edit {
                    replace(0, length, body)
                    this.selection = selection
                }
                // Visibility is captured independently of the input session. Keep the body focused.
                original.setImeVisible(imeOpen)
            }
            val before = compose.runOnIdle {
                assertTrue(model.chromeMemory.focus.autoFocusConsumed)
                assertTrue(sessions > 0)
                sessions
            }
            repeat(2) { recreation ->
                scenario.recreate()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                scenario.onActivity {
                    assertNotSame(original, it)
                    assertTrue(original.isChangingConfigurations)
                    assertSame(model, it.model)
                    assertEquals(InputMode.Touch, it.inputMode.inputMode)
                    original = it
                }
                compose.runOnIdle {
                    assertEquals(body, model.bodyFieldState.text.toString())
                    assertEquals(selection, model.bodyFieldState.selection)
                    assertEquals(before + if (imeOpen) recreation + 1 else 0, sessions)
                }
                assertCaretVisible(model, expected = true)
                // Robolectric has no real IME: seed visibility for the NEXT pause, after the assertions.
                scenario.onActivity { it.setImeVisible(imeOpen) }
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun sharedPreviewBodyAcceptsFreshTouchFocusAndEdits() {
        prepareRotationHost()
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            openSharedPreview()
            scenario.onActivity { it.focusManager.clearFocus(force = true) }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
                .performTouchInput { click() }.assertIsFocused().performTextInput("Éditable")
            scenario.onActivity { assertEquals("Éditable", it.model.bodyFieldState.text.toString()) }
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        }
    }

    @Test fun sharedPreviewRecreationRestoresFocusAndReversedSelectionWithClosedKeyboard() =
        checkSharedPreviewRecreation(imeOpen = false)

    @Test fun sharedPreviewRecreationRestoresFocusAndReversedSelectionWithOpenKeyboard() =
        checkSharedPreviewRecreation(imeOpen = true)

    private fun checkSharedPreviewRecreation(imeOpen: Boolean) {
        var sessions = 0
        prepareRotationHost { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            openSharedPreview()
            // Keep the focus acquired before opening: even the broken guard lets it remain here.
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var original: PostEditorRotationHostActivity
            scenario.onActivity { original = it }
            val model = original.model
            val body = "Bonjour le forum"
            val selection = TextRange(6, 1)
            compose.runOnIdle {
                model.bodyFieldState.edit { replace(0, length, body); this.selection = selection }
                original.setImeVisible(imeOpen)
            }
            val before = compose.runOnIdle { assertTrue(sessions > 0); sessions }
            repeat(2) { recreation ->
                scenario.recreate()
                // No tap or RequestFocus after recreate: the new, initially unmeasured preview
                // must resolve its bounds before the one-shot restoration (H).
                compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                scenario.onActivity {
                    assertNotSame(original, it)
                    assertTrue(original.isChangingConfigurations)
                    assertSame(model, it.model)
                    assertEquals(InputMode.Touch, it.inputMode.inputMode)
                    original = it
                }
                compose.runOnIdle {
                    assertTrue(model.state.value.isPreviewVisible)
                    assertEquals(body, model.bodyFieldState.text.toString())
                    assertEquals(selection, model.bodyFieldState.selection)
                    assertEquals(before + if (imeOpen) recreation + 1 else 0, sessions)
                }
                scenario.onActivity { it.setImeVisible(imeOpen) }
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun coveringPreviewRejectsFreshAndRestoredBodyFocusWhileTabReachesItsSpoiler() {
        var height by mutableStateOf(640.dp)
        var sessions = 0
        every { previewParser.parsePreview(any()) } returns PostContent(listOf(
            PostBlock.Spoiler("Secret", PostContent(listOf(
                PostBlock.Paragraph(listOf(PostInline.Text("Contenu révélé"))),
            ))),
        ))
        prepareRotationHost(editorModifier = { Modifier.height(height) }) { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            openSharedPreview()
            scenario.onActivity { it.focusManager.clearFocus(force = true) }
            // Establish actual focus in the shared source before it becomes covered. This also
            // makes this regression test fail with the old host guard, before testing the refusal.
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
                .performTouchInput { click() }.assertIsFocused()
            lateinit var original: PostEditorRotationHostActivity
            scenario.onActivity { original = it; it.setImeVisible(false) }
            val model = original.model
            val selection = TextRange(6, 1)
            compose.runOnIdle {
                model.bodyFieldState.edit { replace(0, length, "Bonjour le forum"); this.selection = selection }
                height = 360.dp
            }
            compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
            // ED-62/85: the covered body releases focus immediately; ED-86 must not restore it.
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.runOnIdle { assertEquals(selection, model.bodyFieldState.selection) }
            val before = compose.runOnIdle { sessions }
            scenario.recreate()
            compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.onAllNodes(isFocused(), useUnmergedTree = true).assertCountEquals(0)
            // A covering preview stays unfocusable throughout H's bounded wait, then expires.
            compose.mainClock.advanceTimeBy(600L)
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            scenario.onActivity {
                assertNotSame(original, it)
                assertTrue(original.isChangingConfigurations)
                assertSame(model, it.model)
                assertTrue(it.inputMode.requestInputMode(InputMode.Keyboard))
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val toggle = compose.onNodeWithContentDescription(context.getString(UiR.string.editor_preview_label))
                .assertIsOn()
            toggle.requestFocus().assertIsFocused()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.RequestFocus) { assertFalse(it()) }.assertIsNotFocused()
            toggle.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
            val spoiler = compose.onNodeWithText("Secret")
            spoiler.assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
            compose.onNodeWithText("Contenu révélé").assertIsDisplayed()
            compose.runOnIdle { height = 640.dp }
            // More space with the preview still open must not replay the refused restoration.
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            spoiler.assertIsFocused()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle {
                assertTrue(model.state.value.isPreviewVisible)
                assertEquals(selection, model.bodyFieldState.selection)
                assertEquals(before, sessions)
            }
        }
    }

    @Test fun zeroHeightPreviewConsumesRestorationBeforeSpaceReturnsWithClosedKeyboard() =
        checkZeroHeightPreviewRecreation(imeOpen = false)

    @Test fun zeroHeightPreviewConsumesRestorationBeforeSpaceReturnsWithOpenKeyboard() =
        checkZeroHeightPreviewRecreation(imeOpen = true)

    private fun checkZeroHeightPreviewRecreation(imeOpen: Boolean) {
        var height by mutableStateOf(640.dp)
        var sessions = 0
        prepareRotationHost(editorModifier = { Modifier.height(height) }) { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            openSharedPreview()
            lateinit var original: PostEditorRotationHostActivity
            scenario.onActivity { original = it; it.setImeVisible(imeOpen) }
            val model = original.model
            val body = "Bonjour le forum"
            val selection = TextRange(6, 1)
            compose.runOnIdle {
                model.bodyFieldState.edit { replace(0, length, body); this.selection = selection }
                height = 80.dp // The chrome absorbs the whole field area, even without an IME.
            }
            assertEquals(0f, compose.onNodeWithTag("editor-scaffold-field", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().height.value, 0.5f)
            // A zero-height source is covered too: no retained focus or keystrokes before recreation.
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.runOnIdle { assertEquals(selection, model.bodyFieldState.selection) }
            val before = compose.runOnIdle { sessions }

            scenario.recreate()
            compose.onNodeWithTag("editor-preview-fallback").assertExists()
            assertEquals(0f, compose.onNodeWithTag("editor-scaffold-field", useUnmergedTree = true)
                .getUnclippedBoundsInRoot().height.value, 0.5f)
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            // Consume H at its deadline before space returns, without a cancelling key or pointer.
            compose.mainClock.advanceTimeBy(600L)
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            scenario.onActivity {
                assertNotSame(original, it)
                assertTrue(original.isChangingConfigurations)
                assertSame(model, it.model)
                assertEquals(InputMode.Touch, it.inputMode.inputMode)
                assertTrue(it.inputMode.requestInputMode(InputMode.Keyboard))
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val toggle = compose.onNodeWithContentDescription(context.getString(UiR.string.editor_preview_label))
                .assertIsOn()
            toggle.requestFocus().assertIsFocused()
            compose.runOnIdle { height = 640.dp }
            // An unbounded readiness wait would now complete and steal this focus.
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            toggle.assertIsFocused()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle {
                assertTrue(model.state.value.isPreviewVisible)
                assertEquals(body, model.bodyFieldState.text.toString())
                assertEquals(selection, model.bodyFieldState.selection)
                assertEquals(before, sessions)
            }
        }
    }

    private fun openSharedPreview() {
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(UiR.string.editor_preview_label)).performClick()
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
    }

    @Test fun rotationKeepsPostRestoreSheetOverTheBody() = checkCoveredPost(PostEditorChromeSurface.Restore)
    @Test fun rotationKeepsPostOptionsOverTheBody() = checkCoveredPost(PostEditorChromeSurface.Options)
    @Test fun rotationKeepsPostPreviewOverTheBody() = checkCoveredPost(null)

    @Test fun quotePanelRecreationAndReturnKeepOpenKeyboard() = checkQuotePanelRecreation(imeOpen = true)
    @Test fun quotePanelRecreationAndReturnKeepClosedKeyboard() = checkQuotePanelRecreation(imeOpen = false)

    private fun checkQuotePanelRecreation(imeOpen: Boolean) {
        var sessions = 0
        val route = request.copy(initialQuotes = listOf(
            QuoteSelection(QuoteLocator(1, 101, null), "Alice", "Extrait cité"),
        ))
        prepareRotationHost(route) { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var model: PostEditorViewModel
            val selection = TextRange(6, 1)
            scenario.onActivity {
                model = it.model
                model.bodyFieldState.edit { replace(0, length, "Bonjour"); this.selection = selection }
                it.setImeVisible(imeOpen)
            }
            compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
            val before = compose.runOnIdle { sessions }
            scenario.recreate()
            compose.onNodeWithText("Retour au message").assertIsDisplayed()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            compose.runOnIdle {
                assertEquals(PostEditorChromeSurface.Quotes, model.chromeMemory.surface)
                assertEquals(selection, model.bodyFieldState.selection)
                assertEquals(before, sessions)
            }
            compose.onNodeWithText("Retour au message").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(selection, model.bodyFieldState.selection)
                assertEquals(before + if (imeOpen) 1 else 0, sessions)
            }
            // H still restores the focused body after returning from the quote surface.
            scenario.onActivity { it.setImeVisible(imeOpen) }
            compose.waitForIdle()
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(selection, model.bodyFieldState.selection)
                assertEquals(before + if (imeOpen) 2 else 0, sessions)
                assertEquals("Bonjour", model.bodyFieldState.text.toString())
            }
        }
    }

    private fun checkCoveredPost(surface: PostEditorChromeSurface?) {
        store.preload(EditorDraftKey.reply(23, 35421), EditorDraftStore.Draft(body = "Brouillon"))
        var sessions = 0
        // A preview only covers the body when ED-62 cannot fit both panes.
        prepareRotationHost(editorModifier = {
            if (surface == null) Modifier.height(360.dp) else Modifier
        }) { sessions++ }
        ActivityScenario.launch(PostEditorRotationHostActivity::class.java).use { scenario ->
            compose.onNode(hasSetTextAction()).assertIsFocused()
            lateinit var model: PostEditorViewModel
            scenario.onActivity { model = it.model; it.setImeVisible(false) }
            val action = when (surface) {
                PostEditorChromeSurface.Restore -> "Brouillon retrouvé, restaurer ou ignorer"
                PostEditorChromeSurface.Options -> "Options"
                else -> ApplicationProvider.getApplicationContext<Context>().getString(UiR.string.editor_preview_label)
            }
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitForIdle()
            assertPostCover(surface)
            val before = compose.runOnIdle { sessions }
            scenario.recreate()
            compose.waitForIdle()
            scenario.onActivity { assertSame(model, it.model) }
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            if (surface == null) {
                compose.mainClock.advanceTimeBy(600L)
                compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
            }
            assertPostCover(surface)
            compose.runOnIdle {
                assertEquals(surface, model.chromeMemory.surface)
                assertEquals(surface == null, model.state.value.isPreviewVisible)
                assertEquals(before, sessions)
            }
            if (surface == PostEditorChromeSurface.Restore) {
                compose.runOnIdle {
                    assertFalse(model.chromeMemory.focus.imeBeforeSurface)
                    assertEquals(0, model.chromeMemory.returnGeneration)
                    sheetBack()
                }
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNodeWithTag("editor-draft-offer-sheet").assertDoesNotExist()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                compose.mainClock.advanceTimeBy(600L)
                compose.runOnIdle {
                    assertNull(model.chromeMemory.surface)
                    assertEquals(1, model.chromeMemory.returnGeneration)
                    assertEquals(before, sessions)
                }
            }
        }
    }

    private fun assertPostCover(surface: PostEditorChromeSurface?) {
        when (surface) {
            PostEditorChromeSurface.Restore -> {
                compose.onNode(isDialog()).assertExists()
                compose.onNodeWithTag("editor-draft-offer-sheet").assertIsDisplayed()
                compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
                compose.onNodeWithText("Retour au message").assertDoesNotExist()
            }
            PostEditorChromeSurface.Options -> compose.onNodeWithText("Options du message").assertIsDisplayed()
            else -> compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
        }
    }

    private fun sheetBack() {
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
    }

    private fun prepareRotationHost(
        route: PostEditorRequest = request,
        editorModifier: () -> Modifier = { Modifier },
        onInput: () -> Unit = {},
    ) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, PostEditorRotationHostActivity::class.java))
        val input = PlatformTextInputInterceptor { request, next ->
            onInput()
            next.startInputMethod(request)
        }
        PostEditorRotationHostActivity.render = { activity ->
            val model = editor(activity, route)
            activity.model = model
            activity.setContent {
                activity.view = LocalView.current
                activity.inputMode = LocalInputModeManager.current
                activity.focusManager = LocalFocusManager.current
                InterceptPlatformTextInput(input) {
                    RedfaceTheme(darkTheme = false, dynamicColor = false) {
                        PostEditorScreen(route, { _, _, _, _ -> }, modifier = editorModifier(), viewModel = model)
                    }
                }
            }
        }
    }

    @Test fun replyContextComesFromLoadedForm() = checkFormContext(PostEditorMode.Reply, "Sujet de réponse")
    @Test fun editContextComesFromLoadedForm() = checkFormContext(PostEditorMode.Edit, "Sujet modifié")
    @Test fun editContextStaysEmptyWhenFormOmitsSubject() = checkFormContext(PostEditorMode.Edit, "")

    private fun checkFormContext(mode: PostEditorMode, subject: String) {
        val form = CompletableDeferred<ReplyForm>()
        coEvery { replies.fetchReplyForm(any()) } coAnswers { form.await() }
        coEvery { edits.fetchEditPostForm(any()) } coAnswers { form.await() }
        val route = request.copy(mode = mode, numreponse = if (mode == PostEditorMode.Edit) 101 else null)
        val owner = host().get()
        val model = editor(owner, route)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    PostEditorScreen(route, { _, _, _, _ -> }, viewModel = model)
                }
            }
        }
        compose.onNodeWithTag("editor-scaffold-app-bar").assertIsDisplayed()
        compose.onNodeWithTag("editor-app-bar-line-two").assertDoesNotExist()
        compose.runOnIdle { form.complete(ReplyForm("hash", subject, emptyMap(), false, "")) }
        if (subject.isEmpty()) compose.onNodeWithTag("editor-app-bar-line-two").assertDoesNotExist()
        else compose.onNodeWithTag("editor-app-bar-line-two").assertTextEquals(subject)
    }

    private fun checkHydration(mode: PostEditorMode, reload: Boolean = false) {
        val form = CompletableDeferred<ReplyForm>()
        var fail = reload
        coEvery { replies.fetchReplyForm(any()) } coAnswers { form.await() }
        coEvery { edits.fetchEditPostForm(any()) } coAnswers {
            if (fail) throw IOException("offline")
            form.await()
        }
        val route = request.copy(mode = mode, numreponse = if (mode == PostEditorMode.Edit) 101 else null)
        val owner = host().get()
        val model = editor(owner, route)
        showEditor(owner, model, route)
        // An edit awaiting its GET is read-only and therefore exposes no SetText semantics yet.
        compose.onNodeWithTag("editor-scaffold-field").assertIsDisplayed()
        // Exercise the shared reveal fallback while the user has left the field, rather than BTF2 focus tracking.
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.waitForIdle()
        val body = "Hydrated editor line\n".repeat(200)
        compose.runOnIdle {
            if (reload) {
                assertFalse(model.state.value.isLoadingForm)
                fail = false
                model.submit(PostEditorIntent.ReloadRequested)
            }
            form.complete(ReplyForm("hash", "topic", emptyMap(), false, body))
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(body, model.bodyFieldState.text.toString())
            assertEquals(TextRange(body.length), model.bodyFieldState.selection)
            assertTrue(model.bodyScrollState.maxValue > model.bodyScrollState.viewportSize)
            assertEquals(0, model.bodyScrollState.value)
        }
        assertCaretVisible(model, expected = false)
        insertAndAssertCaretVisible(model)
    }

    private fun showEditor(owner: ComponentActivity, model: PostEditorViewModel, route: PostEditorRequest) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                focusManager = LocalFocusManager.current
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    PostEditorScreen(route, { _, _, _, _ -> }, viewModel = model)
                }
            }
        }
    }

    private fun insertAndAssertCaretVisible(model: PostEditorViewModel) {
        compose.runOnIdle { model.submit(PostEditorIntent.SmileySelected(":jap:")) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.bodyScrollState.value > 80) }
        assertCaretVisible(model, expected = true)
    }

    private fun assertCaretVisible(model: PostEditorViewModel, expected: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        compose.runOnIdle {
            val caret = layouts.single().getCursorRect(model.bodyFieldState.selection.end)
            val scroll = model.bodyScrollState
            assertTrue(scroll.viewportSize > 0)
            val visible = caret.top >= scroll.value - 1f &&
                caret.bottom <= scroll.value + scroll.viewportSize + 1f
            assertEquals(expected, visible)
        }
    }

    private fun checkViewportRestoration(shorterContent: Boolean) {
        val firstHost = host()
        val first = editor(firstHost.get())
        var displayed by mutableStateOf(firstHost.get() to first)
        compose.setContent {
            val (owner, editor) = displayed
            // Activity recreation discards the old composition, including the collected UI state.
            // Reusing it would briefly render the new empty field with the previous VM's ready state.
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                        PostEditorScreen(request, { _, _, _, _ -> }, viewModel = editor)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val body = "a long line of editor content\n".repeat(100)
        val savedBody = if (shorterContent) "short editor content" else body
        // ED-14: an offscreen restored caret must not override ED-81's saved offset.
        val initialSelection = TextRange(body.length - 19, body.length - 3)
        val savedSelection = if (shorterContent) TextRange(19, 3) else initialSelection
        compose.runOnIdle {
            first.bodyFieldState.edit {
                replace(0, length, body)
                selection = initialSelection
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(first.bodyScrollState.maxValue > 80)
            first.viewModelScope.launch { first.bodyScrollState.scrollTo(80) }
        }
        compose.waitForIdle()
        val bundle = Bundle()
        compose.runOnIdle {
            assertEquals(80, first.bodyScrollState.value)
            // Capture a shorter buffer before the old viewport has been remeasured.
            if (shorterContent) first.bodyFieldState.edit {
                replace(0, length, savedBody)
                selection = savedSelection
            }
            // The real ON_STOP observer captures the live field before the Android state registry saves.
            firstHost.pause().stop().saveInstanceState(bundle)
            dispatcher.scheduler.runCurrent()
            assertEquals(savedBody, store.saved[EditorDraftKey.reply(23, 35421)]?.body)
            firstHost.destroy()
            store.loadGate = CompletableDeferred()
        }
        val restoredHost = host(bundle)
        val restored = editor(restoredHost.get())
        assertNotSame(first.chromeMemory, restored.chromeMemory)
        assertFalse(restored.chromeMemory.focus.autoFocusConsumed) // New VM: entry autofocus is unchanged.
        compose.runOnIdle { displayed = restoredHost.get() to restored }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(restored.state.value.isInitializing)
            assertEquals(Int.MAX_VALUE, restored.bodyScrollState.maxValue)
            assertEquals(0, restored.bodyScrollState.value)
            store.loadGate?.complete(Unit)
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        compose.runOnIdle {
            assertEquals(savedBody, restored.bodyFieldState.text.toString())
            assertNull(restored.state.value.restorableDraft)
            assertEquals(savedSelection, restored.bodyFieldState.selection)
            if (shorterContent) assertEquals(0, restored.bodyScrollState.maxValue)
            else assertTrue(restored.bodyScrollState.maxValue > 80)
            assertEquals(if (shorterContent) 0 else 80, restored.bodyScrollState.value)
        }
        if (!shorterContent) {
            assertCaretVisible(restored, expected = false)
            insertAndAssertCaretVisible(restored)
        }
    }

    private fun host(saved: Bundle? = null) = Robolectric.buildActivity(ComponentActivity::class.java)
        .create(saved).start().resume().also(hosts::add)

    private fun editor(
        owner: ComponentActivity,
        request: PostEditorRequest = this.request,
    ): PostEditorViewModel = ViewModelProvider(owner, viewModelFactory {
        initializer {
            PostEditorViewModel(
                request = request, previewParser = previewParser, defaultDispatcher = dispatcher,
                replyRepository = replies, editPostRepository = edits,
                smileyRepository = mockk(relaxed = true), draftStore = store, diagnostics = DiagnosticsLog(),
                userPreferencesRepository = mockk<UserPreferencesRepository> {
                    every { observeConfirmBeforePosting() } returns flowOf(true)
                    every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
                    every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
                    every { observeEditorPreviewPositions() } returns flowOf(emptyMap())
                    every { observeEditorShortcut() } returns flowOf(EditorShortcut.NONE)
                },
                uploadRepository = mockk(relaxed = true), imageUploadReader = mockk(relaxed = true),
                authRepository = mockk<AuthRepository> {
                    every { observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
                },
                quoteMaterializer = TopicReplyQuoteMaterializer(replies),
                hfrFavoriteSmileyRepository = FakeHfrFavoriteSmileyRepository(),
                smileyLocalCollections = FakeSmileyLocalCollections(),
                clock = DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
                savedStateHandle = createSavedStateHandle(),
            ).also(editors::add)
        }
    })[PostEditorViewModel::class.java]
}

/** Test-only Activity: onCreate mounts a new screen against its retained ViewModelStore. */
internal class PostEditorRotationHostActivity : ComponentActivity() {
    lateinit var model: PostEditorViewModel
    lateinit var view: android.view.View
    lateinit var inputMode: InputModeManager
    lateinit var focusManager: FocusManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(this)
    }

    fun setImeVisible(visible: Boolean) {
        ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) 120 else 0))
            .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
    }

    companion object {
        var render: (PostEditorRotationHostActivity) -> Unit = {}
    }
}
