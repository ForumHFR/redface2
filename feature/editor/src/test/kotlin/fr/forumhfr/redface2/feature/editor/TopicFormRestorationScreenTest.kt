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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.model.write.TopicPollForm
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

/** Same Android registry / ON_STOP / measured viewport harness as the post, including topic headers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class TopicFormRestorationScreenTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(dispatcher)
    private val store = VersionedEditorDraftStore()
    private val repository = mockk<TopicFormRepository>()
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val editors = mutableListOf<TopicFormViewModel>()
    private var displayed by mutableStateOf<Pair<ComponentActivity, TopicFormViewModel>?>(null)
    private lateinit var focusManager: FocusManager
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var view: android.view.View
    private var sessions = 0
    private var inputActive = false
    private val input = PlatformTextInputInterceptor { request, next ->
        sessions++; inputActive = true
        try { next.startInputMethod(request) } finally { inputActive = false }
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.fetchNewTopicForm(any()) } returns form()
        coEvery { repository.fetchEditFirstPostForm(any()) } returns form()
    }
    @After fun tearDown() {
        hosts.forEach { it.close() }
        editors.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun newTopicStopAndBundleRestoreBodyTitleSubcategorySelectionAndViewport() =
        checkViewport(TopicFormMode.New)
    @Test fun firstPostStopAndBundleRestoreBodyTitleSubcategorySelectionAndViewport() =
        checkViewport(TopicFormMode.EditFirstPost)
    @Test fun savedScrollClampsWhenContentBecameShorter() = checkViewport(TopicFormMode.New, shorter = true)

    private fun checkViewport(mode: TopicFormMode, shorter: Boolean = false) {
        val owner = host()
        val first = editor(owner.get(), mode)
        show(owner.get(), first)
        if (mode == TopicFormMode.New) compose.onNodeWithText("Retour au message").performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val body = if (shorter) "short body" else "Editor line\n".repeat(200)
        val selection = if (shorter) TextRange(8, 2) else TextRange(body.length - 19, body.length - 3)
        val title = TextFieldValue("Titre conservé", TextRange(9, 1))
        compose.runOnIdle {
            focusManager.clearFocus(force = true)
            first.bodyFieldState.edit { replace(0, length, "Editor line\n".repeat(200)) }
            first.submit(TopicFormIntent.SubjectChanged(title))
            first.submit(TopicFormIntent.SubcatSelected(2))
        }
        compose.waitForIdle()
        val saved = Bundle()
        compose.runOnIdle { first.bodyScrollState.dispatchRawDelta(80f - first.bodyScrollState.value) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(80, first.bodyScrollState.value)
            first.bodyFieldState.edit { replace(0, length, body); this.selection = selection }
            owner.pause().stop().saveInstanceState(saved) // Real observer, before debounce or body mirror.
            dispatcher.scheduler.runCurrent()
            assertEquals(body, store.saved[draftKey(mode)]?.body)
            assertEquals(title.text, store.saved[draftKey(mode)]?.subject)
            owner.destroy()
            store.loadGate = CompletableDeferred()
        }
        val nextOwner = host(saved)
        val restored = editor(nextOwner.get(), mode)
        compose.runOnIdle { displayed = nextOwner.get() to restored }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(restored.state.value.isRestoringSession)
            assertEquals(Int.MAX_VALUE, restored.bodyScrollState.maxValue)
            store.loadGate?.complete(Unit)
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(body, restored.bodyFieldState.text.toString())
            assertEquals(selection, restored.bodyFieldState.selection)
            assertEquals(title.text, restored.state.value.subject.text)
            assertEquals(2, restored.state.value.selectedSubcat)
            assertNull(restored.state.value.restorableDraft)
            assertNull(restored.state.value.restorableSubject)
            assertEquals(if (shorter) 0 else 80, restored.bodyScrollState.value)
        }
    }

    @Test fun firstPostReadOnlyExitStartsInputOnlyWhenFocused() = checkHydration(keepFocus = true)
    @Test fun lateFirstPostReloadDoesNotStealFocusOrOpenImeOrRevealHydratedCaret() = checkHydration(keepFocus = false)

    private fun checkHydration(keepFocus: Boolean) {
        val gate = CompletableDeferred<TopicForm>()
        val hydratedBody = "Hydrated line\n".repeat(200)
        var fail = !keepFocus
        coEvery { repository.fetchEditFirstPostForm(any()) } coAnswers {
            if (fail) throw IOException("offline")
            gate.await()
        }
        val owner = host().get()
        val model = editor(owner, TopicFormMode.EditFirstPost)
        show(owner, model)
        compose.onNodeWithTag("editor-scaffold-field").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(inputActive)
            if (!keepFocus) {
                focusManager.clearFocus(force = true)
                fail = false
                model.submit(TopicFormIntent.ReloadRequested)
            }
            gate.complete(form(hydratedBody))
        }
        compose.runOnIdle {
            assertEquals(hydratedBody, model.bodyFieldState.text.toString())
            assertEquals(TextRange(hydratedBody.length), model.bodyFieldState.selection)
        }
        if (keepFocus) {
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle { assertTrue(inputActive); assertTrue(sessions > 0) }
            compose.onNode(hasSetTextAction()).performTextInput(" typed")
            compose.runOnIdle {
                assertEquals(hydratedBody + " typed", model.bodyFieldState.text.toString())
                assertEquals(TextRange(hydratedBody.length + " typed".length), model.bodyFieldState.selection)
            }
        } else {
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle {
                assertFalse(inputActive)
                assertEquals(0, sessions)
                assertEquals(0, model.bodyScrollState.value)
                model.submit(TopicFormIntent.SmileySelected(":jap:"))
            }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(model.bodyScrollState.value > 80) }
        }
    }

    @Test fun closedImeAndSelectionSurviveRestorePanelRecreationWithRetainedViewModel() {
        store.preload(draftKey(TopicFormMode.EditFirstPost), EditorDraftStore.Draft("Brouillon", "Titre"))
        val owner = host().get()
        val model = editor(owner, TopicFormMode.EditFirstPost)
        show(owner, model)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            model.bodyFieldState.edit { selection = TextRange(8, 2) }
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false).build())
        }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        val before = compose.runOnIdle {
            assertFalse(model.chromeMemory.host.focus.imeBeforeSurface)
            assertFalse(inputActive)
            sessions.also { displayed = host().get() to model }
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(TextRange(8, 2), model.bodyFieldState.selection)
            assertEquals(before, sessions)
            assertFalse(inputActive)
        }
    }

    private fun show(owner: ComponentActivity, model: TopicFormViewModel) {
        displayed = owner to model
        compose.setContent {
            val (activity, editor) = requireNotNull(displayed)
            key(activity) {
                CompositionLocalProvider(LocalLifecycleOwner provides activity) {
                    val state by editor.state.collectAsStateWithLifecycle()
                    focusManager = LocalFocusManager.current
                    back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    view = LocalView.current
                    InterceptPlatformTextInput(input) {
                        RedfaceTheme(darkTheme = false, dynamicColor = false) {
                            TopicFormScreen(route(state.mode), { _, _, _ -> }, { _, _, _, _, _ -> },
                                viewModel = editor)
                        }
                    }
                }
            }
        }
    }
    private fun host(saved: Bundle? = null) = Robolectric.buildActivity(ComponentActivity::class.java)
        .create(saved).start().resume().also(hosts::add)
    private fun editor(owner: ComponentActivity, mode: TopicFormMode): TopicFormViewModel =
        ViewModelProvider(owner, viewModelFactory {
            initializer {
                TopicFormViewModel(route(mode), mockk(relaxed = true), dispatcher, repository,
                    mockk(relaxed = true), mockk<UserPreferencesRepository> {
                        every { observeConfirmBeforePosting() } returns flowOf(true)
                        every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
                        every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
                        every { observeEditorPreviewPositions() } returns flowOf(emptyMap())
                    }, store, DiagnosticsLog(), mockk<AuthRepository> {
                        every { observeAuthState() } returns flowOf(AuthState.Authenticated("tester"))
                    }, mockk(relaxed = true), mockk(relaxed = true), FakeHfrFavoriteSmileyRepository(),
                    FakeSmileyLocalCollections(), DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
                    createSavedStateHandle()).also(editors::add)
            }
        })[TopicFormViewModel::class.java]
    private fun route(mode: TopicFormMode) = TopicFormRequest(mode, 23, 1, 35421, 1, 101)
    private fun draftKey(mode: TopicFormMode) = if (mode == TopicFormMode.New) EditorDraftKey.newTopic(23)
        else EditorDraftKey.editFirstPost(23, 101)
    private fun form(body: String = "Server body") = TopicForm("hash", "Server title", body,
        selectedSubcat = 1,
        subcategoryChoices = listOf(1, 2).map { TopicFormSubcategoryChoice(it, "Choice $it", it == 1) },
        hiddenFields = emptyMap(), options = ReplyFormOptions(), msgIcon = "1", poll = TopicPollForm(false, emptyMap()),
        isAnonymous = false)
}
