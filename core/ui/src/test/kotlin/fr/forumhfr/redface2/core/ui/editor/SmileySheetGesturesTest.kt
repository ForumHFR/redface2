package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmileySheetGesturesTest {
    @Test
    fun `long press latch covers moves up and trailing fling until the next gesture`() {
        val gestures = SmileySheetGestures()
        assertFalse(gestures.pointerEvent(pressed = true, newDown = true))
        gestures.openingDetail()
        assertTrue(gestures.pointerEvent(pressed = true, newDown = false))
        assertTrue(gestures.pointerEvent(pressed = false, newDown = false))
        assertTrue("post-fling can run after UP", gestures.blocked)
        assertFalse(gestures.pointerEvent(pressed = true, newDown = true))
        assertFalse(gestures.blocked)
    }

    @Test
    fun `an additional finger does not release the long press latch`() {
        val gestures = SmileySheetGestures()
        gestures.pointerEvent(pressed = true, newDown = true)
        gestures.openingDetail()
        // The node sees previousPressed on the first finger: this is not a first DOWN.
        assertTrue(gestures.pointerEvent(pressed = true, newDown = false))
        assertTrue(gestures.pointerEvent(pressed = true, newDown = false))
        assertTrue(gestures.pointerEvent(pressed = false, newDown = false))
    }

    @Test
    fun `accessibility opening without pointers never blocks and cancellation forgets pressed pointers`() {
        val gestures = SmileySheetGestures()
        gestures.openingDetail()
        assertFalse(gestures.blocked)
        gestures.pointerEvent(pressed = true, newDown = true)
        gestures.openingDetail()
        assertTrue(gestures.blocked)
        gestures.cancelled()
        assertTrue("a detector cancellation must not expose the remainder of the physical gesture", gestures.blocked)
        assertTrue(gestures.pointerEvent(pressed = true, newDown = false))
        gestures.cancelled()
        gestures.openingDetail()
        assertFalse(gestures.blocked)
    }

    @Test
    fun `a genuine new down after cancellation releases the latch`() {
        val gestures = SmileySheetGestures()
        gestures.pointerEvent(pressed = true, newDown = true)
        gestures.openingDetail()
        gestures.cancelled()
        assertTrue(gestures.pointerEvent(pressed = true, newDown = false))
        assertFalse(gestures.pointerEvent(pressed = true, newDown = true))
    }

    @Test
    fun `node consumes the held pointer and its release but allows the next down`() {
        val gestures = SmileySheetGestures()
        val node = SmileySheetGesturesNode(gestures)
        val down = change(pressed = true, previousPressed = false)
        dispatch(node, down)
        assertFalse(down.isConsumed)
        gestures.openingDetail()

        val move = change(pressed = true, previousPressed = true)
        dispatch(node, move)
        assertTrue(move.isConsumed)
        val up = change(pressed = false, previousPressed = true)
        dispatch(node, up)
        assertTrue(up.isConsumed)
        assertTrue("post-fling can run after UP", gestures.blocked)

        val nextDown = change(pressed = true, previousPressed = false)
        dispatch(node, nextDown)
        assertFalse(nextDown.isConsumed)
        assertFalse(gestures.blocked)
    }

    @Test
    fun `hover and wheel after release remain unconsumed without releasing the fling latch`() {
        val gestures = SmileySheetGestures()
        val node = SmileySheetGesturesNode(gestures)
        dispatch(node, change(pressed = true, previousPressed = false))
        gestures.openingDetail()
        dispatch(node, change(pressed = false, previousPressed = true))

        listOf(Offset.Zero, Offset(0f, 1f)).forEach { scrollDelta ->
            val mouse = change(
                pressed = false,
                previousPressed = false,
                type = PointerType.Mouse,
                scrollDelta = scrollDelta,
            )
            dispatch(node, mouse)
            assertFalse("hover/wheel must reach the detail", mouse.isConsumed)
            assertTrue("hover/wheel must not release the post-fling latch", gestures.blocked)
        }
    }

    private fun dispatch(node: SmileySheetGesturesNode, change: PointerInputChange) {
        node.onPointerEvent(PointerEvent(listOf(change)), PointerEventPass.Initial, IntSize(100, 100))
    }

    private fun change(
        pressed: Boolean,
        previousPressed: Boolean,
        type: PointerType = PointerType.Touch,
        scrollDelta: Offset = Offset.Zero,
    ) = PointerInputChange(
        id = PointerId(0),
        uptimeMillis = 16L,
        position = Offset.Zero,
        pressed = pressed,
        previousUptimeMillis = 0L,
        previousPosition = Offset.Zero,
        previousPressed = previousPressed,
        isInitiallyConsumed = false,
        type = type,
        scrollDelta = scrollDelta,
    )
}
