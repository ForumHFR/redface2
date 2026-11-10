package fr.forumhfr.redface2.feature.messages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
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
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Actual screen: ON_STOP, Android saved-state registry and measured field, following the post tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PrivateMessageReplyRestorationScreenTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(dispatcher)
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val editors = mutableListOf<PrivateMessageReplyViewModel>()
    private val repository = mockk<PrivateMessageWriteRepository>()
    private val store = VersionedEditorDraftStore()
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val collections = FakeSmileyLocalCollections()
    private val request = PrivateMessageReplyRequest(threadId = 42)
    private val form = ReplyForm("hash", "Titre du formulaire", mapOf("newdest" to "alice, Administration"), false)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        collections.snapshot.value = SmileyLocalCollectionSnapshot("tester")
        coEvery { repository.fetchReplyForm(any(), any()) } returns form
    }
    @After fun tearDown() {
        hosts.forEach { it.close() }
        editors.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun processDeathResumesStopCheckpointAfterOwnerReadAndMeasurement() {
        val firstHost = host()
        val first = editor(firstHost.get())
        var shown by mutableStateOf(firstHost.get() to first)
        compose.setContent {
            val (owner, model) = shown
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RedfaceTheme(darkTheme = false, dynamicColor = false) {
                        PrivateMessageReplyScreen(request, { _, _ -> }, {}, viewModel = model)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val body = "Longue réponse privée\n".repeat(200)
        val selection = TextRange(body.length - 19, body.length - 3)
        compose.runOnIdle { first.bodyFieldState.edit { replace(0, length, body); this.selection = selection } }
        compose.waitForIdle()
        compose.runOnIdle { first.bodyScrollState.dispatchRawDelta(80f - first.bodyScrollState.value) }
        compose.waitForIdle()
        val bundle = Bundle()
        compose.runOnIdle {
            assertEquals(80, first.bodyScrollState.value)
            firstHost.pause().stop().saveInstanceState(bundle)
            dispatcher.scheduler.runCurrent()
            assertEquals(body, store.saved[EditorDraftKey.mpReply(42)]?.body)
            firstHost.destroy()
            store.loadGate = CompletableDeferred()
        }
        val restoredHost = host(bundle)
        val restored = editor(restoredHost.get())
        compose.runOnIdle { shown = restoredHost.get() to restored }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(restored.state.value.isInitializing)
            assertEquals(Int.MAX_VALUE, restored.bodyScrollState.maxValue)
            store.loadGate?.complete(Unit)
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(body, restored.bodyFieldState.text.toString())
            assertEquals(selection, restored.bodyFieldState.selection)
            assertNull(restored.state.value.restorableDraft)
            assertTrue(restored.bodyScrollState.maxValue > 80)
            assertEquals(80, restored.bodyScrollState.value)
            assertNull(restored.chromeMemory.host.surface)
        }
    }

    @Test fun rotationRetainsManagerAndDoesNotRepeatAutomaticEntryAfterClosing() {
        val route = request.copy(openRecipientManager = true)
        var owner by mutableStateOf(host().get())
        val model = editor(owner, route)
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    RedfaceTheme(darkTheme = false, dynamicColor = false) {
                        PrivateMessageReplyScreen(route, { _, _ -> }, {}, viewModel = model)
                    }
                }
            }
        }
        compose.onNodeWithText("Membres de la discussion").assertIsDisplayed()
        val selection = TextRange(6, 1)
        // Populate the body before opening a manager, as a user would. Editing behind the initial
        // sheet bypasses captureBody and lets BTF2 collapse an uncaptured range during disposal.
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Bonjour")
            .performTextInputSelection(selection)
        compose.onNodeWithContentDescription("2 destinataires, gérer").performClick()
        compose.onNodeWithText("Membres de la discussion").assertIsDisplayed()
        compose.runOnIdle {
            model.chromeMemory.host.focus.imeBeforeSurface = false
            owner = host().get() // Fresh composition/activity owner, same VM as a configuration change.
        }
        compose.onNodeWithText("Membres de la discussion").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, model.bodyFieldState.selection)
            assertFalse(model.chromeMemory.host.focus.imeBeforeSurface)
            owner = host().get()
        }
        compose.onNodeWithText("Membres de la discussion").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { assertTrue(model.chromeMemory.recipientEntryHandled) }
    }

    @Test fun accountSwitchRemovesOpenSmileySheetEventsAndStaleConfirmation() {
        val owner = host().get()
        val model = editor(owner)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    PrivateMessageReplyScreen(request, { _, _ -> }, {}, viewModel = model)
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { model.bodyFieldState.edit { replace(0, length, "Réponse privée") } }
        compose.waitForIdle()
        val oldArm = compose.runOnIdle { model.onSubmit(); requireNotNull(model.state.value.armToken) }
        compose.runOnIdle { model.onSubmitConfirmationDismissed() }
        compose.onNodeWithContentDescription("Smileys").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle {
            auth.value = AuthState.Authenticated("other")
            store.activeOwner = "other"
            collections.snapshot.value = SmileyLocalCollectionSnapshot("other")
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Titre du formulaire").assertDoesNotExist()
        compose.onNodeWithContentDescription("2 destinataires, gérer").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(model.editorEvents.visibleEvent)
            assertNull(model.state.value.restorableDraft)
            assertFalse(model.state.value.formAvailable)
            assertNull(model.state.value.armToken)
            model.onSubmitConfirmed(oldArm)
        }
        coVerify(exactly = 0) { repository.submitReply(any(), any(), any(), any(), any()) }
    }

    @Test fun lateQuoteEstablishesViewportBaselineBeforeTheNextProgrammaticInsertion() {
        val gate = CompletableDeferred<ReplyForm>()
        coEvery { repository.fetchReplyForm(any(), any()) } coAnswers { gate.await() }
        val owner = host().get()
        val model = editor(owner)
        lateinit var focus: FocusManager
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                focus = LocalFocusManager.current
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    PrivateMessageReplyScreen(request, { _, _ -> }, {}, viewModel = model)
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val quote = "Citation privée\n".repeat(200)
        compose.runOnIdle { focus.clearFocus(force = true); gate.complete(form.copy(initialContent = quote)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(quote, model.bodyFieldState.text.toString())
            assertTrue(model.bodyScrollState.maxValue > 80)
            assertEquals(0, model.bodyScrollState.value)
            model.onSmileySelected(":jap:")
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.bodyScrollState.value > 0) }
    }

    private fun host(saved: Bundle? = null) = Robolectric.buildActivity(ComponentActivity::class.java)
        .create(saved).start().resume().also(hosts::add)

    private fun editor(owner: ComponentActivity, route: PrivateMessageReplyRequest = request) =
        ViewModelProvider(owner, viewModelFactory {
            initializer {
                PrivateMessageReplyViewModel(route, repository, mockk(relaxed = true), dispatcher,
                    mockk<UserPreferencesRepository> {
                        every { observeConfirmBeforePosting() } returns flowOf(true)
                        every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
                        every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
                    }, store, mockk<AuthRepository> { every { observeAuthState() } returns auth },
                    mockk(relaxed = true), mockk(relaxed = true), DiagnosticsLog(),
                    DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
                    mockk(relaxed = true), FakeHfrFavoriteSmileyRepository(), collections,
                    createSavedStateHandle()).also(editors::add)
            }
        })[PrivateMessageReplyViewModel::class.java]
}
