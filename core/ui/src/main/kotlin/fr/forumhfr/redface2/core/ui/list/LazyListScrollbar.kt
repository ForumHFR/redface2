package fr.forumhfr.redface2.core.ui.list

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import fr.forumhfr.redface2.core.ui.theme.LocalShowScrollbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * #300/#351 — intra-page position indicator + fast-scroll for a paged post list (topic page, private
 * conversation page).
 *
 * A thin, auto-hiding scrollbar overlaid on the right edge of a post [LazyColumn]. It shows the
 * reading position within the *current* page (between-page navigation is out of scope — #284/#283 cover
 * that) and lets the user fast-scroll by dragging the thumb. Pure UI derived from [LazyListState] — no
 * MVI/ViewModel/repository change. Born as the topic scrollbar (#300); moved verbatim to `:core:ui`
 * for the private-message thread (#351, ADR-013 — share the *components*, not the screens).
 *
 * Two layers, deliberately separated (Compose pointer nodes don't share with siblings by default, so a
 * full-height pointer overlay would swallow the list's own scroll/taps on the whole right strip):
 *  - a full-height [Canvas] that only **draws** the thumb (no pointer input), and
 *  - a small **hit target** placed only over the thumb (± a grab padding) that carries the drag gesture.
 * Outside the thumb hit target there is no pointer node, so a normal vertical scroll / a tap on a
 * right-aligned post action falls straight through to the list.
 *
 * Geometry uses a **fixed-size ordinal model with sub-item interpolation** (cf. [scrollbarMetrics]):
 * a constant thumb size ([THUMB_SIZE_FRACTION]) and a position from `(firstVisibleItemIndex +
 * itemFraction) / (totalItemsCount − 1)`, where `itemFraction` interpolates within the current top post
 * using only that post's own offset/size. Two earlier attempts tied the thumb to a *moving estimate* of
 * total content height and failed: an index-based one (size = `visibleItemsCount/total`, an integer
 * flipping 3↔4↔2) and a pixel-estimated one (size = `viewport / (avgVisibleItemSize × total)`). On a
 * forum page — unequal post heights, plus block images that grow 160→480dp once Coil decodes them (#197)
 * — the estimated total "breathes", so the thumb resized and jumped even while idle. Keeping the size
 * constant (never measured) kills that; interpolating the position with only the *current* top item's
 * size keeps it continuous (no per-post "à-coup") while bounding any residual #197 wobble to one ordinal
 * step.
 *
 * Trade-off (per the Codex review): the thumb size is not proportional to true scroll length and the
 * within-post speed is non-uniform, but it is stable and matches the fast-scroll use case ("where am I /
 * jump near another post"). The drawn offset SNAPS while scrolling/dragging (tracks the finger exactly)
 * and springs only on the idle settle. "Is there anything to scroll" is still read from
 * [LazyListState.canScrollForward]/[LazyListState.canScrollBackward]. Pure `LazyListState` index space
 * (the header card is lazy item 0), so thumb position and `scrollToItem` stay mutually consistent.
 */
@Composable
fun LazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // #105 — « afficher l'ascenseur » opt-out: render nothing when the preference is off (sujets AND
    // MP, both call-sites go through this composable). Read at the call-site of the local so flipping
    // the Settings toggle recomposes this away; the early-return keeps the scrollbar's per-frame
    // derivedState machinery from running at all while hidden.
    if (!LocalShowScrollbar.current) return
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
                totalItemsCount = info.totalItemsCount,
            )
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    val active = canScroll && metrics != null

    // Auto-hide alpha extracted to rememberScrollbarAlpha to keep this composable under the cyclomatic
    // complexity threshold; visible while scrolling/dragging, brief show-then-fade otherwise.
    val alpha = rememberScrollbarAlpha(
        active = active,
        isScrollInProgress = listState.isScrollInProgress,
        isDragging = isDragging,
    )
    val thumbColor = MaterialTheme.colorScheme.primary

    // Sub-item interpolation already makes the offset continuous during a scroll, so it SNAPS (tracks
    // the finger exactly) while scrolling/dragging — a spring there would only lag the live input — and
    // springs only on the idle settle. Extracted to rememberScrollbarDrawOffset (spec per Codex review).
    val animatedOffset = rememberScrollbarDrawOffset(
        targetOffset = metrics?.offsetFraction ?: 0f,
        snapping = isDragging || listState.isScrollInProgress,
    )
    val drawnMetrics = metrics?.copy(offsetFraction = animatedOffset)

    // Live total read inside the gesture without re-keying the pointerInput on the (per-frame) metrics.
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
            val m = drawnMetrics
            if (alpha > 0f && m != null) {
                drawScrollbarThumb(metrics = m, alpha = alpha, color = thumbColor)
            }
        }
        val m = drawnMetrics
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
 * Auto-hide opacity for the scrollbar: fully visible while [active] and (scrolling or [isDragging]),
 * otherwise a brief show-then-fade after the last interaction. The idle branch also fires on the first
 * composition of a scrollable page (initial flash → discoverability) and after a scroll settles. The
 * [LaunchedEffect] restarts on every (active / scroll / drag) change, so a scroll resuming within the
 * hold cancels the pending fade.
 */
@Composable
private fun rememberScrollbarAlpha(
    active: Boolean,
    isScrollInProgress: Boolean,
    isDragging: Boolean,
): Float {
    var alphaTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(active, isScrollInProgress, isDragging) {
        when {
            !active -> alphaTarget = 0f
            isScrollInProgress || isDragging -> alphaTarget = 1f
            else -> {
                alphaTarget = 1f
                delay(HIDE_DELAY_MS)
                alphaTarget = 0f
            }
        }
    }
    val alpha by animateFloatAsState(targetValue = alphaTarget, label = "lazyListScrollbarAlpha")
    return alpha
}

/**
 * Drawn thumb offset: snaps to [targetOffset] while [snapping] (the thumb is dragged or the list is
 * scrolling — sub-item interpolation already makes that continuous, and a spring would only lag the
 * live input), and springs gently on the idle settle so a `scrollToItem` landing eases in.
 */
@Composable
private fun rememberScrollbarDrawOffset(
    targetOffset: Float,
    snapping: Boolean,
): Float {
    val offset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = if (snapping) {
            snap()
        } else {
            spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        },
        label = "lazyListScrollbarOffset",
    )
    return offset
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
 * Pure thumb geometry, **fixed-size ordinal with sub-item interpolation** (#300). The thumb size is the
 * constant [THUMB_SIZE_FRACTION] of the track; its position is the list ordinal
 * `(firstVisibleItemIndex + itemFraction) / (totalItemsCount − 1)` mapped onto the available travel
 * `1 − sizeFraction`, where `itemFraction = firstVisibleItemScrollOffset / firstVisibleItemSize` ∈ [0, 1)
 * interpolates *within the current top post*.
 *
 * Why this shape (two earlier models failed):
 *  - **size** never reads a measured height (constant), so a changing visible set or a post-decode image
 *    growth (#197) can't resize the thumb — that killed the "grows suddenly" symptom.
 *  - **position** interpolates within a post using ONLY the current top item's own offset/size, so it is
 *    continuous within a post and (near-)continuous across the boundary — no per-post step/"à-coup".
 *    The single measured input ([firstVisibleItemSize]) is local: a #197 growth of the top post wobbles
 *    the thumb by at most one ordinal step (`(1−size)/(total−1)`), never the global "×N rewrite of every
 *    prior item" that the average-of-visible-items estimate caused.
 *
 * Guards (cf. the Codex review): `totalItemsCount ≤ 1 → null` (a single, possibly tall, item has no
 * ordinal progress); the index is clamped; [firstVisibleItemScrollOffset] is clamped to the item so the
 * fraction stays in [0, 1); the **last** ordinal forces `itemFraction = 0` so progress never exceeds 1.
 *
 * Trade-off: within a post the speed is non-uniform (tall posts crawl, short posts race) — inherent to
 * exact anchoring, and accepted because the anchoring is wanted. [offsetFraction] ∈ [0, 1 − sizeFraction];
 * [sizeFraction] is constant.
 */
internal fun scrollbarMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstVisibleItemSize: Int,
    totalItemsCount: Int,
): ScrollbarMetrics? {
    if (totalItemsCount <= 1) return null
    val lastIndex = totalItemsCount - 1
    val index = firstVisibleItemIndex.coerceIn(0, lastIndex)
    val itemFraction = if (firstVisibleItemSize > 0 && index < lastIndex) {
        firstVisibleItemScrollOffset.coerceIn(0, firstVisibleItemSize - 1).toFloat() / firstVisibleItemSize
    } else {
        0f
    }
    val progress = ((index + itemFraction) / lastIndex).coerceIn(0f, 1f)
    return ScrollbarMetrics(
        offsetFraction = progress * (1f - THUMB_SIZE_FRACTION),
        sizeFraction = THUMB_SIZE_FRACTION,
    )
}

/**
 * Maps the thumb's travel fraction (∈ [0, 1] over its *available* travel = track − thumb) back to a
 * target first-visible item index, the inverse of [scrollbarMetrics]'s ordinal mapping:
 * `round(travelFraction × (total − 1))`. Clamps so an out-of-range fraction or a shrinking
 * [totalItemsCount] mid-drag never yields an out-of-range index.
 */
internal fun targetIndexForDrag(
    travelFraction: Float,
    totalItemsCount: Int,
): Int {
    if (totalItemsCount <= 1) return 0
    val index = (travelFraction.coerceIn(0f, 1f) * (totalItemsCount - 1)).roundToInt()
    return index.coerceIn(0, totalItemsCount - 1)
}

private const val THUMB_ALPHA = 0.7f

/**
 * Constant thumb height as a fraction of the track. Fixed on purpose: tying the size to any measured
 * quantity (visible-item count, or an estimated total height) made it resize as the visible set or image
 * heights changed — the "grows suddenly" symptom. ~12% gives a comfortable grab target on a phone track.
 */
internal const val THUMB_SIZE_FRACTION = 0.12f
private const val HIDE_DELAY_MS = 800L
private val SCROLLBAR_GUTTER_WIDTH = 24.dp
private val THUMB_WIDTH = 6.dp
private val GRAB_PADDING = 12.dp
