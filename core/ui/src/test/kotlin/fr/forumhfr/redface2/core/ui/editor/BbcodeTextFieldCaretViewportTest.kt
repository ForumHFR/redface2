package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1450 ED-14 — only measured edits arm reveal; initial content and hydration retain host scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldCaretViewportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var state: TextFieldState
    private lateinit var scrollState: ScrollState
    private lateinit var fieldHeight: MutableState<Dp>
    private val resetKey = mutableIntStateOf(0)
    private var latestTextLayout: TextLayoutResult? = null

    @Test
    fun initialMountWithOffscreenCaretDoesNotScroll() {
        setFieldContent()
        assertInitialCaretIsHidden()
        val initialValue = fieldValue()

        shrinkViewport(180.dp)

        assertEquals(0, scrollState.value)
        assertFalse(isCaretVisible())
        assertEquals(initialValue, fieldValue())
    }

    @Test
    fun asyncServerHydrationEstablishesBaselineWithoutScrolling() {
        setFieldContent(initialText = "")
        assertEquals(0, scrollState.value)
        replaceContentWithoutReveal((1..200).joinToString("\n") { "server line $it" })
        assertInitialCaretIsHidden()
        val hydratedValue = fieldValue()

        shrinkViewport(180.dp)

        assertEquals(0, scrollState.value)
        assertFalse(isCaretVisible())
        assertEquals(hydratedValue, fieldValue())
        // A later insertion, after hydration was measured, must still arm reveal.
        insertAtEnd()
        assertTrue(isCaretVisible())
    }

    @Test
    fun draftRestorationDisarmsRevealAndPreservesHostScroll() {
        setFieldContent()
        insertAtEnd()
        assertTrue(isCaretVisible())
        composeTestRule.runOnIdle { scrollState.dispatchRawDelta(80f - scrollState.value) }
        composeTestRule.waitForIdle()
        assertEquals(80, scrollState.value)

        replaceContentWithoutReveal((1..200).joinToString("\n") { "restored line $it" })
        assertEquals(80, scrollState.value)
        assertFalse(isCaretVisible())
        // Model PR 1: the host restores its saved offset after the hydrated layout settles.
        composeTestRule.runOnIdle { scrollState.dispatchRawDelta(120f - scrollState.value) }
        composeTestRule.waitForIdle()
        assertEquals(120, scrollState.value)
        assertFalse(isCaretVisible())
        val restoredValue = fieldValue()

        shrinkViewport(180.dp)

        assertEquals(120, scrollState.value)
        assertFalse(isCaretVisible())
        assertEquals(restoredValue, fieldValue())
    }

    @Test
    fun selectionOnlyEditAfterMeasurementRevealsCaret() {
        setFieldContent()
        assertInitialCaretIsHidden()
        val originalText = state.text.toString()

        composeTestRule.runOnIdle { state.edit { selection = TextRange(length / 2) } }
        composeTestRule.waitForIdle()

        assertTrue(isCaretVisible())
        assertTrue(scrollState.value > 0)
        assertEquals(originalText, state.text.toString())
    }

    @Test
    fun resetWithoutBufferChangeDisarmsViewportFollowing() {
        setFieldContent()
        insertAtEnd()
        assertTrue(isCaretVisible())
        val previousScroll = scrollState.value
        val previousValue = fieldValue()

        composeTestRule.runOnIdle { resetKey.intValue++ }
        composeTestRule.waitForIdle()
        shrinkViewport(180.dp)

        assertEquals(previousScroll, scrollState.value)
        assertFalse(isCaretVisible())
        assertEquals(previousValue, fieldValue())
    }

    @Test
    fun unfocusedInsertionStaysVisibleWhenOnlyViewportShrinks() {
        // Start offscreen: the insertion, not initial layout, must arm unfocused following.
        assertInsertionFollowsViewport(readOnly = false)
        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun readOnlyInsertionStaysVisibleWhenOnlyViewportShrinks() {
        // The same measured, hidden baseline must work when BTF2 cannot edit the field.
        assertInsertionFollowsViewport(readOnly = true)
    }

    @Test
    fun manualScrollAfterUnfocusedInsertionIsPreservedWhenViewportShrinks() {
        // Reveal a real edit before testing that manual scrolling suspends unfocused following.
        assertManualScrollIsPreserved(readOnly = false)
    }

    @Test
    fun manualScrollAfterReadOnlyInsertionIsPreservedWhenViewportShrinks() {
        // Read-only following must also be armed by an edit before the manual scroll.
        assertManualScrollIsPreserved(readOnly = true)
    }

    @Test
    fun disabledRevealDoesNotFollowInsertionOrViewportChanges() {
        setFieldContent(revealCaretOnChange = false)
        // Check the hidden initial caret separately from the explicitly disabled insertion.
        assertInitialCaretIsHidden()
        insertAtEnd()
        assertEquals(0, scrollState.value)
        assertFalse(isCaretVisible())

        // Put the caret in view manually; disabling reveal must also disable viewport following.
        composeTestRule.runOnIdle { scrollState.dispatchRawDelta(scrollState.maxValue.toFloat()) }
        composeTestRule.waitForIdle()
        assertTrue(isCaretVisible())
        val previousScroll = scrollState.value
        val previousValue = fieldValue()

        shrinkViewport(180.dp)

        assertEquals(previousScroll, scrollState.value)
        assertFalse(isCaretVisible())
        assertEquals(previousValue, fieldValue())
    }

    @Test
    fun focusedFieldLeavesSelectionAndViewportFollowingToBtf2() {
        setFieldContent()
        // Assert the inert mount before requesting focus; focused scrolling belongs to BTF2.
        assertInitialCaretIsHidden()
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
        insertAtEnd()
        assertTrue(isCaretVisible())

        // Changing only the start makes BTF2 follow that endpoint. A second reveal of selection.end
        // would fight it and jump back to the bottom, both here and on the subsequent resize.
        composeTestRule.runOnIdle { state.edit { selection = TextRange(0, selection.end) } }
        composeTestRule.waitForIdle()
        assertTrue(isCaretVisible(offset = 0))
        assertFalse(isCaretVisible())
        val previousValue = fieldValue()

        shrinkViewport(180.dp)

        assertTrue(isCaretVisible(offset = 0))
        assertFalse(isCaretVisible())
        assertEquals(previousValue, fieldValue())
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun assertInsertionFollowsViewport(readOnly: Boolean) {
        setFieldContent(readOnly = readOnly)
        assertInitialCaretIsHidden()
        insertAtEnd()
        assertTrue(isCaretVisible())
        val previousValue = fieldValue()

        // Several measurements model an IME/panel animation with no text or selection update.
        listOf(300.dp, 240.dp, 180.dp).forEach { height ->
            val previousScroll = scrollState.value
            shrinkViewport(height)
            assertTrue("the revealed caret must remain inside the measured viewport", isCaretVisible())
            assertTrue(scrollState.value > previousScroll)
            assertEquals(previousValue, fieldValue())
        }
    }

    private fun assertManualScrollIsPreserved(readOnly: Boolean) {
        setFieldContent(readOnly = readOnly)
        assertInitialCaretIsHidden()
        insertAtEnd()
        assertTrue(isCaretVisible())
        val previousValue = fieldValue()

        // Change only the scroll offset, as a user scrolling away from the insertion would do.
        composeTestRule.runOnIdle { scrollState.dispatchRawDelta(-scrollState.value.toFloat()) }
        composeTestRule.waitForIdle()
        assertEquals(0, scrollState.value)
        assertFalse(isCaretVisible())

        listOf(300.dp, 180.dp).forEach { height ->
            shrinkViewport(height)
            assertEquals("a resize must preserve the user's scroll position", 0, scrollState.value)
            assertFalse(isCaretVisible())
            assertEquals(previousValue, fieldValue())
        }
    }

    private fun insertAtEnd() {
        composeTestRule.runOnIdle {
            state.edit {
                insert(length, "\ninserted")
                selection = TextRange(length)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun replaceContentWithoutReveal(text: String) {
        composeTestRule.runOnIdle {
            Snapshot.withMutableSnapshot {
                resetKey.intValue++
                state.edit {
                    replace(0, length, text)
                    selection = TextRange(length)
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(text, requireNotNull(latestTextLayout).layoutInput.text.text)
        assertEquals(TextRange(text.length), state.selection)
    }

    private fun assertInitialCaretIsHidden() {
        assertEquals(0, scrollState.value)
        assertFalse(isCaretVisible())
    }

    private fun shrinkViewport(height: Dp) {
        val previousViewport = scrollState.viewportSize
        composeTestRule.runOnIdle { fieldHeight.value = height }
        composeTestRule.waitForIdle()
        assertTrue("the text viewport must actually shrink", scrollState.viewportSize < previousViewport)
    }

    private fun fieldValue() = TextFieldValue(state.text.toString(), state.selection)

    private fun isCaretVisible(offset: Int = state.selection.end): Boolean {
        val caret = requireNotNull(latestTextLayout).getCursorRect(offset)
        assertTrue("the text viewport must be measured", scrollState.viewportSize > 0)
        return caret.top >= scrollState.value - 1f &&
            caret.bottom <= scrollState.value + scrollState.viewportSize + 1f
    }

    private fun setFieldContent(
        readOnly: Boolean = false,
        revealCaretOnChange: Boolean = true,
        initialText: String = (1..200).joinToString("\n") { "line $it" },
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    state = rememberTextFieldState(
                        initialText = initialText,
                        initialSelection = TextRange(initialText.length),
                    )
                    scrollState = rememberScrollState()
                    fieldHeight = remember { mutableStateOf(400.dp) }
                    Box(Modifier.size(320.dp, fieldHeight.value)) {
                        BbcodeTextField(
                            state = state,
                            label = "Message",
                            modifier = Modifier.fillMaxSize(),
                            readOnly = readOnly,
                            scrollState = scrollState,
                            onTextLayout = { getResult -> latestTextLayout = getResult() },
                            showLabel = false,
                            showKeyboardOnFocus = false,
                            revealCaretOnChange = revealCaretOnChange,
                            caretRevealResetKey = { resetKey.intValue },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
