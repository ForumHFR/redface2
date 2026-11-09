package fr.forumhfr.redface2.feature.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
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
import fr.forumhfr.redface2.core.domain.editor.VersionedEditorDraftStore
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val editors = mutableListOf<PostEditorViewModel>()
    private val request = PostEditorRequest(PostEditorMode.Reply, 23, 35421, null, 1, 0)

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
        compose.runOnIdle {
            first.bodyFieldState.edit {
                replace(0, length, body)
                selection = TextRange(19, 3)
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
                selection = TextRange(19, 3)
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
            assertEquals(TextRange(19, 3), restored.bodyFieldState.selection)
            if (shorterContent) assertEquals(0, restored.bodyScrollState.maxValue)
            else assertTrue(restored.bodyScrollState.maxValue > 80)
            assertEquals(if (shorterContent) 0 else 80, restored.bodyScrollState.value)
        }
    }

    private fun host(saved: Bundle? = null) = Robolectric.buildActivity(ComponentActivity::class.java)
        .create(saved).start().resume().also(hosts::add)

    private fun editor(owner: ComponentActivity): PostEditorViewModel = ViewModelProvider(owner, viewModelFactory {
        initializer {
            PostEditorViewModel(
                request = request, previewParser = mockk(relaxed = true), defaultDispatcher = dispatcher,
                replyRepository = replies, editPostRepository = mockk(relaxed = true),
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
