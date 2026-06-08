package fr.forumhfr.redface2.feature.topic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * #300 — intra-page position indicator + fast-scroll for a topic page.
 *
 * A thin, auto-hiding scrollbar overlaid on the right edge of the topic [LazyColumn]. It shows the
 * reading position within the *current* page (between-page navigation is out of scope — #284/#283 cover
 * that) and lets the user fast-scroll by dragging the thumb. Pure UI derived from [LazyListState] — no
 * MVI/ViewModel/repository change.
 *
 * Two layers, deliberately separated (Compose pointer nodes don't share with siblings by default, so a
 * full-height pointer overlay would swallow the list's own scroll/taps on the whole right strip):
 *  - a full-height [Canvas] that only **draws** the thumb (no pointer input), and
 *  - a small **hit target** placed only over the thumb (± a grab padding) that carries the drag gesture.
 * Outside the thumb hit target there is no pointer node, so a normal vertical scroll / a tap on a
 * right-aligned post action falls straight through to the list.
 *
 * Geometry uses an **index-based** model (pragmatic v1): thumb size ≈ visibleItems/totalItems, thumb top
 * ≈ firstVisibleIndex/totalItems with sub-item interpolation from the scroll offset. Variable post
 * heights make this an approximation; a single post taller than the viewport yields a coarse thumb
 * (sizeFraction ≈ 1) and a coarse fast-scroll — accepted for v1. "Is there anything to scroll" is read
 * from [LazyListState.canScrollForward]/[LazyListState.canScrollBackward], NOT from visible ≥ total
 * (which is false when one item is taller than the viewport). It works in pure `LazyListState` index
 * space (the header card is lazy item 0), so thumb position and `scrollToItem` stay mutually consistent
 * — no header offset to apply.
 */
@Composable
internal fun TopicScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val canScroll by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val metrics by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            scrollbarMetrics(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                firstVisibleItemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0,
                visibleItemsCount = info.visibleItemsInfo.size,
                totalItemsCount = info.totalItemsCount,
            )
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    val active = canScroll && metrics != null

    // Visible while scrolling or dragging; otherwise a brief show-then-fade. The idle branch also fires
    // on the first composition of a scrollable page (initial flash → discoverability of the fast-scroll
    // affordance) and after a scroll settles (post-scroll hold). The LaunchedEffect restarts on every
    // (active / scroll / drag) change, so a scroll resuming within the hold cancels the pending fade.
    var alphaTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(active, listState.isScrollInProgress, isDragging) {
        when {
            !active -> alphaTarget = 0f
            listState.isScrollInProgress || isDragging -> alphaTarget = 1f
            else -> {
                alphaTarget = 1f
                delay(HIDE_DELAY_MS)
                alphaTarget = 0f
            }
        }
    }
    val alpha by animateFloatAsState(targetValue = alphaTarget, label = "topicScrollbarAlpha")
    val thumbColor = MaterialTheme.colorScheme.primary

    // Read live counts inside the gesture without re-keying the pointerInput on the (per-frame) metrics.
    val currentVisibleCount = rememberUpdatedState(listState.layoutInfo.visibleItemsInfo.size)
    val currentTotalCount = rememberUpdatedState(listState.layoutInfo.totalItemsCount)
    val scope = rememberCoroutineScope()
    var lastTargetIndex by remember { mutableIntStateOf(-1) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    // Hoisted out of the layout tree so the `if` they contain doesn't add nesting depth below.
    val onDraggingChange: (Boolean) -> Unit = { dragging ->
        isDragging = dragging
        if (dragging) lastTargetIndex = -1 // a fresh grab always re-evaluates the target
    }
    val onSeek: (Float) -> Unit = { travelFraction ->
        val index = targetIndexForDrag(
            travelFraction = travelFraction,
            visibleItemsCount = currentVisibleCount.value,
            totalItemsCount = currentTotalCount.value,
        )
        if (index != lastTargetIndex) {
            lastTargetIndex = index
            scrollJob?.cancel()
            scrollJob = scope.launch { listState.scrollToItem(index) }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(SCROLLBAR_GUTTER_WIDTH)
            .fillMaxHeight(),
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val m = metrics
            if (alpha > 0f && m != null) {
                drawScrollbarThumb(metrics = m, alpha = alpha, color = thumbColor)
            }
        }
        val m = metrics
        if (active && m != null) {
            ThumbHitTarget(
                listState = listState,
                trackHeightPx = trackHeightPx,
                metrics = m,
                onDraggingChange = onDraggingChange,
                onSeek = onSeek,
            )
        }
    }
}

/**
 * The thumb-only drag surface. Placed at the thumb's current vertical offset (± [GRAB_PADDING]) so it is
 * the *only* pointer node in the gutter — everything else stays scrollable/tappable. The drag maps the
 * finger's absolute track position back from the live hit-target top: `trackY = hitTop + localY`, which
 * stays correct frame-to-frame even as the thumb (and this surface) moves under the finger.
 */
@Composable
private fun ThumbHitTarget(
    listState: LazyListState,
    trackHeightPx: Float,
    metrics: ScrollbarMetrics,
    onDraggingChange: (Boolean) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val padPx = with(LocalDensity.current) { GRAB_PADDING.toPx() }
    val thumbTopPx = metrics.offsetFraction * trackHeightPx
    val thumbHeightPx = metrics.sizeFraction * trackHeightPx
    val hitTopPx = (thumbTopPx - padPx).coerceAtLeast(0f)
    val hitHeight = with(LocalDensity.current) { (thumbHeightPx + 2f * padPx).toDp() }
    val liveMetrics = rememberUpdatedState(metrics)
    val liveSeek = rememberUpdatedState(onSeek)
    val liveDragging = rememberUpdatedState(onDraggingChange)

    Box(
        modifier = Modifier
            .offset { IntOffset(x = 0, y = hitTopPx.roundToInt()) }
            .fillMaxWidth()
            .height(hitHeight)
            .pointerInput(listState) {
                detectVerticalDragGestures(
                    onDragStart = { liveDragging.value(true) },
                    onDragEnd = { liveDragging.value(false) },
                    onDragCancel = { liveDragging.value(false) },
                ) { change, _ ->
                    change.consume()
                    val thumbH = liveMetrics.value.sizeFraction * trackHeightPx
                    val hitTopNow = (liveMetrics.value.offsetFraction * trackHeightPx - padPx).coerceAtLeast(0f)
                    val trackY = hitTopNow + change.position.y
                    val travel = (trackHeightPx - thumbH).coerceAtLeast(1f)
                    val top = (trackY - thumbH / 2f).coerceIn(0f, travel)
                    liveSeek.value(top / travel)
                }
            },
    )
}

private fun DrawScope.drawScrollbarThumb(
    metrics: ScrollbarMetrics,
    alpha: Float,
    color: Color,
) {
    val trackHeightPx = size.height
    val thumbHeightPx = metrics.sizeFraction * trackHeightPx
    val thumbTopPx = metrics.offsetFraction * trackHeightPx
    val thumbWidthPx = THUMB_WIDTH.toPx()
    drawRoundRect(
        color = color.copy(alpha = THUMB_ALPHA * alpha),
        topLeft = Offset(size.width - thumbWidthPx, thumbTopPx),
        size = Size(thumbWidthPx, thumbHeightPx),
        cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
    )
}

/**
 * Geometry of the scrollbar thumb, derived from the lazy list. [offsetFraction] ∈ [0, 1] is the top of
 * the thumb as a fraction of the track; [sizeFraction] ∈ (0, 1] is the thumb height as a fraction of the
 * track.
 */
internal data class ScrollbarMetrics(
    val offsetFraction: Float,
    val sizeFraction: Float,
)

/**
 * Pure thumb geometry. Returns `null` only when there is nothing to represent ([totalItemsCount] ≤ 0 or
 * no visible item) — the "page fits, nothing to scroll" decision is taken by the caller from
 * `canScrollForward/Backward`, NOT from `visibleItemsCount >= totalItemsCount` (false when a single item
 * is taller than the viewport). Index-based with sub-item interpolation; [firstVisibleItemSize] ≤ 0 is
 * tolerated (no sub-item contribution).
 */
internal fun scrollbarMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemSize: Int,
    visibleItemsCount: Int,
    totalItemsCount: Int,
): ScrollbarMetrics? {
    if (totalItemsCount <= 0 || visibleItemsCount <= 0) return null
    val sizeFraction = (visibleItemsCount.toFloat() / totalItemsCount).coerceIn(MIN_THUMB_FRACTION, 1f)
    val subItem =
        if (firstVisibleItemSize > 0) firstVisibleItemScrollOffset.toFloat() / firstVisibleItemSize else 0f
    val offsetFraction =
        ((firstVisibleItemIndex + subItem) / totalItemsCount).coerceIn(0f, 1f - sizeFraction)
    return ScrollbarMetrics(offsetFraction = offsetFraction, sizeFraction = sizeFraction)
}

/**
 * Maps the thumb's travel fraction (∈ [0, 1] over its *available* travel = track − thumb) to a target
 * first-visible item index, consistent with [scrollbarMetrics] (max offset 1 − sizeFraction ⇔
 * firstVisibleIndex = total − visible). Clamps so a shrinking [totalItemsCount] mid-drag never yields a
 * negative index.
 */
internal fun targetIndexForDrag(
    travelFraction: Float,
    visibleItemsCount: Int,
    totalItemsCount: Int,
): Int {
    val maxFirstIndex = (totalItemsCount - visibleItemsCount.coerceAtLeast(1)).coerceAtLeast(0)
    return (travelFraction.coerceIn(0f, 1f) * maxFirstIndex).roundToInt()
}

private const val THUMB_ALPHA = 0.7f
internal const val MIN_THUMB_FRACTION = 0.08f
private const val HIDE_DELAY_MS = 800L
private val SCROLLBAR_GUTTER_WIDTH = 24.dp
private val THUMB_WIDTH = 6.dp
private val GRAB_PADDING = 12.dp
