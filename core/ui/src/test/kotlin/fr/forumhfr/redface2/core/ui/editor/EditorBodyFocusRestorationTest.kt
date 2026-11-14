package fr.forumhfr.redface2.core.ui.editor

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorBodyFocusRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseBeforeTheRestorationFrameKeepsFocusPendingUntilResume() {
        val owner = BodyLifecycleOwner()
        val snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        val memory = EditorBodyFocusMemory().apply { this.snapshot = snapshot }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val focus = FocusRequester()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val observation = rememberEditorBodyFocus(body, memory, imeVisible = false)
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    BbcodeTextField(body, "Message", Modifier.focusRequester(focus)
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
                RestoreRecreatedEditorBodyFocus(observation, true, body, focus) { }
                // Pause without stop after attachment, before the restoration's first frame.
                DisposableEffect(Unit) {
                    owner.registry.currentState = Lifecycle.State.STARTED
                    onDispose { }
                }
            }
        }
        // Let the frame pass while paused: no focus request and no consumed snapshot.
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(snapshot, memory.snapshot)
            assertEquals(selection, body.selection)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(snapshot, memory.snapshot)
            assertEquals(selection, body.selection)
        }
    }

    @Test fun missingPreviewMeasurementExpiresBeforeAControlCanLoseFocus() =
        checkConsumedRestoration(EditorBodyFocusAvailability.Pending)

    @Test fun blockedPreviewExpiresBeforeAControlCanLoseFocus() =
        checkConsumedRestoration(EditorBodyFocusAvailability.Blocked)

    @Test fun transientBlockedPreviewRestoresBeforeTheDeadline() = checkBlockedRestoration(becomesReady = true)

    @Test fun stableBlockedPreviewExpiresWithPreviewBlocked() = checkBlockedRestoration(becomesReady = false)

    private fun checkBlockedRestoration(becomesReady: Boolean) {
        ShadowLog.clear()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        val snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        val memory = EditorBodyFocusMemory().apply { this.snapshot = snapshot }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val focus = FocusRequester()
        val observation = EditorBodyFocusObservation(memory, imeVisible = false,
            trace = EditorBodyFocusTrace(enabled = true))
        var availability by mutableStateOf(EditorBodyFocusAvailability.Blocked)
        var bodyFocusGains = 0
        var focusGainedAt = -1L
        compose.mainClock.autoAdvance = false
        val startedAt = compose.mainClock.currentTime
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                BbcodeTextField(body, "Message", Modifier.focusRequester(focus)
                    .onFocusChanged {
                        if (it.isFocused) {
                            bodyFocusGains++
                            focusGainedAt = compose.mainClock.currentTime
                        }
                    }
                    .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
            }
            RestoreRecreatedEditorBodyFocus(observation, true, body, focus, availability) { }
        }
        compose.mainClock.advanceTimeBy(320L)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), restorationOutcomes())
            assertEquals(snapshot, memory.snapshot)
            assertEquals(selection, body.selection)
            assertEquals(0, bodyFocusGains)
            if (becomesReady) availability = EditorBodyFocusAvailability.Ready
        }
        if (becomesReady) {
            // Same pattern as the Pending tests: let frames run until the attempt settles. The clock keeps
            // running for the caret afterwards, so time the focus gain itself against the 500 ms deadline.
            compose.mainClock.autoAdvance = true
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertTrue(focusGainedAt >= 0L && focusGainedAt - startedAt < 500L)
                assertEquals(listOf("restored"), restorationOutcomes())
                assertEquals(selection, body.selection)
                assertEquals(1, bodyFocusGains)
            }
        } else {
            compose.mainClock.advanceTimeBy(280L) // Cross the original 500 ms deadline while still Blocked.
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle {
                assertEquals(listOf("preview-blocked"), restorationOutcomes())
                assertEquals(EditorBodyFocusSnapshot(), memory.snapshot)
                availability = EditorBodyFocusAvailability.Ready
            }
            compose.mainClock.advanceTimeBy(64L)
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.runOnIdle {
                assertEquals(listOf("preview-blocked"), restorationOutcomes())
                assertEquals(selection, body.selection)
                assertEquals(0, bodyFocusGains)
            }
        }
    }

    private fun restorationOutcomes(): List<String> = ShadowLog.getLogsForTag("RF2-H")
        .filter { "outcome=" in it.msg }.map { it.msg.substringAfter("outcome=") }

    private fun checkConsumedRestoration(initial: EditorBodyFocusAvailability) {
        val memory = EditorBodyFocusMemory().apply {
            snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val bodyFocus = FocusRequester()
        val controlFocus = FocusRequester()
        var bodyFocusGains = 0
        var availability by mutableStateOf(initial)
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            val observation = rememberEditorBodyFocus(body, memory, imeVisible = false)
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Column {
                    // Keyboard mode calls Android's requestFocusFromTouch(), which can focus the
                    // first child. Keep that setup on the control, away from BTF2's loss-of-focus
                    // selection collapse: this test exercises a body that stays unfocused.
                    TextButton({}, Modifier.focusRequester(controlFocus)) { Text("Contrôle") }
                    BbcodeTextField(body, "Message", Modifier.focusRequester(bodyFocus)
                        .onFocusChanged { if (it.isFocused) bodyFocusGains++ }
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
            }
            RestoreRecreatedEditorBodyFocus(observation, true, body, bodyFocus, availability) { }
        }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.mainClock.advanceTimeBy(600L)
        compose.runOnIdle {
            // Both a blocked preview and a missing measurement expire within the same deadline,
            // independently of the subsequent focus request or a future resize.
            assertEquals(EditorBodyFocusSnapshot(), memory.snapshot)
            assertEquals(selection, body.selection)
            assertEquals(0, bodyFocusGains)
            assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
        }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(selection, body.selection)
            assertEquals(0, bodyFocusGains)
            assertTrue(controlFocus.requestFocus())
        }
        compose.onNodeWithText("Contrôle").assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, body.selection)
            assertEquals(0, bodyFocusGains)
            availability = EditorBodyFocusAvailability.Ready
        }
        compose.onNodeWithText("Contrôle").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(selection, body.selection)
            assertEquals(0, bodyFocusGains)
        }
    }

    @Test fun aTouchBeforeMeasurementCancelsEvenWithoutMovingFocus() = checkCancelledWait(WaitInteraction.Touch)

    @Test fun focusTakenAndReleasedBeforeMeasurementCancelsTheAttempt() = checkCancelledWait(WaitInteraction.Focus)

    @Test fun aControlKeepsFocusWhenThePendingMeasurementArrives() = checkCancelledWait(WaitInteraction.FocusHeld)

    @Test fun aTouchWhileBlockedCancelsWithPointerReason() =
        checkCancelledWait(WaitInteraction.Touch, EditorBodyFocusAvailability.Blocked)

    @Test fun aKeyWhileBlockedCancelsWithKeyReason() =
        checkCancelledWait(WaitInteraction.Key, EditorBodyFocusAvailability.Blocked)

    @Test fun anImeEditWhileBlockedCancelsWithTextReason() =
        checkCancelledWait(WaitInteraction.Edit, EditorBodyFocusAvailability.Blocked)

    @Test fun aSelectionChangeWhileBlockedCancelsWithSelectionReason() =
        checkCancelledWait(WaitInteraction.Selection, EditorBodyFocusAvailability.Blocked)

    @Test fun aHostBlockWhileBlockedCancelsWithoutRestoringLater() =
        checkCancelledWait(WaitInteraction.Host, EditorBodyFocusAvailability.Blocked)

    @Test fun refusedProgrammaticFocusInTouchModeDoesNotCancelRestoration() =
        checkFocusWithoutInput(InputMode.Touch)

    @Test fun programmaticFocusGainInTouchModeCancelsPendingRestorationWithFocusReason() =
        checkProgrammaticFocusCancellation(EditorBodyFocusAvailability.Pending)

    @Test fun programmaticFocusGainInTouchModeCancelsBlockedRestorationWithFocusReason() =
        checkProgrammaticFocusCancellation(EditorBodyFocusAvailability.Blocked)

    private fun checkProgrammaticFocusCancellation(initial: EditorBodyFocusAvailability) {
        val memory = EditorBodyFocusMemory().apply {
            snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val bodyFocus = FocusRequester()
        val controlFocus = FocusRequester()
        var bodyFocusGains = 0
        var availability by mutableStateOf(initial)
        lateinit var observation: EditorBodyFocusObservation
        lateinit var inputMode: InputModeManager
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            inputMode = LocalInputModeManager.current
            observation = rememberEditorBodyFocus(body, memory, imeVisible = false)
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.cancelEditorBodyFocusRestorationOnInteraction(observation)) {
                    // Unlike TextButton, this control accepts focus in touch mode, without input.
                    Box(Modifier.size(48.dp).testTag("touch-focus-control")
                        .focusRequester(controlFocus).focusable())
                    BbcodeTextField(body, "Message", Modifier.focusRequester(bodyFocus)
                        .onFocusChanged { if (it.isFocused) bodyFocusGains++ }
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
            }
            RestoreRecreatedEditorBodyFocus(observation, true, body, bodyFocus, availability) { }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(initial, availability)
            assertEquals(InputMode.Touch, inputMode.inputMode)
            assertTrue(!observation.restorationCancelled)
            assertTrue(controlFocus.requestFocus())
        }
        compose.onNodeWithTag("touch-focus-control").assertIsFocused()
        compose.runOnIdle {
            assertEquals(InputMode.Touch, inputMode.inputMode)
            assertTrue(observation.restorationCancelled)
            assertEquals("focus", observation.cancellationReason)
            assertEquals(selection, body.selection)
            availability = EditorBodyFocusAvailability.Ready
        }
        compose.mainClock.autoAdvance = true
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithTag("touch-focus-control").assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, bodyFocusGains)
            assertEquals(selection, body.selection)
        }
    }

    @Test fun anImeEditBeforeMeasurementCancelsTheAttempt() = checkCancelledWait(WaitInteraction.Edit)

    @Test fun aKeyBeforeMeasurementCancelsEvenWithoutMovingFocus() = checkCancelledWait(WaitInteraction.Key)

    @Test fun keyboardDefaultFocusWithoutInputDoesNotCancelRestoration() =
        checkFocusWithoutInput(InputMode.Keyboard)

    @Test fun keyboardDefaultFocusWhileBlockedRestoresBeforeTheDeadline() =
        checkFocusWithoutInput(InputMode.Keyboard, EditorBodyFocusAvailability.Blocked)

    private fun checkFocusWithoutInput(
        mode: InputMode, initial: EditorBodyFocusAvailability = EditorBodyFocusAvailability.Pending,
    ) {
        ShadowLog.clear()
        // Enable the lifecycle-owned observation's debug trace in the Robolectric host.
        InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.apply {
            flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
        }
        val memory = EditorBodyFocusMemory().apply {
            snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        }
        val selection = TextRange(6, 1)
        val body = TextFieldState("Bonjour", selection)
        val bodyFocus = FocusRequester()
        val controlFocus = FocusRequester()
        var availability by mutableStateOf(initial)
        var focusGainedAt = -1L
        lateinit var observation: EditorBodyFocusObservation
        lateinit var inputMode: InputModeManager
        if (mode == InputMode.Touch) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
        compose.mainClock.autoAdvance = false
        val startedAt = compose.mainClock.currentTime
        compose.setContent {
            inputMode = LocalInputModeManager.current
            observation = rememberEditorBodyFocus(body, memory, imeVisible = false)
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.cancelEditorBodyFocusRestorationOnInteraction(observation)) {
                    TextButton({}, Modifier.focusRequester(controlFocus)) { Text("Contrôle") }
                    BbcodeTextField(body, "Message", Modifier.focusRequester(bodyFocus)
                        .onFocusChanged { if (it.isFocused) focusGainedAt = compose.mainClock.currentTime }
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
            }
            RestoreRecreatedEditorBodyFocus(observation, true, body, bodyFocus, availability) { }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            if (mode == InputMode.Keyboard) assertTrue(inputMode.requestInputMode(mode))
            assertEquals(mode, inputMode.inputMode)
            // TextButton refuses focus in touch mode. In keyboard mode, model Android's default
            // focus explicitly: changing mode alone need not focus the first child in this host.
            assertEquals(mode == InputMode.Keyboard, controlFocus.requestFocus())
        }
        compose.mainClock.advanceTimeBy(320L)
        if (mode == InputMode.Keyboard) compose.onNodeWithText("Contrôle").assertIsFocused()
        else compose.onNodeWithText("Contrôle").assertIsNotFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            // ED-86 cancels after user input; a refused request is neither input nor a focus gain.
            assertTrue(!observation.restorationCancelled)
            assertEquals(emptyList<String>(), restorationOutcomes())
            assertEquals(selection, body.selection)
            availability = EditorBodyFocusAvailability.Ready
        }
        // Time the focus gain itself: the caret can keep advancing the clock after restoration.
        compose.mainClock.autoAdvance = true
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Contrôle").assertIsNotFocused()
        compose.runOnIdle {
            assertTrue(focusGainedAt >= 0L && focusGainedAt - startedAt < 500L)
            assertEquals(listOf("restored"), restorationOutcomes())
            assertEquals(selection, body.selection)
            assertEquals(EditorBodyFocusSnapshot(true, false), memory.snapshot)
        }
    }

    private fun checkCancelledWait(
        interaction: WaitInteraction, initial: EditorBodyFocusAvailability = EditorBodyFocusAvailability.Pending,
    ) {
        ShadowLog.clear()
        val memory = EditorBodyFocusMemory().apply {
            snapshot = EditorBodyFocusSnapshot(focused = true, imeVisible = false)
        }
        val body = TextFieldState("Bonjour", TextRange(6, 1))
        val bodyFocus = FocusRequester()
        val controlFocus = FocusRequester()
        var availability by mutableStateOf(initial)
        var allowed by mutableStateOf(true)
        val observation = EditorBodyFocusObservation(memory, imeVisible = false,
            trace = EditorBodyFocusTrace(enabled = true))
        lateinit var releaseFocus: () -> Unit
        lateinit var inputMode: InputModeManager
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val focusManager = LocalFocusManager.current
            inputMode = LocalInputModeManager.current
            releaseFocus = { focusManager.clearFocus(force = true) }
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.cancelEditorBodyFocusRestorationOnInteraction(observation)) {
                    TextButton({}, Modifier.focusRequester(controlFocus)) { Text("Contrôle") }
                    Box(Modifier.size(48.dp).testTag("touch-without-focus"))
                    BbcodeTextField(body, "Message", Modifier.focusRequester(bodyFocus)
                        .observeEditorBodyFocus(observation), showKeyboardOnFocus = false)
                }
            }
            RestoreRecreatedEditorBodyFocus(observation, allowed, body, bodyFocus, availability) { }
        }
        compose.mainClock.advanceTimeByFrame()
        when (interaction) {
            WaitInteraction.Touch -> compose.onNodeWithTag("touch-without-focus").performTouchInput {
                down(center); up()
            }
            WaitInteraction.Focus, WaitInteraction.FocusHeld, WaitInteraction.Key -> compose.runOnIdle {
                assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
                assertTrue(controlFocus.requestFocus())
            }
            // TextFieldState is the BTF2/IME boundary; no hardware key or pointer is dispatched.
            WaitInteraction.Edit -> compose.runOnIdle { body.edit { replace(0, 0, "!") } }
            WaitInteraction.Selection -> compose.runOnIdle { body.edit { selection = TextRange(2) } }
            WaitInteraction.Host -> compose.runOnIdle { allowed = false }
        }
        if (interaction in listOf(WaitInteraction.Focus, WaitInteraction.FocusHeld, WaitInteraction.Key)) {
            // Model an actual keyboard interaction, not Android's automatic first focus.
            compose.onNodeWithText("Contrôle").performKeyInput { pressKey(Key.ShiftLeft) }
            if (interaction == WaitInteraction.Focus) compose.runOnIdle { releaseFocus() }
        }
        val selectionAfterInteraction = compose.runOnIdle { body.selection }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(listOf(interaction.reason), restorationOutcomes())
            allowed = true
            availability = EditorBodyFocusAvailability.Ready
        }
        compose.mainClock.autoAdvance = true
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        if (interaction == WaitInteraction.FocusHeld) compose.onNodeWithText("Contrôle").assertIsFocused()
        compose.runOnIdle { assertEquals(selectionAfterInteraction, body.selection) }
    }

    private enum class WaitInteraction(val reason: String) {
        Touch("cancelled:pointer"), Focus("cancelled:key"), FocusHeld("cancelled:key"), Key("cancelled:key"),
        Edit("cancelled:text"), Selection("cancelled:selection"), Host("host-blocked"),
    }

    private class BodyLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
