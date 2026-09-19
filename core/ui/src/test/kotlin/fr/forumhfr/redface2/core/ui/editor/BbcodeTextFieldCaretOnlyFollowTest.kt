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
import androidx.compose.ui.test.SemanticsMatcher
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
 * #275/#410 — rendering contract of the bounded field's caret probe.
 *
 * `TextFieldScrollerPosition.update` only coerces its offset after the displayed cursor rectangle
 * changes. These tests pin the local probe/restoration sequence and prove that it never leaks into
 * the controlled callback. Pixel visibility during a real IME animation and selection-handle
 * dragging remain platform behaviours covered on device.
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
    fun `size change probes an end caret backward then restores it locally`() {
        assertLocalProbe(TextFieldValue(TEXT, TextRange(TEXT.length)), probe = TEXT.length - 1)
    }

    @Test
    fun `size change probes a middle caret backward then restores it locally`() {
        assertLocalProbe(TextFieldValue(TEXT, TextRange(5)), probe = 4)
    }

    @Test
    fun `size change probes a start caret forward then restores it locally`() {
        assertLocalProbe(TextFieldValue(TEXT, TextRange.Zero), probe = 1)
    }

    @Test
    fun `single-character text can probe forward from its start`() {
        assertLocalProbe(TextFieldValue("x", TextRange.Zero), probe = 1)
    }

    @Test
    fun `emoji probe moves by a code point rather than half a surrogate pair`() {
        val text = "a\uD83D\uDE00"

        assertLocalProbe(TextFieldValue(text, TextRange(text.length)), probe = 1)
    }

    @Test
    fun `focus gain alone does not probe the caret`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = setFieldContent(initial)

        focusField()

        assertEquals(initial.selection, displayedSelection())
        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `size change while unfocused does not probe`() {
        val fixture = setFieldContent(TextFieldValue(TEXT, TextRange(6)))

        resizeAndSettle(fixture)

        assertEquals(fixture.value.value.selection, displayedSelection())
        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `size change never touches a forward extended selection`() {
        assertNoFocusedResizeProbe(TextFieldValue(TEXT, TextRange(2, 8)))
    }

    @Test
    fun `size change never touches a reversed extended selection`() {
        assertNoFocusedResizeProbe(TextFieldValue(TEXT, TextRange(8, 2)))
    }

    @Test
    fun `size change does not probe empty text`() {
        assertNoFocusedResizeProbe(TextFieldValue("", TextRange.Zero))
    }

    @Test
    fun `size change does not probe an active IME composition`() {
        assertNoFocusedResizeProbe(
            TextFieldValue(
                text = TEXT,
                selection = TextRange(6),
                composition = TextRange(3, 6),
            ),
        )
    }

    @Test
    fun `typing during the probe is rebased onto the real caret`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)
        startResizeProbe(fixture)
        assertEquals(TextRange(5), displayedSelection())

        composeTestRule.onNode(hasSetTextAction()).performTextInput("X")
        composeTestRule.waitForIdle()

        val expected = TextFieldValue("012345X6789", TextRange(7))
        assertEquals(expected.text, fixture.value.value.text)
        assertEquals(expected.selection, fixture.value.value.selection)
        assertEquals(listOf(expected), fixture.emissions)
        finishProbeFrames()
        assertEquals(expected.selection, displayedSelection())
    }

    @Test
    fun `a parent value change during the probe cancels local restoration`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)
        startResizeProbe(fixture)
        val replacement = TextFieldValue("replacement", TextRange(3))

        composeTestRule.runOnIdle { fixture.value.value = replacement }
        composeTestRule.waitForIdle()
        finishProbeFrames()

        assertEquals(replacement, fixture.value.value)
        assertEquals(replacement.selection, displayedSelection())
        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `rapid size changes are debounced until the final measurement`() {
        val initial = TextFieldValue(TEXT, TextRange(6))
        val fixture = focusedFixture(initial)

        composeTestRule.runOnIdle { fixture.height.value = 360.dp }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS / 2)
        composeTestRule.runOnIdle { fixture.height.value = 320.dp }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS / 2)
        assertEquals(initial.selection, displayedSelection())
        composeTestRule.runOnIdle { fixture.height.value = 280.dp }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS + 1)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        assertEquals(TextRange(5), displayedSelection())
        assertTrue(fixture.emissions.isEmpty())
        finishProbeFrames()
        assertEquals(initial.selection, displayedSelection())
    }

    private fun assertLocalProbe(initial: TextFieldValue, probe: Int) {
        val fixture = focusedFixture(initial)

        startResizeProbe(fixture)

        assertEquals(TextRange(probe), displayedSelection())
        assertEquals(initial, fixture.value.value)
        assertTrue("the rendering probe must not reach the ViewModel callback", fixture.emissions.isEmpty())

        finishProbeFrames()

        assertEquals(initial.selection, displayedSelection())
        assertEquals(initial, fixture.value.value)
        assertTrue(fixture.emissions.isEmpty())
    }

    private fun assertNoFocusedResizeProbe(initial: TextFieldValue) {
        val fixture = focusedFixture(initial)

        resizeAndSettle(fixture)

        assertEquals(initial.selection, displayedSelection())
        assertEquals(initial, fixture.value.value)
        assertTrue(fixture.emissions.isEmpty())
    }

    private fun focusedFixture(initial: TextFieldValue): FieldFixture {
        val fixture = setFieldContent(initial)
        focusField()
        return fixture
    }

    private fun setFieldContent(initial: TextFieldValue): FieldFixture {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var height: MutableState<Dp>
        val emissions = mutableListOf<TextFieldValue>()
        composeTestRule.mainClock.autoAdvance = false
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
        // Settle the first measurement while unfocused. Focus is only a guard and must not replay it.
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS + 1)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
        return FieldFixture(value = value, height = height, emissions = emissions)
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    private fun startResizeProbe(fixture: FieldFixture) {
        resizeAndSettle(fixture)
        composeTestRule.waitForIdle()
    }

    private fun resizeAndSettle(fixture: FieldFixture) {
        composeTestRule.runOnIdle { fixture.height.value = 240.dp }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(FIELD_SIZE_SETTLE_MS + 1)
        // Render the probe once; the effect then waits for its second frame.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    private fun finishProbeFrames() {
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    private fun displayedSelection(): TextRange = composeTestRule
        .onNode(
            SemanticsMatcher.keyIsDefined(BbcodeDisplayedSelectionKey),
            useUnmergedTree = true,
        )
        .fetchSemanticsNode()
        .config[BbcodeDisplayedSelectionKey]

    private data class FieldFixture(
        val value: MutableState<TextFieldValue>,
        val height: MutableState<Dp>,
        val emissions: MutableList<TextFieldValue>,
    )
}
