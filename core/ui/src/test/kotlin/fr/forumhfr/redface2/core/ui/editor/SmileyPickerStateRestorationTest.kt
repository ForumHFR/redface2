package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * StateRestorationTester replaces the host registry, not ModalBottomSheet's dialog registry.
 * Exercise the production grid and state helpers in the reachable registry; this does not recreate
 * the Activity. Input sessions are observable here; actual IME visibility still needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerStateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    private var inputStarts = 0

    @Before
    fun touchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    @Test
    fun `untouched consultation restores selected tab focus without starting input`() {
        val restoration = consultationRestoration()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals(0, inputStarts)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals(0, inputStarts)
    }

    @Test
    fun `focused Wiki field keeps focus after restoration even before typing`() {
        val restoration = consultationRestoration()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithTag("smiley_selected_tab").assertIsNotFocused()
    }

    @Test
    fun `typed Wiki query keeps field focus after restoration`() {
        val restoration = consultationRestoration()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("o")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        val field = compose.onNode(hasSetTextAction()).assertIsFocused().fetchSemanticsNode().config
        assertEquals("japo", field[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(4), field[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("smiley_selected_tab").assertIsNotFocused()
    }

    @Test
    fun `touched Wiki field with autofocus cleared restores selected tab focus without starting input`() {
        lateinit var uiState: SmileyPickerUiState
        val restoration = consultationRestoration { uiState = it }
        compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
        compose.runOnIdle {
            assertTrue(uiState.wikiAutofocus.value)
            // Simulate the autofocus reset from a detail visit without mounting the dialog/detail.
            uiState.wikiAutofocus.value = false
            inputStarts = 0
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("smiley_selected_tab").assertIsFocused()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle {
            assertFalse(uiState.wikiAutofocus.value)
            assertEquals(0, inputStarts)
        }
    }

    private fun consultationRestoration(onUiState: (SmileyPickerUiState) -> Unit = {}): StateRestorationTester {
        lateinit var inputMode: InputModeManager
        var state by mutableStateOf(SmileyPickerState.Open(
            query = "jap", openingIntent = SmileyPickerOpenIntent.CONSULTATION,
        ))
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                InterceptPlatformTextInput(PlatformTextInputInterceptor { request, next ->
                    inputStarts++
                    next.startInputMethod(request)
                }) {
                    RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                        inputMode = LocalInputModeManager.current
                        val uiState = rememberSmileyPickerUiState(state)
                        onUiState(uiState)
                        Column {
                            SmileyPickerGridPage(state, { state = state.copy(query = it) }, {},
                                SmileyPickerLayoutSpec.Current, uiState)
                        }
                    }
                }
            }
            compose.runOnIdle { assertEquals(InputMode.Touch, inputMode.inputMode) }
        }
    }

    @Test
    fun `stale Wiki text is discarded even when the field is composed after restoration`() {
        // Plain inputs change only when the saved tree is recreated, as with a new controller.
        var query = "jap"
        var recreated = false
        var revealed by mutableStateOf(false)
        var field: MutableState<TextFieldValue>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            field = if (!recreated || revealed) rememberSmileyWikiFieldValue(query) else null
        }
        compose.runOnIdle {
            assertEquals("jap", requireNotNull(field).value.text)
            query = ""
            recreated = true
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertNull(field)
            revealed = true
        }
        compose.runOnIdle { assertEquals(TextFieldValue("", TextRange(0)), requireNotNull(field).value) }
    }

    @Test
    fun `mismatched restored Wiki text uses the controller query with the caret at the end`() {
        var query = "jap"
        lateinit var field: MutableState<TextFieldValue>
        val restoration = StateRestorationTester(compose)
        restoration.setContent { field = rememberSmileyWikiFieldValue(query) }
        compose.runOnIdle {
            field.value = TextFieldValue("jap", TextRange(1))
            query = "autre"
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(TextFieldValue("autre", TextRange(5)), field.value) }
    }

    @Test
    fun `matching restored Wiki text preserves the user selection`() {
        lateinit var field: MutableState<TextFieldValue>
        val selected = TextFieldValue("jap", TextRange(0, 2))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { field = rememberSmileyWikiFieldValue("jap") }
        compose.runOnIdle { field.value = selected }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(selected, field.value) }
    }

    @Test
    fun `lagging query echo cannot overwrite a newer edit during the same composition`() {
        var query by mutableStateOf("jap")
        lateinit var field: MutableState<TextFieldValue>
        compose.setContent { field = rememberSmileyWikiFieldValue(query) }
        val latest = TextFieldValue("japon", TextRange(5))
        compose.runOnIdle {
            field.value = latest
            query = "japo"
        }
        compose.runOnIdle { assertEquals(latest, field.value) }
    }

    @Test
    fun `feedback produced during a detail visit remains eligible after restoration`() {
        val smiley = BUILTIN_HFR_SMILEYS.first()
        var state by mutableStateOf(SmileyPickerState.Open(
            favoriteFeedback = SmileyFavoriteFeedback.LocalRemoved, favoriteFeedbackGeneration = 3L,
            favoriteFeedbackToken = smiley.token, favoriteFeedbackEmission = 3L, detailFeedbackEmissionAtEntry = 3L,
        ))
        var feedback: SmileyFavoriteFeedback? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { feedback = rememberSmileyDetailFeedback(smiley, state) }
        compose.runOnIdle {
            assertNull(feedback)
            state = state.copy(
                favoriteFeedback = SmileyFavoriteFeedback.LocalAdded,
                favoriteFeedbackGeneration = 4L, favoriteFeedbackEmission = 4L,
            )
        }
        compose.runOnIdle { assertEquals(SmileyFavoriteFeedback.LocalAdded, feedback) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(SmileyFavoriteFeedback.LocalAdded, feedback) }
    }

    @Test
    fun `restoring a different detail discards the previous smiley entry generation`() {
        var smiley = BUILTIN_HFR_SMILEYS.first()
        var state = SmileyPickerState.Open(
            favoriteFeedbackGeneration = 3L, favoriteFeedbackEmission = 3L,
            favoriteFeedbackToken = smiley.token, detailFeedbackEmissionAtEntry = 3L,
        )
        var feedback: SmileyFavoriteFeedback? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { feedback = rememberSmileyDetailFeedback(smiley, state) }
        compose.runOnIdle {
            // A completion for the previous token remains ineligible after recreation.
            smiley = BUILTIN_HFR_SMILEYS[1]
            state = state.copy(
                favoriteFeedback = SmileyFavoriteFeedback.LocalAdded,
                favoriteFeedbackGeneration = 4L, favoriteFeedbackEmission = 4L,
            )
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(feedback) }
    }

    @Test
    fun `consumed overlay stays expired after restoration and a new generation can display`() {
        var generation by mutableStateOf(4L)
        lateinit var expired: MutableState<Boolean>
        val restoration = StateRestorationTester(compose)
        restoration.setContent { expired = rememberSmileyPickerOverlayExpired(generation) }
        compose.runOnIdle { expired.value = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(expired.value)
            generation++
        }
        compose.runOnIdle { assertFalse(expired.value) }
    }

    @Test
    fun `restored expiration does not consume feedback from another generation`() {
        var generation = 4L
        lateinit var expired: MutableState<Boolean>
        val restoration = StateRestorationTester(compose)
        restoration.setContent { expired = rememberSmileyPickerOverlayExpired(generation) }
        compose.runOnIdle {
            expired.value = true
            generation++
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertFalse(expired.value) }
    }

    @Test
    fun `pending generation stays unconsumed after restoration for a late result on the grid`() {
        lateinit var expired: MutableState<Boolean>
        val restoration = StateRestorationTester(compose)
        restoration.setContent { expired = rememberSmileyPickerOverlayExpired(4L) }
        compose.runOnIdle { assertFalse(expired.value) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertFalse(expired.value) }
    }
}
