package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
 * #447 hotfix — the externally scrolled [BbcodeTextField] follows collapsed carets only.
 *
 * The full-screen post editor and MP reply use [ScrollMode.FILL_VIEWPORT]; `TopicFormScreen` and
 * MP creation use the default field inside the caller's `verticalScroll` ([ScrollMode.OUTER]). Both
 * paths keep caret following from #449/#880, while this component's requester stays inert for every
 * extended selection so dragging a legacy `TextFieldValue` handle cannot scroll its coordinate
 * system away from the finger.
 *
 * Robolectric changes selections as state because the platform handles live in a separate `Popup`.
 * The tests read the real [SemanticsProperties.VerticalScrollAxisRange] of each external scroller.
 * They deliberately do not use a toolbar wrap to test text changes: legacy `CoreTextField` owns a
 * separate `BringIntoViewRequester` and reveals its focused selection end when focused text changes,
 * independently of this component's requester. [BbcodeFormatterTest] pins the atomic extended
 * selection returned by a wrap; the relayout cases below isolate this component's follow effect by
 * changing the text layout without changing the text.
 *
 * OUTER assertions use the stabilized scroll position as their baseline: at xxhdpi the initial
 * collapsed-caret reveal can consume the floating label's 8 dp headroom (24 px) while remaining at
 * the start of the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BbcodeTextFieldCaretOnlyFollowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val OUTER_SCROLL_TAG = "outer_scroll"
        val LONG_TEXT = (1..200).joinToString("\n") { "line $it" }
        val INSERTED_TEXT = (1..80).joinToString("\n", postfix = "\n") { "inserted line $it" }
        val SELECTION_NEAR_END = LONG_TEXT.length - 300
    }

    @Test
    fun `fillViewport reveals a collapsed caret outside the viewport`() =
        assertCollapsedCaretIsRevealed(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll reveals a collapsed caret outside the viewport`() =
        assertCollapsedCaretIsRevealed(ScrollMode.OUTER)

    @Test
    fun `fillViewport stays still when the top selection edge moves above the viewport`() =
        assertMovingTopBoundaryDoesNotScroll(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll stays still when the top selection edge moves above the viewport`() =
        assertMovingTopBoundaryDoesNotScroll(ScrollMode.OUTER)

    @Test
    fun `fillViewport stays still when the bottom selection edge moves below the viewport`() =
        assertMovingBottomBoundaryDoesNotScroll(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll stays still when the bottom selection edge moves below the viewport`() =
        assertMovingBottomBoundaryDoesNotScroll(ScrollMode.OUTER)

    @Test
    fun `fillViewport relayout does not follow an unchanged extended selection`() =
        assertRelayoutDoesNotFollowExtendedSelection(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll relayout does not follow an unchanged extended selection`() =
        assertRelayoutDoesNotFollowExtendedSelection(ScrollMode.OUTER)

    @Test
    fun `fillViewport select all from the final caret keeps the viewport still`() =
        assertSelectAllFromFinalCaretDoesNotScroll(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll select all from the final caret keeps the viewport still`() =
        assertSelectAllFromFinalCaretDoesNotScroll(ScrollMode.OUTER)

    @Test
    fun `fillViewport follows an insertion that moves a collapsed caret`() =
        assertCollapsedCaretInsertionIsFollowed(ScrollMode.FILL_VIEWPORT)

    @Test
    fun `outer scroll follows an insertion that moves a collapsed caret`() =
        assertCollapsedCaretInsertionIsFollowed(ScrollMode.OUTER)

    private fun assertCollapsedCaretIsRevealed(mode: ScrollMode) {
        val value = setContent(mode)
        focusField()
        val before = scrollValue(mode.tag)

        setSelection(value, TextRange(value.value.text.length))

        val after = scrollValue(mode.tag)
        val max = maxScrollValue(mode.tag)
        assertTrue(
            "the external viewport follows the final caret " +
                "(mode=$mode before=$before after=$after max=$max)",
            after > before && max > 0f && after >= max * 0.95f,
        )
    }

    private fun assertMovingTopBoundaryDoesNotScroll(mode: ScrollMode) {
        val value = setContent(mode)
        focusField()
        setSelection(value, TextRange(LONG_TEXT.length))
        setSelection(value, TextRange(SELECTION_NEAR_END, LONG_TEXT.length))
        val before = scrollValue(mode.tag)
        assertTrue("precondition: the viewport is away from the top", before > 0f)

        setSelection(value, TextRange(5, LONG_TEXT.length))

        assertViewportUnchanged(mode, before, "moving the top selection edge")
    }

    private fun assertMovingBottomBoundaryDoesNotScroll(mode: ScrollMode) {
        val value = setContent(mode)
        focusField()
        setSelection(value, TextRange(0, 5))
        val before = scrollValue(mode.tag)
        assertViewportNearStart(mode, before)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        assertViewportUnchanged(mode, before, "moving the bottom selection edge")
    }

    private fun assertRelayoutDoesNotFollowExtendedSelection(mode: ScrollMode) {
        val (value, width) = setResizableContent(mode)
        focusField()
        // Both selection edges stay near the start, then the viewport is parked at the opposite end.
        // A relayout that followed either edge would therefore produce an observable jump upwards.
        setSelection(value, TextRange(5, 20))
        scrollToEnd(mode.tag)
        val before = scrollValue(mode.tag)
        val max = maxScrollValue(mode.tag)
        assertTrue(
            "precondition: the viewport was manually scrolled away from both selection edges",
            max > 0f && before >= max * 0.9f,
        )

        relayout(width)

        assertViewportUnchanged(mode, before, "relayout with an unchanged extended selection")
    }

    private fun assertSelectAllFromFinalCaretDoesNotScroll(mode: ScrollMode) {
        val value = setContent(mode)
        focusField()
        setSelection(value, TextRange(LONG_TEXT.length))
        val before = scrollValue(mode.tag)
        assertTrue("precondition: the final caret moved the viewport", before > 0f)

        setSelection(value, TextRange(0, LONG_TEXT.length))

        assertViewportUnchanged(mode, before, "select all from the final caret")
    }

    private fun assertCollapsedCaretInsertionIsFollowed(mode: ScrollMode) {
        val value = setContent(mode)
        focusField()
        val before = scrollValue(mode.tag)
        assertViewportNearStart(mode, before)

        insertAtCaret(value, INSERTED_TEXT)

        val after = scrollValue(mode.tag)
        assertTrue(
            "the external viewport follows a collapsed caret after insertion " +
                "(mode=$mode before=$before after=$after)",
            after > before,
        )
    }

    private fun setContent(mode: ScrollMode): MutableState<TextFieldValue> {
        lateinit var value: MutableState<TextFieldValue>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        value = remember { mutableStateOf(longTextValue()) }
                        FieldHost(mode = mode, value = value)
                    }
                }
            }
        }
        return value
    }

    private fun setResizableContent(
        mode: ScrollMode,
    ): Pair<MutableState<TextFieldValue>, MutableState<Dp>> {
        lateinit var value: MutableState<TextFieldValue>
        lateinit var width: MutableState<Dp>
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    value = remember { mutableStateOf(longTextValue()) }
                    width = remember { mutableStateOf(320.dp) }
                    Box(Modifier.size(width.value, 400.dp)) {
                        FieldHost(mode = mode, value = value)
                    }
                }
            }
        }
        return value to width
    }

    @Composable
    private fun FieldHost(mode: ScrollMode, value: MutableState<TextFieldValue>) {
        when (mode) {
            ScrollMode.FILL_VIEWPORT -> BbcodeTextField(
                value = value.value,
                onValueChange = { value.value = it },
                label = "Message",
                modifier = Modifier.fillMaxSize(),
                fillViewport = true,
            )
            ScrollMode.OUTER -> Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(OUTER_SCROLL_TAG),
            ) {
                BbcodeTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    label = "Message",
                )
            }
        }
    }

    private fun scrollToEnd(tag: String) {
        composeTestRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 100_000f)
        }
        composeTestRule.waitForIdle()
    }

    private fun relayout(width: MutableState<Dp>) {
        composeTestRule.runOnIdle { width.value = 300.dp }
        composeTestRule.waitForIdle()
    }

    private fun focusField() {
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.waitForIdle()
    }

    private fun setSelection(value: MutableState<TextFieldValue>, selection: TextRange) {
        composeTestRule.runOnIdle { value.value = value.value.copy(selection = selection) }
        composeTestRule.waitForIdle()
    }

    private fun insertAtCaret(value: MutableState<TextFieldValue>, insertedText: String) {
        composeTestRule.runOnIdle {
            val caret = value.value.selection.end
            val text = value.value.text
            value.value = TextFieldValue(
                text = text.substring(0, caret) + insertedText + text.substring(caret),
                selection = TextRange(caret + insertedText.length),
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun assertViewportUnchanged(mode: ScrollMode, before: Float, action: String) {
        assertEquals(
            "$action must not move the external viewport in $mode",
            before,
            scrollValue(mode.tag),
            0f,
        )
    }

    private fun assertViewportNearStart(mode: ScrollMode, value: Float) {
        val max = maxScrollValue(mode.tag)
        assertTrue(
            "precondition: the initial viewport leaves room to scroll (mode=$mode value=$value max=$max)",
            max > 0f && value <= max * 0.05f,
        )
    }

    private fun longTextValue() = TextFieldValue(text = LONG_TEXT, selection = TextRange.Zero)

    private fun scrollValue(tag: String): Float = scrollRange(tag).value()

    private fun maxScrollValue(tag: String): Float = scrollRange(tag).maxValue()

    private fun scrollRange(tag: String) = composeTestRule
        .onNodeWithTag(tag)
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    private enum class ScrollMode(val tag: String) {
        FILL_VIEWPORT(BBCODE_FIELD_VIEWPORT_TAG),
        OUTER(OUTER_SCROLL_TAG),
    }
}
