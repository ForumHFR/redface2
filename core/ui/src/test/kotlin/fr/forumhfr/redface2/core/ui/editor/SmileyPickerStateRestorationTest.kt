package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * StateRestorationTester replaces the host registry, not ModalBottomSheet's dialog registry.
 * Exercise the production state helpers in the reachable registry; this does not recreate the Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerStateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

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
        ))
        var feedback: SmileyFavoriteFeedback? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { feedback = rememberSmileyDetailFeedback(smiley, state) }
        compose.runOnIdle {
            assertNull(feedback)
            state = state.copy(favoriteFeedback = SmileyFavoriteFeedback.LocalAdded, favoriteFeedbackGeneration = 4L)
        }
        compose.runOnIdle { assertEquals(SmileyFavoriteFeedback.LocalAdded, feedback) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(SmileyFavoriteFeedback.LocalAdded, feedback) }
    }

    @Test
    fun `restoring a different detail discards the previous smiley entry generation`() {
        var smiley = BUILTIN_HFR_SMILEYS.first()
        var state = SmileyPickerState.Open(favoriteFeedbackGeneration = 3L)
        var feedback: SmileyFavoriteFeedback? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { feedback = rememberSmileyDetailFeedback(smiley, state) }
        compose.runOnIdle {
            // Change only on recreation, challenging the saved token rather than live inputs.
            smiley = BUILTIN_HFR_SMILEYS[1]
            state = state.copy(favoriteFeedback = SmileyFavoriteFeedback.LocalAdded, favoriteFeedbackGeneration = 4L)
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
