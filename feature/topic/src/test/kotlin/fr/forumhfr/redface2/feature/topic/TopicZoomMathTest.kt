package fr.forumhfr.redface2.feature.topic

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicZoomMathTest {

    @Test
    fun `pinch keeps the content under the centroid fixed on screen`() {
        val rubberedScale = clampScaleDuringPinch(raw = 3f)
        val cases = listOf(
            PinchCase(
                label = "zoom in from rest",
                scaleOld = 1f,
                panXOld = 0f,
                centroidX = 400f,
                centroidY = 800f,
                zoomFactor = 1.5f,
                widthPx = 1080f,
            ),
            PinchCase(
                label = "zoom in with existing pan",
                scaleOld = 1.75f,
                panXOld = -300f,
                centroidX = 500f,
                centroidY = 300f,
                zoomFactor = 1.2f,
                widthPx = 1000f,
            ),
            PinchCase(
                label = "zoom out with existing pan",
                scaleOld = 2.2f,
                panXOld = -600f,
                centroidX = 400f,
                centroidY = 950f,
                zoomFactor = 0.8f,
                widthPx = 1000f,
            ),
            PinchCase(
                label = "zoom in from rubber band",
                scaleOld = rubberedScale,
                panXOld = -500f,
                centroidX = 400f,
                centroidY = 700f,
                zoomFactor = 1.1f,
                widthPx = 1000f,
            ),
            PinchCase(
                label = "zoom out from rubber band",
                scaleOld = rubberedScale,
                panXOld = -500f,
                centroidX = 400f,
                centroidY = 700f,
                zoomFactor = 0.9f,
                widthPx = 1000f,
            ),
        )

        cases.forEach(::assertScreenInvariant)
    }

    @Test
    fun `scroll sign follows LazyListState viewport pixels`() {
        val zoomIn = pinchStep(
            current = ZoomTransform(scale = 1.5f, panX = -200f),
            centroidX = 400f,
            centroidY = 800f,
            zoomFactor = 1.2f,
            widthPx = 1000f,
        )
        val zoomOut = pinchStep(
            current = ZoomTransform(scale = 1.8f, panX = -300f),
            centroidX = 400f,
            centroidY = 800f,
            zoomFactor = 0.8f,
            widthPx = 1000f,
        )

        assertTrue("zoom-in must request positive scrollBy", zoomIn.scrollByPx > 0f)
        assertTrue("zoom-out must request negative scrollBy", zoomOut.scrollByPx < 0f)
    }

    @Test
    fun `centroid correction clamps pan at both edges as best effort`() {
        val widthPx = 1000f
        val rightEdge = pinchStep(
            current = ZoomTransform(scale = 2f, panX = 0f),
            centroidX = 400f,
            centroidY = 600f,
            zoomFactor = 0.75f,
            widthPx = widthPx,
        )
        val leftEdge = pinchStep(
            current = ZoomTransform(scale = 2f, panX = panXRange(scale = 2f, widthPx = widthPx).start),
            centroidX = 600f,
            centroidY = 600f,
            zoomFactor = 0.75f,
            widthPx = widthPx,
        )

        assertEquals(0f, rightEdge.panXNew, TOLERANCE)
        assertEquals(panXRange(leftEdge.scaleNew, widthPx).start, leftEdge.panXNew, TOLERANCE)

        // Once an X edge is reached, exposing no blank strip wins over centroid invariance.
        val rightContentX = 400f / 2f
        val leftContentX = (600f - panXRange(2f, widthPx).start) / 2f
        val rightScreenX = rightContentX * rightEdge.scaleNew + rightEdge.panXNew
        val leftScreenX = leftContentX * leftEdge.scaleNew + leftEdge.panXNew
        assertTrue(abs(rightScreenX - 400f) > TOLERANCE)
        assertTrue(abs(leftScreenX - 600f) > TOLERANCE)

        // X clamping is independent from the real-list correction, so Y remains anchored here.
        assertYInvariant(centroidY = 600f, scaleOld = 2f, step = rightEdge)
        assertYInvariant(centroidY = 600f, scaleOld = 2f, step = leftEdge)
    }

    @Test
    fun `one finger pan and pan range clamp at both bounds`() {
        val range = panXRange(scale = 2f, widthPx = 500f)

        assertEquals(-500f, range.start, TOLERANCE)
        assertEquals(0f, range.endInclusive, TOLERANCE)
        assertEquals(0f, panStep(-250f, deltaX = 400f, scale = 2f, widthPx = 500f), TOLERANCE)
        assertEquals(-500f, panStep(-250f, deltaX = -400f, scale = 2f, widthPx = 500f), TOLERANCE)
        assertEquals(-150f, panStep(-250f, deltaX = 100f, scale = 2f, widthPx = 500f), TOLERANCE)
    }

    @Test
    fun `pinch scale has a hard floor and a continuous monotone rubber band`() {
        assertEquals(MIN_ZOOM_SCALE, clampScaleDuringPinch(raw = -2f), TOLERANCE)
        assertEquals(MIN_ZOOM_SCALE, clampScaleDuringPinch(raw = 0.8f), TOLERANCE)
        assertEquals(2.25f, clampScaleDuringPinch(raw = 2.25f), TOLERANCE)

        val atJoin = clampScaleDuringPinch(raw = MAX_ZOOM_SCALE)
        val justPastJoin = clampScaleDuringPinch(raw = MAX_ZOOM_SCALE + 0.0004f)
        assertTrue(justPastJoin > atJoin)
        assertEquals(atJoin, justPastJoin, 0.00011f)

        val rawScales = listOf(2.5f, 2.6f, 3f, 4f, 10f)
        val displayScales = rawScales.map(::clampScaleDuringPinch)
        displayScales.zipWithNext().forEach { (first, second) ->
            assertTrue("rubber band must stay monotone: $first then $second", second > first)
        }
        assertTrue(clampScaleDuringPinch(raw = 3f) > MAX_ZOOM_SCALE)
        assertTrue(clampScaleDuringPinch(raw = 3f) < 3f)
    }

    @Test
    fun `release snaps near one caps overshoot and preserves settled scales`() {
        assertEquals(1f, resolveScaleOnRelease(gestureScale = 0.8f), TOLERANCE)
        assertEquals(1f, resolveScaleOnRelease(gestureScale = 1.03f), TOLERANCE)
        assertEquals(1.0301f, resolveScaleOnRelease(gestureScale = 1.0301f), TOLERANCE)
        assertEquals(1.8f, resolveScaleOnRelease(gestureScale = 1.8f), TOLERANCE)
        assertEquals(2.5f, resolveScaleOnRelease(gestureScale = 2.5f), TOLERANCE)
        assertEquals(2.5f, resolveScaleOnRelease(gestureScale = 2.8f), TOLERANCE)
    }

    @Test
    fun `incremental factors remain directional in the rubber band`() {
        val scaleOld = clampScaleDuringPinch(raw = 3f)
        val rubbered = ZoomTransform(scale = scaleOld, panX = -500f)
        val noOp = pinchStep(rubbered, 400f, 700f, 1f, 1000f)
        val zoomIn = pinchStep(rubbered, 400f, 700f, 1.01f, 1000f)
        val zoomOut = pinchStep(rubbered, 400f, 700f, 0.99f, 1000f)

        assertEquals(scaleOld, noOp.scaleNew, 0f)
        assertEquals(-500f, noOp.panXNew, 0f)
        assertEquals(0f, noOp.scrollByPx, 0f)
        assertTrue("rubbered zoom-in must still increase scale", zoomIn.scaleNew > scaleOld)
        assertTrue("rubbered zoom-out must still decrease scale", zoomOut.scaleNew < scaleOld)
    }

    @Test
    fun `floor width zero and top centroid keep their independent degeneracies`() {
        val floor = pinchStep(
            current = ZoomTransform(scale = 1f, panX = -100f),
            centroidX = 400f,
            centroidY = 600f,
            zoomFactor = 0.5f,
            widthPx = 1000f,
        )
        assertEquals(1f, floor.scaleNew, TOLERANCE)
        assertEquals(0f, floor.panXNew, TOLERANCE)
        assertEquals(0f, floor.scrollByPx, TOLERANCE)

        val zeroWidth = pinchStep(ZoomTransform(1.5f, -100f), 200f, 600f, 1.2f, widthPx = 0f)
        assertEquals(0f, panXRange(scale = 2f, widthPx = 0f).start, TOLERANCE)
        assertEquals(0f, zeroWidth.panXNew, TOLERANCE)
        assertEquals(0f, panStep(-100f, 50f, scale = 2f, widthPx = 0f), TOLERANCE)
        assertTrue("zero width must not disable scale", zeroWidth.scaleNew > 1.5f)
        assertTrue("zero width must not disable Y correction", zeroWidth.scrollByPx > 0f)

        val topCentroid = pinchStep(ZoomTransform(1.5f, -100f), 200f, 0f, 1.2f, widthPx = 1000f)
        assertEquals(0f, topCentroid.scrollByPx, TOLERANCE)
    }

    @Test
    fun `invalid non gesture inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { clampScaleDuringPinch(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { resolveScaleOnRelease(Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { panXRange(scale = 0.9f, widthPx = 1000f) }
        assertThrows(IllegalArgumentException::class.java) { panXRange(scale = 2f, widthPx = -1f) }
        assertThrows(IllegalArgumentException::class.java) {
            pinchStep(ZoomTransform(1.5f, 0f), 100f, 100f, zoomFactor = 0f, widthPx = 1000f)
        }
    }

    private fun assertScreenInvariant(case: PinchCase) {
        val contentViewportX = (case.centroidX - case.panXOld) / case.scaleOld
        val contentViewportY = case.centroidY / case.scaleOld
        val step = pinchStep(
            current = ZoomTransform(scale = case.scaleOld, panX = case.panXOld),
            centroidX = case.centroidX,
            centroidY = case.centroidY,
            zoomFactor = case.zoomFactor,
            widthPx = case.widthPx,
        )

        val screenXAfter = contentViewportX * step.scaleNew + step.panXNew
        // Positive scrollBy moves the content up before the draw layer applies its new scale.
        val screenYAfter = (contentViewportY - step.scrollByPx) * step.scaleNew
        assertEquals("${case.label} X", case.centroidX, screenXAfter, TOLERANCE)
        assertEquals("${case.label} Y", case.centroidY, screenYAfter, TOLERANCE)
    }

    private fun assertYInvariant(centroidY: Float, scaleOld: Float, step: PinchStep) {
        val contentViewportY = centroidY / scaleOld
        val screenYAfter = (contentViewportY - step.scrollByPx) * step.scaleNew
        assertEquals(centroidY, screenYAfter, TOLERANCE)
    }

    private data class PinchCase(
        val label: String,
        val scaleOld: Float,
        val panXOld: Float,
        val centroidX: Float,
        val centroidY: Float,
        val zoomFactor: Float,
        val widthPx: Float,
    )

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
