package fr.forumhfr.redface2.feature.topic

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicZoomMathTest {

    @Test
    fun `pinch keeps the content under the centroid fixed on screen`() {
        val rubberedScale = clampScaleDuringPinch(raw = 3.5f)
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

        val rawScales = listOf(3f, 3.1f, 3.5f, 4f, 10f)
        val displayScales = rawScales.map(::clampScaleDuringPinch)
        displayScales.zipWithNext().forEach { (first, second) ->
            assertTrue("rubber band must stay monotone: $first then $second", second > first)
        }
        assertTrue(clampScaleDuringPinch(raw = 3.5f) > MAX_ZOOM_SCALE)
        assertTrue(clampScaleDuringPinch(raw = 3.5f) < 3.5f)
    }

    @Test
    fun `release snaps near one caps overshoot and preserves settled scales`() {
        assertEquals(1f, resolveScaleOnRelease(gestureScale = 0.8f), TOLERANCE)
        assertEquals(1f, resolveScaleOnRelease(gestureScale = 1.03f), TOLERANCE)
        assertEquals(1.0301f, resolveScaleOnRelease(gestureScale = 1.0301f), TOLERANCE)
        assertEquals(1.8f, resolveScaleOnRelease(gestureScale = 1.8f), TOLERANCE)
        assertEquals(3f, resolveScaleOnRelease(gestureScale = 3f), TOLERANCE)
        assertEquals(3f, resolveScaleOnRelease(gestureScale = 3.4f), TOLERANCE)
    }

    @Test
    fun `incremental factors remain directional in the rubber band`() {
        val scaleOld = clampScaleDuringPinch(raw = 3.5f)
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

    @Test
    fun `vertical range spans the scaled viewport and collapses at one`() {
        val zoomed = panYRange(scale = 2f, viewportHeightPx = 1000f)
        val collapsed = panYRange(scale = 1f, viewportHeightPx = 1000f)

        assertEquals(-1000f, zoomed.start, TOLERANCE)
        assertEquals(0f, zoomed.endInclusive, TOLERANCE)
        assertEquals(0f, collapsed.start, TOLERANCE)
        assertEquals(0f, collapsed.endInclusive, TOLERANCE)
    }

    @Test
    fun `vertical anchor survives full partial and saturated list scroll`() {
        val anchorY = 600f
        val scaleOld = 2f
        val scaleNew = 2.5f
        val panYOld = -400f
        val panYAfterScale = panYOld.coerceIn(panYRange(scaleNew, viewportHeightPx = 1000f))
        val contentViewportY = (anchorY - panYOld) / scaleOld
        val driftScreenPx = anchoredVerticalDrift(
            anchorY = anchorY,
            panYOld = panYOld,
            scaleOld = scaleOld,
            scaleNew = scaleNew,
            panYNew = panYAfterScale,
        )
        val requestViewportPx = upwardListRequestViewportPx(driftScreenPx, scaleNew)
        val cases = listOf(
            100f to -400f,
            60f to -500f,
            0f to -650f,
        )

        assertEquals(250f, driftScreenPx, TOLERANCE)
        assertEquals(100f, requestViewportPx, TOLERANCE)
        cases.forEach { (consumedViewportPx, expectedPanY) ->
            val panYNew = upwardPanYAfterScroll(
                deltaScreenPx = driftScreenPx,
                consumedViewportPx = consumedViewportPx,
                panYOld = panYAfterScale,
                scale = scaleNew,
                viewportHeightPx = 1000f,
            )
            val screenYAfter = (contentViewportY - consumedViewportPx) * scaleNew + panYNew

            assertEquals(expectedPanY, panYNew, TOLERANCE)
            assertEquals(anchorY, screenYAfter, TOLERANCE)
        }
    }

    @Test
    fun `vertical anchor is best effort when pan and list are both saturated`() {
        val anchorY = 800f
        val scaleOld = 2f
        val scaleNew = 1.5f
        val panYOld = -1000f
        val panYAfterScale = panYOld.coerceIn(panYRange(scaleNew, viewportHeightPx = 1000f))
        val contentViewportY = (anchorY - panYOld) / scaleOld
        val driftScreenPx = anchoredVerticalDrift(
            anchorY = anchorY,
            panYOld = panYOld,
            scaleOld = scaleOld,
            scaleNew = scaleNew,
            panYNew = panYAfterScale,
        )
        val panYFinal = upwardPanYAfterScroll(
            deltaScreenPx = driftScreenPx,
            consumedViewportPx = 0f,
            panYOld = panYAfterScale,
            scale = scaleNew,
            viewportHeightPx = 1000f,
        )
        val screenYAfter = contentViewportY * scaleNew + panYFinal

        assertEquals(50f, driftScreenPx, TOLERANCE)
        assertEquals(-500f, panYFinal, TOLERANCE)
        assertEquals(850f, screenYAfter, TOLERANCE)
        assertTrue(abs(screenYAfter - anchorY) > TOLERANCE)
    }

    @Test
    fun `upward remainder uses screen pan only as far as its lower bound`() {
        val requestViewportPx = upwardListRequestViewportPx(deltaScreenPx = 800f, scale = 2f)
        val panYNew = upwardPanYAfterScroll(
            deltaScreenPx = 800f,
            consumedViewportPx = 100f,
            panYOld = -700f,
            scale = 2f,
            viewportHeightPx = 1000f,
        )

        assertEquals(400f, requestViewportPx, TOLERANCE)
        assertEquals(-1000f, panYNew, TOLERANCE)
    }

    @Test
    fun `downward movement unwinds pan before requesting backward list scroll`() {
        val absorbedByPan = downwardDistribution(
            deltaScreenPx = -400f,
            panYOld = -600f,
            scale = 2f,
            viewportHeightPx = 1000f,
        )
        val withListRemainder = downwardDistribution(
            deltaScreenPx = -800f,
            panYOld = -600f,
            scale = 2f,
            viewportHeightPx = 1000f,
        )

        assertEquals(-200f, absorbedByPan.panYNew, TOLERANCE)
        assertEquals(0f, absorbedByPan.listRequestViewportPx, TOLERANCE)
        assertEquals(0f, withListRemainder.panYNew, TOLERANCE)
        assertEquals(-100f, withListRemainder.listRequestViewportPx, TOLERANCE)
    }

    @Test
    fun `downward movement repairs pan outside both bounds`() {
        val belowRange = downwardDistribution(
            deltaScreenPx = -100f,
            panYOld = -1200f,
            scale = 2f,
            viewportHeightPx = 1000f,
        )
        val aboveRange = downwardDistribution(
            deltaScreenPx = -100f,
            panYOld = 100f,
            scale = 2f,
            viewportHeightPx = 1000f,
        )

        assertEquals(-1000f, belowRange.panYNew, TOLERANCE)
        assertEquals(0f, belowRange.listRequestViewportPx, TOLERANCE)
        assertEquals(0f, aboveRange.panYNew, TOLERANCE)
        assertEquals(-100f, aboveRange.listRequestViewportPx, TOLERANCE)
    }

    @Test
    fun `invalid vertical inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            panYRange(scale = 0.9f, viewportHeightPx = 1000f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            panYRange(scale = 2f, viewportHeightPx = -1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            upwardListRequestViewportPx(deltaScreenPx = -1f, scale = 2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            upwardPanYAfterScroll(10f, Float.NaN, 0f, 2f, 1000f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            downwardDistribution(0f, panYOld = 0f, scale = 2f, viewportHeightPx = 1000f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            anchoredVerticalDrift(100f, 0f, scaleOld = 0f, scaleNew = 2f, panYNew = 0f)
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
