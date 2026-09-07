package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #937 — instrumented gesture matrix of the #182 magnifier (the production tranche of the GO POC
 * #935). Each test is one line of the framing's minimal matrix; the device-level lines (P95,
 * sharpness, PTR wiring, reset chip end-to-end) are covered by the dated bench relevés on #935.
 *
 * Harness: a 360×600 dp Box carrying [topicMagnifier] around a plain LazyColumn — and, for the
 * coexistence lines, [topicPageSwipe] stacked the way TopicScreen stacks them. Geometry at
 * xxhdpi: 3 px/dp, box = 1080×1800 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopicZoomGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var zoomState: TopicZoomState
    private lateinit var listState: LazyListState
    private lateinit var swipeDragOffset: androidx.compose.runtime.MutableFloatState

    /** Mounts the magnifier harness; [withSwipe] stacks the page swipe under it like TopicScreen. */
    private fun mount(
        withSwipe: Boolean = false,
        onOpenPage: (Int) -> Unit = {},
        pageKey: () -> Any = { 1 },
    ) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = pageKey(), animationScope = scope)
            val dragOffset = remember { mutableFloatStateOf(0f) }.also { swipeDragOffset = it }
            val haptics = LocalHapticFeedback.current
            var modifier = Modifier
                .size(360.dp, 600.dp)
                .testTag("zoom")
                .topicMagnifier(zoomState, listState)
            if (withSwipe) {
                modifier = modifier.topicPageSwipe(
                    currentPage = 5,
                    totalPages = { 10 },
                    dragOffset = dragOffset,
                    handlers = TopicSwipeHandlers(
                        haptics = haptics,
                        onOpenPage = onOpenPage,
                        enabled = { !zoomState.zoomed },
                        leftGestureInsetPx = { 0 },
                        rightGestureInsetPx = { 0 },
                    ),
                )
            }
            Box(modifier) {
                LazyColumn(state = listState, userScrollEnabled = !zoomState.zoomed) {
                    items(count = 200) { i ->
                        Text("post $i", Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }
        }
    }

    private fun pinchOut(gapStartPx: Float = 300f, gapEndPx: Float = 750f, steps: Int = 12) {
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, gapStartPx / 2f))
            down(1, center + Offset(0f, gapStartPx / 2f))
            repeat(steps) { i ->
                val gap = gapStartPx + (gapEndPx - gapStartPx) * (i + 1) / steps
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(0)
            up(1)
        }
    }

    /** Public SelectionContainer does not expose its selection: spy on the same long-press drag contract. */
    private fun mountLongPressDragTarget(
        onDragStart: () -> Unit = {},
        onDragEnd: () -> Unit = {},
        onDragCancel: () -> Unit = {},
        onDrag: (Offset) -> Unit = {},
    ): Long {
        var longPressTimeoutMillis = 0L
        compose.setContent {
            val scope = rememberCoroutineScope()
            longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
            Box(
                Modifier.size(360.dp, 600.dp).testTag("zoom").topicMagnifier(zoomState, listState),
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = !zoomState.zoomed,
                    modifier = Modifier.topicZoomTransform(zoomState),
                ) {
                    item {
                        Text(
                            "one two three four five six seven eight",
                            Modifier.fillMaxWidth().height(600.dp).pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { onDragStart() },
                                    onDragEnd = onDragEnd,
                                    onDragCancel = onDragCancel,
                                ) { change, amount ->
                                    onDrag(amount)
                                    change.consume()
                                }
                            },
                        )
                    }
                    items(count = 20) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        compose.runOnIdle {
            zoomState.scale.floatValue = 2f
            // Leave room in both horizontal directions so clamping cannot hide a stolen drag.
            zoomState.panX.floatValue = -100f
        }
        compose.waitForIdle()
        return longPressTimeoutMillis
    }

    @Test
    fun `a two-finger pinch engages the scale and settles inside the bounds`() {
        mount()
        pinchOut()
        compose.waitForIdle()

        val scale = zoomState.scale.floatValue
        assertTrue("pinch must engage the zoom (scale=$scale)", scale > 1.5f)
        assertTrue("release must cap at the ceiling", scale <= MAX_ZOOM_SCALE + 0.001f)
        val panX = zoomState.panX.floatValue
        assertTrue("panX must stay in its bounds", panX <= 0f && panX >= 1080f * (1f - scale) - 1f)
    }

    @Test
    fun `a pinch back to rest snaps to exactly one and releases the transform`() {
        mount()
        pinchOut()
        compose.waitForIdle()
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 400f))
            down(1, center + Offset(0f, 400f))
            repeat(12) { i ->
                val gap = 800f - 730f * (i + 1) / 12
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertEquals("snap must land at exactly 1x", 1f, zoomState.scale.floatValue, 0.001f)
        assertEquals("panX must collapse with the snap", 0f, zoomState.panX.floatValue, 0.5f)
        assertEquals("panY must collapse with the snap", 0f, zoomState.panY.floatValue, 0.5f)
    }

    @Test
    fun `a third pointer keeps participating without breaking the bounds`() {
        mount()
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 150f))
            down(1, center + Offset(0f, 150f))
            repeat(4) { i ->
                val gap = 300f + 200f * (i + 1) / 4
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
        }
        val scaleWithTwo = zoomState.scale.floatValue
        compose.onNodeWithTag("zoom").performTouchInput {
            down(2, center + Offset(200f, 0f))
            // The two first pointers stay PUT: only the third moves. If only「the first two」
            // fed the math, the scale would not change (framing arbitration 3, contractualised).
            repeat(4) { i ->
                updatePointerTo(2, center + Offset(200f + 60f * (i + 1), 0f))
                move()
            }
            up(0); up(1); up(2)
        }
        compose.waitForIdle()

        assertNotEquals(
            "the third pointer must participate in the zoom computation",
            scaleWithTwo,
            zoomState.scale.floatValue,
            0.005f,
        )
        val scale = zoomState.scale.floatValue
        assertTrue("scale must stay within [1, ceiling]", scale >= 1f && scale <= MAX_ZOOM_SCALE + 0.001f)
    }

    @Test
    fun `taps propagate while zoomed and after the reset`() {
        var taps = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
            Box(
                Modifier
                    .size(360.dp, 600.dp)
                    .testTag("zoom")
                    .topicMagnifier(zoomState, listState),
            ) {
                // The list ATTACHES the LazyListState : the settle's dispatchRawDelta would die
                // silently inside its launch on a detached state (frozen scale, dead job).
                LazyColumn(state = listState) {
                    items(count = 200) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
                Box(
                    Modifier
                        .size(360.dp, 600.dp)
                        .background(Color.Transparent)
                        .clickable { taps++ },
                )
            }
        }
        // Sanity check of the harness itself : a tap at rest must reach the child.
        compose.onNodeWithTag("zoom").performTouchInput { down(0, center); up(0) }
        compose.waitForIdle()
        assertEquals("harness: a tap at rest must click", 1, taps)

        pinchOut()
        compose.waitForIdle()
        assertTrue(zoomState.zoomed)

        compose.onNodeWithTag("zoom").performTouchInput { down(0, center); up(0) }
        compose.waitForIdle()
        assertEquals("a tap at >1x must still reach the child target", 2, taps)

        // Reset by GESTURE (the proven snap path): the programmatic settle is covered by the
        // dedicated settle test; here only the transparent tap path matters.
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 400f))
            down(1, center + Offset(0f, 400f))
            repeat(12) { i ->
                val gap = 800f - 730f * (i + 1) / 12
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals("the gesture snap must land at rest", 1f, zoomState.scale.floatValue, 0.001f)
        compose.onNodeWithTag("zoom").performTouchInput { down(0, center); up(0) }
        compose.waitForIdle()
        assertEquals("after the reset the tap must keep reaching the child", 3, taps)
    }

    @Test
    fun `direct child target works while zoomed with the reader gesture stack`() {
        var taps = 0
        var refreshes = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
            val dragOffset = remember { mutableFloatStateOf(0f) }
            val haptics = LocalHapticFeedback.current
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(360.dp, 600.dp)
                    .testTag("zoom")
                    .topicMagnifier(zoomState, listState)
                    .topicPageSwipe(
                        currentPage = 5,
                        totalPages = { 10 },
                        dragOffset = dragOffset,
                        handlers = TopicSwipeHandlers(
                            haptics = haptics,
                            onOpenPage = {},
                            enabled = { !zoomState.zoomed },
                            leftGestureInsetPx = { 0 },
                            rightGestureInsetPx = { 0 },
                        ),
                    )
                    .topicDoubleTapRefresh(enabled = !zoomState.zoomed) { refreshes++ }
                    .topicZoomTransform(zoomState),
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(600.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .background(Color.Transparent)
                                .clickable { taps++ }
                                .testTag("target"),
                        )
                    }
                }
                items(count = 20) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("harness: the direct target must work at rest", 1, taps)

        pinchOut()
        compose.waitForIdle()
        assertTrue(zoomState.zoomed)
        compose.onNodeWithTag("target").performTouchInput { click() }
        compose.waitForIdle()

        assertEquals("the zoomed reader stack must let taps reach child targets", 2, taps)
        assertEquals("a zoomed tap must not trigger list-level refresh", 0, refreshes)
    }

    @Test
    fun `long presses propagate while zoomed`() {
        var longPresses = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
            Box(
                Modifier
                    .size(360.dp, 600.dp)
                    .testTag("zoom")
                    .topicMagnifier(zoomState, listState),
            ) {
                LazyColumn(state = listState) {
                    items(count = 200) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
                Box(
                    Modifier
                        .size(360.dp, 600.dp)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { longPresses++ })
                        },
                )
            }
        }

        pinchOut()
        compose.waitForIdle()
        assertTrue(zoomState.zoomed)

        compose.onNodeWithTag("zoom").performTouchInput { longClick(center) }
        compose.waitForIdle()

        assertEquals("a long press at >1x must still reach the child target", 1, longPresses)
    }

    @Test
    fun `long press then drag while zoomed extends the selection instead of panning`() {
        var longPresses = 0
        var dragEnds = 0
        var childDrag = Offset.Zero
        val longPressTimeout = mountLongPressDragTarget(
            onDragStart = { longPresses++ },
            onDragEnd = { dragEnds++ },
            onDrag = { childDrag += it },
        )
        val panBefore = Offset(zoomState.panX.floatValue, zoomState.panY.floatValue)
        val itemBefore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, Offset(120f, 40f))
            advanceEventTime(longPressTimeout + 1)
            moveBy(0, Offset(80f, 0f))
            up(0)
        }
        compose.waitForIdle()

        // Fallback oracle: the child receives the full drag through the 2x transform and ends
        // normally, while neither layer translation nor real list scrolling changes.
        assertEquals("the text child must recognize the long press", 1, longPresses)
        assertEquals("the child must keep the drag through release", 1, dragEnds)
        assertEquals("80 screen px must reach the child as 40 local px", 40f, childDrag.x, 0.001f)
        assertEquals(panBefore, Offset(zoomState.panX.floatValue, zoomState.panY.floatValue))
        assertEquals(itemBefore, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        assertFalse("selection must never engage the zoom pan", zoomState.gestureEngaged)
        assertNull("selection must not start a zoom fling", zoomState.releaseJob)
    }

    @Test
    fun `a second finger still starts a pinch after a child claims the long press drag`() {
        var childDrag = Offset.Zero
        var dragCancellations = 0
        val longPressTimeout = mountLongPressDragTarget(
            onDragCancel = { dragCancellations++ },
            onDrag = { childDrag += it },
        )
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 150f))
            advanceEventTime(longPressTimeout + 1)
            moveBy(0, Offset(80f, 0f))
        }
        compose.waitForIdle()
        assertEquals(40f, childDrag.x, 0.001f)
        assertEquals("child consumption must block the pan", -100f, zoomState.panX.floatValue, 0.001f)

        compose.onNodeWithTag("zoom").performTouchInput {
            down(1, center + Offset(80f, 150f))
        }
        assertTrue("the second down must engage on Initial despite child ownership", zoomState.gestureEngaged)
        compose.onNodeWithTag("zoom").performTouchInput {
            repeat(4) {
                moveBy(0, Offset(0f, -20f))
                moveBy(1, Offset(0f, 20f))
            }
            up(1)
            up(0)
        }
        compose.waitForIdle()
        assertTrue("the pinch must still increase the scale", zoomState.scale.floatValue > 2f)
        assertEquals("the pinch must cancel the child's drag", 1, dragCancellations)
        assertEquals("pinch movement must be consumed before the child on Main", 0f, childDrag.y, 0.001f)
    }

    @Test
    fun `one finger pan while zoomed lets children arbitrate then consumes subsequent movement`() {
        var childUnconsumedMoves = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = 1, animationScope = scope)
            Box(
                Modifier
                    .size(360.dp, 600.dp)
                    .testTag("zoom")
                    .topicMagnifier(zoomState, listState),
            ) {
                LazyColumn(state = listState) {
                    items(count = 200) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.changes.none { it.pressed }) return@awaitEachGesture
                                    if (event.changes.any { it.positionChange() != Offset.Zero && !it.isConsumed }) {
                                        childUnconsumedMoves++
                                    }
                                }
                            }
                        },
                )
            }
        }
        pinchOut()
        compose.waitForIdle()
        assertTrue(zoomState.zoomed)
        val itemBefore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(0f, -80f)) }
            up(0)
        }
        compose.waitForIdle()

        val itemAfter = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        assertEquals("only the engaging frame reaches children unconsumed on Main", 1, childUnconsumedMoves)
        assertNotEquals("the consumed movement must still drive the zoom pan", itemBefore, itemAfter)
    }

    @Test
    fun `one finger horizontal pan without long press still translates while zoomed`() {
        mount(withSwipe = true)
        pinchOut(gapEndPx = 600f)
        compose.waitForIdle()
        val panBefore = zoomState.panX.floatValue
        val itemBefore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(4) { moveBy(0, Offset(80f, 0f)) }
            up(0)
        }
        compose.waitForIdle()

        assertEquals("all horizontal deltas still drive the pan", panBefore + 320f, zoomState.panX.floatValue, 0.001f)
        assertEquals(itemBefore, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        assertEquals("zoomed horizontal pan must not arm the page swipe", 0f, swipeDragOffset.floatValue, 0.001f)
    }

    @Test
    fun `at rest a vertical drag still scrolls the native list without engaging the magnifier`() {
        mount(withSwipe = true)
        val itemBefore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(4) { moveBy(0, Offset(0f, -80f)) }
        }
        compose.waitForIdle()

        assertNotEquals(itemBefore, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        assertFalse("native scrolling at 1x must retain the gesture", zoomState.gestureEngaged)
        assertEquals(1f, zoomState.scale.floatValue, 0.001f)
        compose.onNodeWithTag("zoom").performTouchInput { up(0) }
    }

    @Test
    fun `an uncommitted swipe is cancelled by the second finger with zero navigation`() {
        var opened: Int? = null
        mount(withSwipe = true, onOpenPage = { opened = it })
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) } // past slop AND past the commit distance
            down(1, center + Offset(0f, 200f))
            repeat(4) { i ->
                updatePointerTo(0, center - Offset(600f, 40f * (i + 1)))
                updatePointerTo(1, center + Offset(0f, 200f + 40f * (i + 1)))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertNull("the armed swipe must cancel into the pinch, never navigate", opened)
        assertTrue("the pinch must have engaged", zoomState.scale.floatValue > 1f)
    }

    @Test
    fun `at rest the page swipe still commits exactly once under the magnifier`() {
        var opened = 0
        mount(withSwipe = true, onOpenPage = { opened++ })
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(10) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()

        assertEquals("the magnifier at 1x must not eat the swipe — exactly one navigation", 1, opened)
    }

    @Test
    fun `capture is held until the last up even back at one`() {
        mount(withSwipe = true, onOpenPage = { })
        // Zoom in then back to ~1x WITHOUT lifting, then drag horizontally with the remaining
        // finger: the magnifier must keep the capture (no swipe wake-up mid-gesture).
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 300f))
            down(1, center + Offset(0f, 300f))
            repeat(6) { i ->
                val gap = 600f + 300f * (i + 1) / 6
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            repeat(6) { i ->
                val gap = 900f - 600f * (i + 1) / 6
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(1)
            repeat(8) { moveBy(0, Offset(-60f, 0f)) } // would arm the swipe if capture leaked
            up(0)
        }
        compose.waitForIdle()

        assertEquals(
            "the one-finger tail of a pinch must never reach the page swipe",
            0f,
            swipeDragOffset.floatValue,
            0.5f,
        )
    }

    @Test
    fun `the anchored settle animates through intermediate scales and is interruptible`() {
        mount()
        pinchOut()
        compose.waitForIdle()
        val zoomed = zoomState.scale.floatValue
        assertTrue(zoomed > 1.5f)

        compose.mainClock.autoAdvance = false
        zoomState.settleAnchoredTo(1f, 540f, 900f, listState)
        compose.mainClock.advanceTimeBy(80)
        val midway = zoomState.scale.floatValue
        assertTrue(
            "the settle must be animating (midway=$midway, from=$zoomed)",
            midway < zoomed - 0.05f && midway > 1f + 0.02f,
        )
        // A new pinch interrupts the animation (hypothesis 8): engage cancels the release job.
        zoomState.engage(listState)
        val frozen = zoomState.scale.floatValue
        compose.mainClock.advanceTimeBy(300)
        assertEquals("engage must freeze the settle", frozen, zoomState.scale.floatValue, 0.001f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `a page key change resets the zoom and orphans no animation`() {
        val key = mutableStateOf(1)
        compose.setContent {
            val scope = rememberCoroutineScope()
            listState = remember { LazyListState() }
            zoomState = rememberTopicZoomState(pageKey = key.value, animationScope = scope)
            Box(Modifier.size(360.dp, 600.dp).testTag("zoom").topicMagnifier(zoomState, listState)) {
                LazyColumn(state = listState) {
                    items(count = 200) { i -> Text("post $i", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        pinchOut()
        compose.waitForIdle()
        assertTrue(zoomState.zoomed)

        // Start a settle, then swap the page mid-animation: the NEW state must open at rest and
        // the ORPHANED job must stop mutating the shared list (gate: scroll position frozen).
        compose.mainClock.autoAdvance = false
        zoomState.settleAnchoredTo(1f, 540f, 900f, listState)
        compose.mainClock.advanceTimeBy(60)
        key.value = 2
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val itemAfterSwap = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true

        assertEquals("the new page's state must open at 1x", 1f, zoomState.scale.floatValue, 0.001f)
        assertEquals("panX must open at rest", 0f, zoomState.panX.floatValue, 0.001f)
        assertEquals(
            "the orphaned settle must stop scrolling the list after the key change",
            itemAfterSwap,
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun `one finger pan while zoomed scrolls the real list and bounds panX`() {
        mount()
        pinchOut()
        compose.waitForIdle()
        val itemBefore = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(0f, -80f)) } // pan up = content up = list scrolls forward
            up(0)
        }
        compose.waitForIdle()

        val itemAfter = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        assertNotEquals("the vertical pan must drive the REAL list scroll", itemBefore, itemAfter)
        val scale = zoomState.scale.floatValue
        val panX = zoomState.panX.floatValue
        assertTrue("panX must stay bounded", panX <= 0f && panX >= 1080f * (1f - scale) - 1f)
    }

    @Test
    fun `a fast zoomed pan release glides on after the finger lifts`() {
        mount()
        pinchOut()
        compose.waitForIdle()
        // Fast one-finger pan up : high release velocity.
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center + Offset(0f, 300f))
            repeat(6) { moveBy(0, Offset(0f, -90f)) }
            up(0)
        }
        val atRelease = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val afterDecay = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

        assertNotEquals("the damped glide must keep scrolling after the lift", atRelease, afterDecay)
        val settled = afterDecay
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(
            "the strong friction must have stopped the glide well within ~600ms",
            settled,
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun `a gesture that pinched never flings on release`() {
        mount()
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center - Offset(0f, 150f))
            down(1, center + Offset(0f, 150f))
            repeat(6) { i ->
                val gap = 300f + 300f * (i + 1) / 6
                updatePointerTo(0, center - Offset(0f, gap / 2f))
                updatePointerTo(1, center + Offset(0f, gap / 2f))
                move()
            }
            up(1)
            // Same gesture continues as a FAST pan : hadPinch is latched, no fling allowed.
            repeat(6) { moveBy(0, Offset(0f, -90f)) }
            up(0)
        }
        compose.waitForIdle()
        val atRelease = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        assertEquals(
            "a mixed pinch-pan gesture must stop dead at the lift (no RF1 sucette)",
            atRelease,
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun `a slow zoomed pan release does not fling`() {
        mount()
        pinchOut()
        compose.waitForIdle()
        compose.onNodeWithTag("zoom").performTouchInput {
            down(0, center + Offset(0f, 300f))
            repeat(6) {
                advanceEventTime(120)
                moveBy(0, Offset(0f, -30f))
            }
            up(0)
        }
        compose.waitForIdle()
        val atRelease = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        assertEquals(
            "below the velocity threshold the pan must stop with the finger",
            atRelease,
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset,
        )
    }
}
