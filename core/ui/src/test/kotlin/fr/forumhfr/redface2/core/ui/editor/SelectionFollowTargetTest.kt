package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #447 point 2 / #1263 — which selection edge the externally scrolled viewport must follow.
 *
 * Pure decision table: the Compose wiring (rect + `bringIntoView`) is pinned by
 * [BbcodeTextFieldSelectionFollowTest]; this class pins the rule itself, including the cases the
 * previous implementation got wrong (it always revealed `selection.end`, so extending a selection
 * BACKWARDS — the start handle — never moved the viewport).
 */
class SelectionFollowTargetTest {

    @Test
    fun `first reveal follows the caret without lookahead`() {
        val target = selectionFollowTarget(previous = null, current = TextRange(42))

        assertEquals(SelectionFollowTarget(42, SelectionFollowDirection.NONE), target)
    }

    @Test
    fun `a collapsed caret is followed without lookahead`() {
        val target = selectionFollowTarget(previous = TextRange(10), current = TextRange(42))

        assertEquals(SelectionFollowTarget(42, SelectionFollowDirection.NONE), target)
    }

    @Test
    fun `extending the end forward follows the end and looks ahead`() {
        val target = selectionFollowTarget(
            previous = TextRange(10, 20),
            current = TextRange(10, 60),
        )

        assertEquals(SelectionFollowTarget(60, SelectionFollowDirection.FORWARD), target)
    }

    @Test
    fun `shrinking the end backwards follows the end upwards`() {
        val target = selectionFollowTarget(
            previous = TextRange(10, 60),
            current = TextRange(10, 20),
        )

        assertEquals(SelectionFollowTarget(20, SelectionFollowDirection.BACKWARD), target)
    }

    @Test
    fun `extending the start backwards follows the start, not the fixed end`() {
        // The #1263 case: dragging the START handle up. `end` never moves, so revealing `end`
        // (the old behaviour) left the viewport still while the selection grew off-screen.
        val target = selectionFollowTarget(
            previous = TextRange(50, 60),
            current = TextRange(5, 60),
        )

        assertEquals(SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD), target)
    }

    @Test
    fun `shrinking the start forward follows the start downwards`() {
        val target = selectionFollowTarget(
            previous = TextRange(5, 60),
            current = TextRange(50, 60),
        )

        assertEquals(SelectionFollowTarget(50, SelectionFollowDirection.FORWARD), target)
    }

    @Test
    fun `a reversed selection follows its focus end`() {
        // Dragging from an anchor UPWARDS produces `end` < `start` (the focus is `end`).
        val target = selectionFollowTarget(
            previous = TextRange(60, 50),
            current = TextRange(60, 5),
        )

        assertEquals(SelectionFollowTarget(5, SelectionFollowDirection.BACKWARD), target)
    }

    @Test
    fun `a brand new selection reveals its end without overshooting`() {
        // Both edges moved (long-press word, select-all, programmatic selection): no direction is
        // being dragged, so no lookahead — reveal the focus end and stop there.
        val target = selectionFollowTarget(
            previous = TextRange(10),
            current = TextRange(200, 260),
        )

        assertEquals(SelectionFollowTarget(260, SelectionFollowDirection.NONE), target)
    }

    @Test
    fun `an unchanged selection is re-revealed without lookahead`() {
        // #880 re-trigger (IME inset settles, new text layout): same selection, same target.
        val target = selectionFollowTarget(
            previous = TextRange(10, 60),
            current = TextRange(10, 60),
        )

        assertEquals(SelectionFollowTarget(60, SelectionFollowDirection.NONE), target)
    }
}
