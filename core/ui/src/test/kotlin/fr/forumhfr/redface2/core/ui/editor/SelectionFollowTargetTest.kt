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
 * BACKWARDS, i.e. the start handle, never moved the viewport), reading « one edge moved » as a drag
 * (« select all » from the end caret jumped to the top, gate Sol passe 1), then reading any
 * whole-text selection as « select all » (a START handle dragged to offset 0 jumped to the BOTTOM,
 * gate Sol passe 2).
 */
class SelectionFollowTargetTest {

    private companion object {
        val TEXT = "x".repeat(400)
        val LEN = TEXT.length
    }

    private fun state(
        selection: TextRange?,
        movingEdge: SelectionEdge = SelectionEdge.NONE,
        text: String = TEXT,
    ) = SelectionFollowState(
        text = selection?.let { text },
        selection = selection,
        movingEdge = movingEdge,
    )

    private fun target(previous: SelectionFollowState, current: TextRange) =
        selectionFollowTarget(previous, TEXT, current)

    @Test
    fun `first reveal follows the caret without lookahead`() {
        assertEquals(
            SelectionFollowTarget(42, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(SelectionFollowState(), TextRange(42)),
        )
    }

    @Test
    fun `a collapsed caret is followed without lookahead`() {
        assertEquals(
            SelectionFollowTarget(42, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(10)), TextRange(42)),
        )
    }

    @Test
    fun `extending the end forward follows the end and looks ahead`() {
        assertEquals(
            SelectionFollowTarget(60, SelectionFollowDirection.FORWARD, SelectionEdge.END),
            target(state(TextRange(10, 20)), TextRange(10, 60)),
        )
    }

    @Test
    fun `shrinking the end backwards follows the end upwards`() {
        assertEquals(
            SelectionFollowTarget(20, SelectionFollowDirection.BACKWARD, SelectionEdge.END),
            target(state(TextRange(10, 60)), TextRange(10, 20)),
        )
    }

    @Test
    fun `extending the start backwards follows the start, not the fixed end`() {
        // The #1263 case: dragging the START handle up out of an EXISTING selection. `end` never
        // moves, so revealing `end` (the first implementation) left the viewport still while the
        // selection grew off-screen.
        assertEquals(
            SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD, SelectionEdge.START),
            target(state(TextRange(50, 60)), TextRange(5, 60)),
        )
    }

    @Test
    fun `shrinking the start forward follows the start downwards`() {
        assertEquals(
            SelectionFollowTarget(50, SelectionFollowDirection.FORWARD, SelectionEdge.START),
            target(state(TextRange(5, 60)), TextRange(50, 60)),
        )
    }

    @Test
    fun `a reversed selection follows its focus end`() {
        // Dragging from an anchor UPWARDS produces `end` < `start` (the focus is `end`).
        assertEquals(
            SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD, SelectionEdge.END),
            target(state(TextRange(60, 50)), TextRange(60, 5)),
        )
    }

    @Test
    fun `a brand new selection reveals its end without overshooting`() {
        // Both edges moved (long-press word, programmatic selection): no drag, no lookahead.
        assertEquals(
            SelectionFollowTarget(360, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(200, 260)), TextRange(300, 360)),
        )
    }

    @Test
    fun `a selection appearing from a caret is new, even when only one edge moved`() {
        // gate Sol passe 1 : a caret has no handle to grab, so this is never a drag.
        assertEquals(
            SelectionFollowTarget(25, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(10)), TextRange(10, 25)),
        )
    }

    @Test
    fun `select all from the end caret reveals the end, it does not jump to the top`() {
        assertEquals(
            SelectionFollowTarget(LEN, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(LEN)), TextRange(0, LEN)),
        )
    }

    @Test
    fun `select all from the start caret reveals the end`() {
        assertEquals(
            SelectionFollowTarget(LEN, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange.Zero), TextRange(0, LEN)),
        )
    }

    @Test
    fun `select all from a selection already ending at the last character reveals the end`() {
        // `(k,n) → (0,n)` also moves only `start`, and with NO drag under way it is « select all ».
        assertEquals(
            SelectionFollowTarget(LEN, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(120, LEN)), TextRange(0, LEN)),
        )
    }

    @Test
    fun `select all from a partial selection reveals the end`() {
        assertEquals(
            SelectionFollowTarget(LEN, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(120, 180)), TextRange(0, LEN)),
        )
    }

    @Test
    fun `a start drag already under way keeps following start onto the whole text`() {
        // gate Sol passe 2 : `(5,n) → (0,n)` is the END of a START drag, not « select all ».
        // Reading it as « select all » revealed `end` and threw the view back to the bottom.
        assertEquals(
            SelectionFollowTarget(0, SelectionFollowDirection.BACKWARD, SelectionEdge.START),
            target(state(TextRange(5, LEN), movingEdge = SelectionEdge.START), TextRange(0, LEN)),
        )
    }

    @Test
    fun `the whole start drag sequence keeps following start to the top`() {
        // gate Sol passe 2, end to end : `(k,n) → (5,n) → (0,n)` fed through its own state.
        val first = target(state(TextRange(120, LEN)), TextRange(5, LEN))
        assertEquals(
            SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD, SelectionEdge.START),
            first,
        )

        val second = target(
            state(TextRange(5, LEN), movingEdge = first.movingEdge),
            TextRange(0, LEN),
        )
        assertEquals(
            SelectionFollowTarget(0, SelectionFollowDirection.BACKWARD, SelectionEdge.START),
            second,
        )
    }

    @Test
    fun `an end drag under way does not turn a select all into an end drag`() {
        // Continuity is per EDGE: a drag on END does not licence a whole-text jump on START.
        assertEquals(
            SelectionFollowTarget(LEN, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            target(state(TextRange(120, LEN), movingEdge = SelectionEdge.END), TextRange(0, LEN)),
        )
    }

    @Test
    fun `dragging the start of a selection that reaches the last character stays a drag`() {
        // Not whole-text (start stays > 0), so this IS a handle drag and keeps its lookahead.
        assertEquals(
            SelectionFollowTarget(20, SelectionFollowDirection.BACKWARD, SelectionEdge.START),
            target(state(TextRange(120, LEN)), TextRange(20, LEN)),
        )
    }

    @Test
    fun `an empty field never reads a whole-text selection`() {
        assertEquals(
            SelectionFollowTarget(0, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            selectionFollowTarget(
                SelectionFollowState("", TextRange.Zero),
                currentText = "",
                current = TextRange.Zero,
            ),
        )
    }

    @Test
    fun `editing the text resets the drag continuity`() {
        // Offsets are renumbered by an edit: whatever moved, it is not the same drag.
        assertEquals(
            SelectionFollowTarget(4, SelectionFollowDirection.NONE, SelectionEdge.NONE),
            selectionFollowTarget(
                SelectionFollowState("abc", TextRange(1, 3), SelectionEdge.END),
                currentText = "abcd",
                current = TextRange(1, 4),
            ),
        )
    }

    @Test
    fun `an unchanged selection is re-revealed without lookahead and keeps the drag`() {
        // #880 re-trigger (IME inset settles, new text layout): same selection, same target — and
        // the drag in progress must survive it, the next move is still the same handle.
        assertEquals(
            SelectionFollowTarget(60, SelectionFollowDirection.NONE, SelectionEdge.START),
            target(state(TextRange(10, 60), movingEdge = SelectionEdge.START), TextRange(10, 60)),
        )
    }
}
