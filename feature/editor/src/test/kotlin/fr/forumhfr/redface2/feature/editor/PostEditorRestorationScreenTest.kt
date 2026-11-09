package fr.forumhfr.redface2.feature.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.editor.EditorDraftKey
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.domain.write.TopicReplyQuoteMaterializer
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    fun closedKeyboardStaysClosedAfterPanelRecreationWithSameViewModel() {
        store.preload(EditorDraftKey.reply(23, 35421), EditorDraftStore.Draft(body = "Brouillon"))
        var owner by mutableStateOf(host().get())
        val model = editor(owner)
        var sessions = 0
        var inputActive = false
        val input = PlatformTextInputInterceptor { request, next ->
            sessions++
            inputActive = true
            try { next.startInputMethod(request) } finally { inputActive = false }
        }
        lateinit var back: OnBackPressedDispatcher
        lateinit var view: android.view.View
        compose.setContent {
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
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
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        val before = compose.runOnIdle {
            assertFalse(model.chromeMemory.focus.imeBeforeSurface)
            assertFalse(inputActive)
            sessions.also { owner = host().get() }
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(before, sessions); assertFalse(inputActive) }
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
        compose.runOnIdle { displayed = restoredHost.get() to restored }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(restored.state.value.isInitializing)
            assertEquals(Int.MAX_VALUE, restored.bodyScrollState.maxValue)
            assertEquals(0, restored.bodyScrollState.value)
            store.loadGate?.complete(Unit)
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
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
                request = request, previewParser = mockk(relaxed = true), defaultDispatcher = dispatcher,
                replyRepository = replies, editPostRepository = edits,
                smileyRepository = mockk(relaxed = true), draftStore = store, diagnostics = DiagnosticsLog(),
                userPreferencesRepository = mockk<UserPreferencesRepository> {
                    every { observeConfirmBeforePosting() } returns flowOf(true)
                    every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
                    every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
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
