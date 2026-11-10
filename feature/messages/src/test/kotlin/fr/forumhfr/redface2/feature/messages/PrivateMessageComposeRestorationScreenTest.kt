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
import androidx.compose.ui.test.onNodeWithTag
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
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.editor.ImagePickerMode
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
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
class PrivateMessageComposeRestorationScreenTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val compose = createComposeRule(dispatcher)
    private val hosts = mutableListOf<ActivityController<ComponentActivity>>()
    private val editors = mutableListOf<PrivateMessageComposeViewModel>()
    private val repository = mockk<PrivateMessageWriteRepository>()
    private val store = VersionedEditorDraftStore()
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("tester"))
    private val collections = FakeSmileyLocalCollections()
    private val favorites = FakeHfrFavoriteSmileyRepository()
    private val form = ReplyForm("hash", "", emptyMap(), false)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        collections.snapshot.value = SmileyLocalCollectionSnapshot("tester")
        coEvery { repository.fetchComposeForm(any()) } returns form
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
                        PrivateMessageComposeScreen({}, {}, viewModel = model)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val body = "Longue réponse privée\n".repeat(200)
        val selection = TextRange(body.length - 19, body.length - 3)
        val recipients = EditorRecipientState(listOf("alice", "Bob with spaces"), "tail")
        compose.runOnIdle {
            first.onRecipientStateChanged(recipients)
            first.onSubjectChanged("Sujet privé")
            first.bodyFieldState.edit { replace(0, length, body); this.selection = selection }
        }
        compose.waitForIdle()
        compose.runOnIdle { first.bodyScrollState.dispatchRawDelta(80f - first.bodyScrollState.value) }
        compose.waitForIdle()
        val bundle = Bundle()
        compose.runOnIdle {
            assertEquals(80, first.bodyScrollState.value)
            firstHost.pause().stop().saveInstanceState(bundle)
            dispatcher.scheduler.runCurrent()
            val saved = requireNotNull(store.saved[EditorDraftKey.mpCompose()])
            assertEquals(body, saved.body)
            assertEquals("Sujet privé", saved.subject)
            assertEquals(recipients, EditorRecipientState.decode(saved.recipients))
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
            assertEquals("Sujet privé", restored.state.value.subject)
            assertEquals(recipients, restored.state.value.recipientState)
            assertEquals(body, restored.bodyFieldState.text.toString())
            assertEquals(selection, restored.bodyFieldState.selection)
            assertNull(restored.state.value.restorableDraft)
            assertTrue(restored.bodyScrollState.maxValue > 80)
            assertEquals(80, restored.bodyScrollState.value)
            assertNull(restored.chromeMemory.host.surface)
        }
    }

    @Test fun rotationRetainsInitialHeaderAndProcessDeathNeverRepeatsEntryAfterClosing() {
        val modelOwner = host()
        var owner by mutableStateOf(modelOwner.get())
        var model by mutableStateOf(editor(owner, initial = null))
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        compose.setContent {
            key(owner) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    RedfaceTheme(darkTheme = false, dynamicColor = false) {
                        PrivateMessageComposeScreen({}, {}, viewModel = model)
                    }
                }
            }
        }
        compose.onNodeWithText("Retour au message").assertIsDisplayed()
        val selection = TextRange(6, 1)
        // Capture a body edited before opening the header; replacing text behind it bypasses captureBody.
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Bonjour")
        compose.onNode(hasSetTextAction()).performTextInputSelection(selection)
        compose.onNodeWithContentDescription("Modifier les destinataires et le sujet").performClick()
        compose.onNodeWithText("Retour au message").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(selection, model.bodyFieldState.selection)
            assertFalse(model.chromeMemory.host.focus.imeBeforeSurface)
            owner = host().get() // Fresh composition/activity owner, same VM as a configuration change.
        }
        compose.onNodeWithText("Retour au message").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, model.bodyFieldState.selection)
            assertFalse(model.chromeMemory.host.focus.imeBeforeSurface)
            assertFalse(model.chromeMemory.host.focus.autoFocusConsumed)
            owner = host().get()
        }
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { assertNull(model.chromeMemory.host.surface) }
        val bundle = Bundle()
        compose.runOnIdle {
            val currentHost = hosts.last()
            currentHost.pause().stop()
            dispatcher.scheduler.runCurrent()
            // The retained VM's handle stays registered on its original owner in this test harness.
            modelOwner.pause().stop().saveInstanceState(bundle).destroy()
            currentHost.destroy()
            owner = host(bundle).get()
            model = editor(owner, initial = null)
        }
        compose.onNodeWithText("Retour au message").assertDoesNotExist()
        compose.onNodeWithTag("editor-scaffold-field").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Bonjour", model.bodyFieldState.text.toString())
            assertEquals(selection, model.bodyFieldState.selection)
            assertTrue(model.state.value.recipientState.chips.isEmpty())
            assertNull(model.state.value.restorableDraft)
        }
    }

    @Test fun accountSwitchRemovesOpenSmileySheetEventsAndStaleConfirmation() {
        val owner = host().get()
        val model = editor(owner)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    PrivateMessageComposeScreen({}, {}, viewModel = model)
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle {
            model.onSubjectChanged("Sujet privé")
            model.bodyFieldState.edit { replace(0, length, "Réponse privée") }
        }
        compose.waitForIdle()
        val oldArm = compose.runOnIdle { model.onSubmit(); requireNotNull(model.state.value.armToken) }
        compose.runOnIdle { model.onSubmitConfirmationDismissed() }
        compose.onNodeWithContentDescription("Smileys").performClick()
        compose.onNode(isDialog()).assertExists()
        val smiley = EditorSmiley("[:fictif_geste:2]", "https://example.invalid/geste.gif", EditorSmileySource.WIKI)
        val oldFavorite = compose.runOnIdle {
            model.smileyPicker.onDetailChanged(smiley)
            val open = model.smileyPicker.state.value as SmileyPickerState.Open
            assertEquals(smiley, open.detailSmiley)
            requireNotNull(open.onFavoriteChange)
        }
        compose.runOnIdle {
            auth.value = AuthState.Authenticated("other")
            store.activeOwner = "other"
            collections.snapshot.value = SmileyLocalCollectionSnapshot("other")
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(model.editorEvents.visibleEvent)
            assertNull(model.state.value.restorableDraft)
            assertFalse(model.state.value.formAvailable)
            assertNull(model.state.value.armToken)
            model.onSubmitConfirmed(oldArm)
            val picker = model.smileyPicker.state.value as SmileyPickerState.Open
            assertNull(picker.detailSmiley)
            assertNull(picker.onFavoriteChange)
            assertTrue(picker.favoriteSmileys.isEmpty())
            oldFavorite(smiley)
            assertTrue(favorites.owners.isEmpty())
        }
        coVerify(exactly = 0) { repository.submitNewMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun lateFormEstablishesViewportBaselineBeforeTheNextProgrammaticInsertion() {
        val gate = CompletableDeferred<ReplyForm>()
        val owner = host().get()
        val model = editor(owner)
        lateinit var focus: FocusManager
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                focus = LocalFocusManager.current
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    PrivateMessageComposeScreen({}, {}, viewModel = model)
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        val quote = "Citation privée\n".repeat(200)
        coEvery { repository.fetchComposeForm(any()) } coAnswers { gate.await() }
        compose.runOnIdle { focus.clearFocus(force = true); model.retryFormLoad() }
        compose.runOnIdle { gate.complete(form.copy(initialContent = quote)) }
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

    private fun editor(owner: ComponentActivity, initial: String? = "alice") =
        ViewModelProvider(owner, viewModelFactory {
            initializer {
                PrivateMessageComposeViewModel(initial, repository, mockk(relaxed = true), dispatcher,
                    mockk<UserPreferencesRepository> {
                        every { observeConfirmBeforePosting() } returns flowOf(true)
                        every { observeEditorImageInsert() } returns flowOf(EditorImageInsert.FULL)
                        every { observeImagePickerMode() } returns flowOf(ImagePickerMode.DEFAULT)
                    }, store, mockk<AuthRepository> { every { observeAuthState() } returns auth },
                    mockk(relaxed = true), mockk(relaxed = true), DiagnosticsLog(),
                    DiagnosticsClock { dispatcher.scheduler.currentTime * 1_000_000L },
                    mockk(relaxed = true), favorites, collections,
                    createSavedStateHandle()).also(editors::add)
            }
        })[PrivateMessageComposeViewModel::class.java]
}
