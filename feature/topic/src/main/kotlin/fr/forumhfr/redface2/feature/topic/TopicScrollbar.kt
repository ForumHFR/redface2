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
import androidx.compose.foundation.lazy.LazyListItemInfo
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
 * Geometry uses a **pixel-based estimated** model: thumb size ≈ viewport / (averageItemSize × total),
 * thumb top ≈ pixelsScrolled / maxScroll. The earlier index-based model (`visibleItems/total`,
 * `firstVisibleIndex/total`) was the root cause of the "jumps + resizes" bug on a forum page: posts have
 * wildly unequal heights, so `visibleItemsCount` (an integer) flipped 3↔4↔2 as a tall post entered/left
 * the viewport — resizing the thumb by whole-item steps — and the thumb travelled ~10× faster over a
 * short post than a tall one for the same `1/total` step, so it accelerated/jumped at every item border.
 * The pixel model derives both size and position from an estimated total height (mean of the *visible*
 * item sizes × total), which varies continuously instead of by integer steps. It is still an
 * approximation (the mean drifts a little as the visible set changes), but far smoother. "Is there
 * anything to scroll" is read from [LazyListState.canScrollForward]/[LazyListState.canScrollBackward],
 * NOT from visible ≥ total (false when one item is taller than the viewport). It works in pure
 * `LazyListState` index space (the header card is lazy item 0), so thumb position and `scrollToItem`
 * stay mutually consistent — no header offset to apply.
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
            val visible = info.visibleItemsInfo
            scrollbarMetrics(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                averageItemSizePx = visible.averageItemSizePx(),
                totalItemsCount = info.totalItemsCount,
                viewportHeightPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
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

    // Read live geometry inside the gesture without re-keying the pointerInput on the (per-frame)
    // metrics. Same estimated-height inputs as `scrollbarMetrics`, so a fast-scroll lands where the
    // thumb sits.
    val currentTotalCount = rememberUpdatedState(listState.layoutInfo.totalItemsCount)
    val currentAvgItemSize = rememberUpdatedState(listState.layoutInfo.visibleItemsInfo.averageItemSizePx())
    val currentViewportHeight = rememberUpdatedState(
        (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset).toFloat(),
    )
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
            averageItemSizePx = currentAvgItemSize.value,
            totalItemsCount = currentTotalCount.value,
            viewportHeightPx = currentViewportHeight.value,
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

/** Mean size (px) of the currently visible lazy items — the estimator both pure functions rely on. */
private fun List<LazyListItemInfo>.averageItemSizePx(): Float =
    if (isEmpty()) 0f else sumOf { it.size }.toFloat() / size

/**
 * Pure thumb geometry, **pixel-based** (#300 follow-up fixing the "jumps + resizes" report). The thumb
 * size and position come from an *estimated* total content height ([averageItemSizePx] × [totalItemsCount])
 * and the pixels already scrolled — NOT from the integer count of visible items, which flipped by whole
 * steps on a page of unequal-height posts and made the thumb resize and travel non-uniformly.
 *
 * [averageItemSizePx] is the mean size of the **currently visible** items: an approximation that stays
 * smooth because it varies continuously (a tall post entering nudges the mean up gradually) rather than by
 * whole-item steps. A small residual discontinuity remains when the first visible item changes while its
 * size differs a lot from the mean; it is far smaller than the index-based jump and accepted for v1.
 *
 * Returns `null` only when there is nothing to represent ([totalItemsCount] ≤ 0, no visible item, or a
 * non-positive [averageItemSizePx]/[viewportHeightPx]). The "page fits, nothing to scroll" decision stays
 * with the caller (`canScrollForward/Backward`). [offsetFraction] ∈ [0, 1 − sizeFraction]; [sizeFraction]
 * ∈ (0, 1].
 */
internal fun scrollbarMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    averageItemSizePx: Float,
    totalItemsCount: Int,
    viewportHeightPx: Float,
): ScrollbarMetrics? {
    // Single guard (ReturnCount): an empty visible set surfaces as `averageItemSizePx == 0f`, so the
    // "nothing visible" case is covered here without a separate `visibleItemsCount` parameter.
    if (totalItemsCount <= 0 || averageItemSizePx <= 0f || viewportHeightPx <= 0f) return null
    val estimatedTotalHeight = averageItemSizePx * totalItemsCount
    val sizeFraction = (viewportHeightPx / estimatedTotalHeight).coerceIn(MIN_THUMB_FRACTION, 1f)
    val maxScrollPx = estimatedTotalHeight - viewportHeightPx
    val offsetFraction = if (maxScrollPx <= 0f) {
        0f
    } else {
        val scrolledPx = firstVisibleItemIndex * averageItemSizePx + firstVisibleItemScrollOffset
        ((scrolledPx / maxScrollPx) * (1f - sizeFraction)).coerceIn(0f, 1f - sizeFraction)
    }
    return ScrollbarMetrics(offsetFraction = offsetFraction, sizeFraction = sizeFraction)
}

/**
 * Maps the thumb's travel fraction (∈ [0, 1] over its *available* travel = track − thumb) back to a
 * target first-visible item index, using the SAME estimated-height model as [scrollbarMetrics] so the
 * thumb position and a fast-scroll stay mutually consistent: `travelFraction × maxScrollPx` is the target
 * scroll position in px, divided by [averageItemSizePx] for the item to land on. Clamps to a valid index
 * so a shrinking [totalItemsCount] mid-drag never yields an out-of-range value.
 */
internal fun targetIndexForDrag(
    travelFraction: Float,
    averageItemSizePx: Float,
    totalItemsCount: Int,
    viewportHeightPx: Float,
): Int {
    if (averageItemSizePx <= 0f || totalItemsCount <= 0) return 0
    val estimatedTotalHeight = averageItemSizePx * totalItemsCount
    val maxScrollPx = (estimatedTotalHeight - viewportHeightPx).coerceAtLeast(0f)
    val targetScrollPx = travelFraction.coerceIn(0f, 1f) * maxScrollPx
    val index = (targetScrollPx / averageItemSizePx).roundToInt()
    return index.coerceIn(0, totalItemsCount - 1)
}

private const val THUMB_ALPHA = 0.7f
internal const val MIN_THUMB_FRACTION = 0.08f
private const val HIDE_DELAY_MS = 800L
private val SCROLLBAR_GUTTER_WIDTH = 24.dp
private val THUMB_WIDTH = 6.dp
private val GRAB_PADDING = 12.dp
