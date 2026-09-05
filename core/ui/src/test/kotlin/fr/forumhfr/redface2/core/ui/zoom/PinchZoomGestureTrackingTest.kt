package fr.forumhfr.redface2.core.ui.zoom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only arbitration checks; Main/Initial dispatch itself is covered by TopicZoomGestureTest. */
class PinchZoomGestureTrackingTest {

    @Test
    fun `a child consumed drag cannot engage the pan even past slop`() {
        val tracking = MagnifierGestureTracking(Offset.Zero)

        assertFalse(tracking.zoomedPanReady(frame(DRAG, consumed = true), TOUCH_SLOP))
        assertFalse(tracking.zoomedPanReady(frame(DRAG * 2f), TOUCH_SLOP))
    }

    @Test
    fun `consumption below slop reserves the entire gesture for the child`() {
        val tracking = MagnifierGestureTracking(Offset.Zero)

        assertFalse(tracking.zoomedPanReady(frame(Offset(1f, 0f), consumed = true), TOUCH_SLOP))
        assertFalse(tracking.zoomedPanReady(frame(DRAG), TOUCH_SLOP))
        assertFalse(tracking.zoomedPanReady(frame(-DRAG), TOUCH_SLOP))
    }

    @Test
    fun `unconsumed movement retains the original slop in both directions`() {
        listOf(Offset(1f, 0f), Offset(0f, 1f)).forEach { axis ->
            val tracking = MagnifierGestureTracking(Offset.Zero)
            assertFalse(tracking.zoomedPanReady(frame(axis * TOUCH_SLOP), TOUCH_SLOP))
            assertTrue(tracking.zoomedPanReady(frame(axis * (TOUCH_SLOP + 1f)), TOUCH_SLOP))
            assertTrue(tracking.zoomedPanReady(frame(Offset.Zero), TOUCH_SLOP))
        }
    }

    @Test
    fun `a new gesture can pan after the previous gesture belonged to a child`() {
        val previous = MagnifierGestureTracking(Offset.Zero)
        assertFalse(previous.zoomedPanReady(frame(DRAG, consumed = true), TOUCH_SLOP))

        val next = MagnifierGestureTracking(Offset.Zero)
        assertTrue(next.zoomedPanReady(frame(DRAG), TOUCH_SLOP))
    }

    private fun frame(position: Offset, consumed: Boolean = false): PointerEvent {
        val change = PointerInputChange(
            id = PointerId(0),
            uptimeMillis = FRAME_TIME_MILLIS,
            position = position,
            pressed = true,
            previousUptimeMillis = 0L,
            previousPosition = Offset.Zero,
            previousPressed = true,
            isInitiallyConsumed = consumed,
        )
        return PointerEvent(listOf(change))
    }

    private companion object {
        const val TOUCH_SLOP = 24f
        const val FRAME_TIME_MILLIS = 16L
        val DRAG = Offset(80f, 0f)
    }
}
