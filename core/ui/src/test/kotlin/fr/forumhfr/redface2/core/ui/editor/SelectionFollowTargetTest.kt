package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #447 point 2 / #1263 — which selection edge the externally scrolled viewport must follow.
 *
 * Pure decision table: the Compose wiring (rect + `bringIntoView`) is pinned by
 * [BbcodeTextFieldSelectionFollowTest]; this class pins the rule itself, including the cases the
 * previous implementations got wrong — revealing `selection.end` always (extending a selection
 * BACKWARDS, i.e. the start handle, never moved the viewport), then reading « one edge moved » as a
 * drag (« select all » from the end caret jumped to the top, gate Sol).
 */
class SelectionFollowTargetTest {

    private companion object {
        /** Text length used by every case — only its value relative to the offsets matters. */
        const val TEXT_LENGTH = 400
    }

    private fun target(previous: TextRange?, current: TextRange) =
        selectionFollowTarget(previous, current, TEXT_LENGTH)

    @Test
    fun `first reveal follows the caret without lookahead`() {
        assertEquals(
            SelectionFollowTarget(42, SelectionFollowDirection.NONE),
            target(previous = null, current = TextRange(42)),
        )
    }

    @Test
    fun `a collapsed caret is followed without lookahead`() {
        assertEquals(
            SelectionFollowTarget(42, SelectionFollowDirection.NONE),
            target(previous = TextRange(10), current = TextRange(42)),
        )
    }

    @Test
    fun `extending the end forward follows the end and looks ahead`() {
        assertEquals(
            SelectionFollowTarget(60, SelectionFollowDirection.FORWARD),
            target(previous = TextRange(10, 20), current = TextRange(10, 60)),
        )
    }

    @Test
    fun `shrinking the end backwards follows the end upwards`() {
        assertEquals(
            SelectionFollowTarget(20, SelectionFollowDirection.BACKWARD),
            target(previous = TextRange(10, 60), current = TextRange(10, 20)),
        )
    }

    @Test
    fun `extending the start backwards follows the start, not the fixed end`() {
        // The #1263 case: dragging the START handle up out of an EXISTING selection. `end` never
        // moves, so revealing `end` (the first implementation) left the viewport still while the
        // selection grew off-screen.
        assertEquals(
            SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD),
            target(previous = TextRange(50, 60), current = TextRange(5, 60)),
        )
    }

    @Test
    fun `shrinking the start forward follows the start downwards`() {
        assertEquals(
            SelectionFollowTarget(50, SelectionFollowDirection.FORWARD),
            target(previous = TextRange(5, 60), current = TextRange(50, 60)),
        )
    }

    @Test
    fun `a reversed selection follows its focus end`() {
        // Dragging from an anchor UPWARDS produces `end` < `start` (the focus is `end`).
        assertEquals(
            SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD),
            target(previous = TextRange(60, 50), current = TextRange(60, 5)),
        )
    }

    @Test
    fun `a brand new selection reveals its end without overshooting`() {
        // Both edges moved (long-press word, programmatic selection): no direction is being
        // dragged, so no lookahead — reveal the focus end and stop there.
        assertEquals(
            SelectionFollowTarget(260, SelectionFollowDirection.NONE),
            target(previous = TextRange(10), current = TextRange(200, 260)),
        )
    }

    @Test
    fun `a selection appearing from a caret is new, even when only one edge moved`() {
        // gate Sol : a caret has no handle to grab, so this is never a drag.
        assertEquals(
            SelectionFollowTarget(25, SelectionFollowDirection.NONE),
            target(previous = TextRange(10), current = TextRange(10, 25)),
        )
    }

    @Test
    fun `select all from the end caret reveals the end, it does not jump to the top`() {
        // gate Sol, the reported regression : `(n,n) → (0,n)` moves only `start`.
        assertEquals(
            SelectionFollowTarget(TEXT_LENGTH, SelectionFollowDirection.NONE),
            target(previous = TextRange(TEXT_LENGTH), current = TextRange(0, TEXT_LENGTH)),
        )
    }

    @Test
    fun `select all from the start caret reveals the end`() {
        assertEquals(
            SelectionFollowTarget(TEXT_LENGTH, SelectionFollowDirection.NONE),
            target(previous = TextRange.Zero, current = TextRange(0, TEXT_LENGTH)),
        )
    }

    @Test
    fun `select all from a selection already ending at the last character reveals the end`() {
        // gate Sol : `(k,n) → (0,n)` also moves only `start`, and is also not a handle drag.
        assertEquals(
            SelectionFollowTarget(TEXT_LENGTH, SelectionFollowDirection.NONE),
            target(previous = TextRange(120, TEXT_LENGTH), current = TextRange(0, TEXT_LENGTH)),
        )
    }

    @Test
    fun `select all from a partial selection reveals the end`() {
        assertEquals(
            SelectionFollowTarget(TEXT_LENGTH, SelectionFollowDirection.NONE),
            target(previous = TextRange(120, 180), current = TextRange(0, TEXT_LENGTH)),
        )
    }

    @Test
    fun `an empty field never reads a whole-text selection`() {
        // Guard on the `textLength > 0` clause: TextRange.Zero on an empty text is a caret.
        assertEquals(
            SelectionFollowTarget(0, SelectionFollowDirection.NONE),
            selectionFollowTarget(TextRange.Zero, TextRange.Zero, textLength = 0),
        )
    }

    @Test
    fun `dragging the start of a selection that reaches the last character stays a drag`() {
        // Not whole-text (start stays > 0), so this IS a handle drag and keeps its lookahead.
        assertEquals(
            SelectionFollowTarget(20, SelectionFollowDirection.BACKWARD),
            target(previous = TextRange(120, TEXT_LENGTH), current = TextRange(20, TEXT_LENGTH)),
        )
    }

    @Test
    fun `an unchanged selection is re-revealed without lookahead`() {
        // #880 re-trigger (IME inset settles, new text layout): same selection, same target.
        assertEquals(
            SelectionFollowTarget(60, SelectionFollowDirection.NONE),
            target(previous = TextRange(10, 60), current = TextRange(10, 60)),
        )
    }
}
