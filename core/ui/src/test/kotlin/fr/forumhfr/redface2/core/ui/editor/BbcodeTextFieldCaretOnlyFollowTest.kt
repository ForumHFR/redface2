package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #275/#410 — state contract of the bounded field's caret nudge.
 *
 * `TextFieldScrollerPosition.update` only coerces its offset after the cursor rectangle changes.
 * These tests pin the required probe/restoration sequence and its guards. Pixel visibility during
 * a real IME animation and selection-handle dragging are platform behaviours covered on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldCaretOnlyFollowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val TEXT = "0123456789"
    }

    @Test
    fun `size change nudges an end caret backward then restores it`() {
        assertNudge(TextFieldValue(TEXT, TextRange(TEXT.length)), probe = TEXT.length - 1)
    }

    @Test
    fun `size change nudges a middle caret backward then restores it`() {
        assertNudge(TextFieldValue(TEXT, TextRange(5)), probe = 4)
    }

    @Test
    fun `size change nudges a start caret forward then restores it`() {
        assertNudge(TextFieldValue(TEXT, TextRange.Zero), probe = 1)
    }

    @Test
    fun `single-character text can probe forward from its start`() {
        assertNudge(TextFieldValue("x", TextRange.Zero), probe = 1)
    }

    @Test
    fun `focus gain nudges a collapsed caret without another size change`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = setFieldContent(initial)

        focusField()

        assertNudgeEmissions(fixture.emissions, initial, probe = 5)
    }

    @Test
    fun `size change while unfocused does not nudge`() {
        val fixture = setFieldContent(TextFieldValue(TEXT, TextRange(6)))

        resize(fixture)

        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `size change never touches a forward extended selection`() {
        assertNoFocusedResizeNudge(TextFieldValue(TEXT, TextRange(2, 8)))
    }

    @Test
    fun `size change never touches a reversed extended selection`() {
        assertNoFocusedResizeNudge(TextFieldValue(TEXT, TextRange(8, 2)))
    }

    @Test
    fun `size change does not nudge empty text`() {
        assertNoFocusedResizeNudge(TextFieldValue("", TextRange.Zero))
    }

    @Test
    fun `typing during the nudge window wins over caret restoration`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)
        startResizeNudge(fixture)
        assertEquals(TextRange(5), fixture.emissions.single().selection)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("X")
        val afterTyping = fixture.value.value
        composeTestRule.mainClock.advanceTimeBy(CARET_NUDGE_MS + 1)
        composeTestRule.waitForIdle()

        assertEquals(afterTyping, fixture.value.value)
        assertEquals(afterTyping, fixture.emissions.last())
        assertEquals(2, fixture.emissions.size)
    }

    @Test
    fun `a parent value change during the nudge window cancels restoration`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)
        startResizeNudge(fixture)
        val replacement = TextFieldValue("replacement", TextRange(3))

        composeTestRule.runOnIdle { fixture.value.value = replacement }
        composeTestRule.mainClock.advanceTimeBy(CARET_NUDGE_MS + 1)
        composeTestRule.waitForIdle()

        assertEquals(replacement, fixture.value.value)
        assertEquals(1, fixture.emissions.size)
    }

    @Test
    fun `rapid size changes are debounced into one nudge`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.runOnIdle { fixture.height.value = 360.dp }
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS / 2)
        composeTestRule.runOnIdle { fixture.height.value = 320.dp }
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS / 2)
        composeTestRule.runOnIdle { fixture.height.value = 280.dp }
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS + 1)
        composeTestRule.mainClock.advanceTimeBy(CARET_NUDGE_MS + 1)
        composeTestRule.waitForIdle()

        assertNudgeEmissions(fixture.emissions, initial, probe = 5)
    }

    private fun assertNudge(initial: TextFieldValue, probe: Int) {
        val fixture = focusedFixture(initial)

        resize(fixture)

        assertNudgeEmissions(fixture.emissions, initial, probe)
    }

    private fun assertNoFocusedResizeNudge(initial: TextFieldValue) {
        val fixture = focusedFixture(initial)

        resize(fixture)

        assertTrue(fixture.emissions.isEmpty())
        assertEquals(initial, fixture.value.value)
    }

    private fun focusedFixture(initial: TextFieldValue): FieldFixture {
        val fixture = setFieldContent(initial)
        focusField()
        composeTestRule.runOnIdle { fixture.emissions.clear() }
        return fixture
    }

    private fun setFieldContent(initial: TextFieldValue): FieldFixture {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var height: MutableState<Dp>
        val emissions = mutableListOf<TextFieldValue>()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    value = remember { mutableStateOf(initial) }
                    height = remember { mutableStateOf(400.dp) }
                    Box(Modifier.size(320.dp, height.value)) {
                        BbcodeTextField(
                            value = value.value,
                            onValueChange = { changedValue ->
                                emissions += changedValue
                                value.value = changedValue
                            },
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        return FieldFixture(value = value, height = height, emissions = emissions)
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private fun resize(fixture: FieldFixture) {
        composeTestRule.runOnIdle { fixture.height.value = 240.dp }
        composeTestRule.waitForIdle()
    }

    private fun startResizeNudge(fixture: FieldFixture) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle { fixture.height.value = 240.dp }
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS + 1)
        composeTestRule.waitForIdle()
    }

    private fun assertNudgeEmissions(
        emissions: List<TextFieldValue>,
        initial: TextFieldValue,
        probe: Int,
    ) {
        assertEquals(2, emissions.size)
        assertEquals(initial.copy(selection = TextRange(probe)), emissions[0])
        assertEquals(initial, emissions[1])
    }

    private data class FieldFixture(
        val value: MutableState<TextFieldValue>,
        val height: MutableState<Dp>,
        val emissions: MutableList<TextFieldValue>,
    )
}
