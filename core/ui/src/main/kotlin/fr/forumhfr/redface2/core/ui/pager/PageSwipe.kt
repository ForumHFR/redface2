package fr.forumhfr.redface2.core.ui.pager

import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Shared geometry of the horizontal page-swipe gesture (#282 topic, #351 private messages).
 *
 * These pure functions carry the entire *feel* of the swipe — commit thresholds (distance + fling),
 * drag-follow shaping (1:1 then bounded overpull, damped wall at a blocked edge) and the edge-hint
 * opacity ramp — so both consumers share the exact same thresholds and feedback. What is NOT shared
 * (ADR-013): the gesture *machinery*. The topic gesture is route-driven (its re-entrance latch is
 * reset by the route change destroying the composition — `topicPageSwipe` in `:feature:topic`);
 * the private-message thread paginates in place (same composition, latch re-armed locally).
 * Generalising one pointer-input pipeline over both lifecycles was rejected as speculative
 * complexity for two consumers.
 */

/**
 * Target page of a horizontal swipe (#282), or `null` when the gesture is blocked by an edge.
 * [forward] = true → next page, false → previous page. Pure → unit-tested without Compose.
 *
 * Bounds the result to `1..totalPages` — the same range as the pager's `canGoNext`/`canGoPrevious`.
 * The page source is the *rendered* page (what the user is looking at), which matches the value the
 * Previous/Next buttons pass to the page-change callback.
 */
/**
 * #752 — start (edge-gesture) dead zone: `true` when a gesture's DOWN lands inside the system's
 * left/right gesture bands ([leftInsetPx] / [rightInsetPx], from `WindowInsets.systemGestures`).
 * A custom horizontal swipe must not START there: on a real edge the system back gesture wins
 * anyway, and a finger-width miss just inside the band used to fire a surprise tab/page change
 * (beta feedback by Stylken). Distinct from the existing « swipe dead-zone » wording, which
 * denotes the nav-transition collapse (cf. `topicPageSwipe`). Pure → unit-tested without Compose.
 */
fun inStartGestureDeadZone(x: Float, widthPx: Int, leftInsetPx: Int, rightInsetPx: Int): Boolean =
    x < leftInsetPx || x > widthPx - rightInsetPx

fun swipeTargetPage(currentPage: Int, totalPages: Int, forward: Boolean): Int? =
    if (forward) {
        (currentPage + 1).takeIf { it <= totalPages }
    } else {
        (currentPage - 1).takeIf { it >= 1 }
    }

/**
 * Direction a finished horizontal drag committed to, or `null` for a no-op (drag too small AND too
 * slow). `true` = next page (leftward drag), `false` = previous page (rightward). Pure → unit-tested
 * without a gesture clock. Edge clamping is the caller's job (via [swipeTargetPage]).
 *
 * Commit when the drag crossed EITHER the distance ([commitDistancePx]) or the fling velocity
 * ([flingThresholdPx]) threshold. Direction priority is **distance first, then velocity**: once the
 * drag has travelled past [commitDistancePx] it is "armed" and the page-follow, edge glow and arming
 * haptic have already committed the user to the direction of [totalDx] — so the commit MUST follow
 * that direction even if the finger flicked back the other way at lift-off (a fast reverse on release
 * would otherwise open the opposite page and contradict every bit of feedback the user just saw).
 * Velocity decides ONLY for a short gesture that never crossed the distance threshold (a quick flick
 * that lifts on the wrong side of its start still goes the way it was thrown). A near-zero orientation
 * never picks an unstable sign. Negative = leftward drag = next page (geometric, deliberately not
 * `LayoutDirection`-aware — the forum content is LTR French).
 */
fun swipeCommitDirection(
    totalDx: Float,
    velocityX: Float,
    commitDistancePx: Float,
    flingThresholdPx: Float,
): Boolean? {
    val committedByDistance = abs(totalDx) >= commitDistancePx
    val committedByFling = abs(velocityX) >= flingThresholdPx
    val orientation = when {
        committedByDistance -> totalDx
        committedByFling -> velocityX
        else -> return null
    }
    return if (orientation == 0f) null else orientation < 0f
}

/**
 * Distance (px) past which a slow drag commits to a page change. The same value drives the visual
 * drag-follow cap and the edge hint (so the page stops growing exactly when the hint is fully lit),
 * so it is computed once per layout and shared by the gesture and [pageSwipeEdgeHint].
 * Pure (takes already-resolved pixels) → unit-tested without a `Density`.
 */
fun swipeCommitDistancePx(widthPx: Float, minCommitPx: Float): Float =
    maxOf(minCommitPx, widthPx * COMMIT_FRACTION)

/**
 * Visual translation (px) of the current page for a raw horizontal displacement [rawDx], giving the
 * drag immediate feedback (#282 follow). The sign matches [rawDx] (leftward drag → negative offset).
 * Pure → unit-tested.
 *
 * - [hasTarget] = true (a page exists that way): track the finger 1:1 up to [commitDistancePx]
 *   (`commitDistancePx` is the visual "armed" point the edge hint mirrors), then a **bounded
 *   overpull** — a `tanh` asymptote that lets the page keep giving a little while it can never drift
 *   past `commitDistancePx + OVERPULL_MAX_FRACTION × commitDistancePx`. The old linear-with-resistance
 *   ramp grew without bound on a long drag, pulling the page arbitrarily far off-screen; the asymptote
 *   keeps the gesture readable as "armed, will commit" instead of "I am tearing the page off".
 * - [hasTarget] = false (first/last page): a damped "wall", capped at [EDGE_MAX_FRACTION] of the
 *   commit distance, so a swipe into the void barely moves and reads as a boundary.
 */
fun swipeFollowOffset(rawDx: Float, commitDistancePx: Float, hasTarget: Boolean): Float {
    if (commitDistancePx <= 0f) return 0f
    val magnitude = abs(rawDx)
    val sign = if (rawDx < 0f) -1f else 1f
    val travel = if (hasTarget) {
        if (magnitude <= commitDistancePx) {
            magnitude
        } else {
            val overpull = commitDistancePx * OVERPULL_MAX_FRACTION
            val excess = magnitude - commitDistancePx
            commitDistancePx + overpull * tanh(excess / overpull)
        }
    } else {
        minOf(magnitude * EDGE_RESISTANCE, commitDistancePx * EDGE_MAX_FRACTION)
    }
    return sign * travel
}

/**
 * Whether the swipe has crossed the commit distance, i.e. releasing now would change page. Pure →
 * unit-tested. Drives the haptic "armed" tick (fired once on the rising edge by the gesture); the
 * edge-hint mirrors the same threshold continuously via [swipeEdgeHintAlpha]. Mirrors the distance
 * arm of [swipeCommitDirection]; the fling arm can still commit a shorter drag (no static "armed"
 * state for a fling, which is decided only at release).
 */
fun swipeArmed(offsetPx: Float, commitDistancePx: Float): Boolean =
    commitDistancePx > 0f && abs(offsetPx) >= commitDistancePx

/**
 * Edge-glow opacity for a swipe [progress] = `|offset| / commitDistance` (`0f` at rest, `1f` at the
 * commit/armed point, up to `1f + OVERPULL_MAX_FRACTION` — currently `1.5f` — in the bounded overpull
 * region). Pure → unit-tested.
 *
 * **Late-start** so the glow is a "you're about to commit" confirmation, not a decoration that
 * occupies the screen from the first pixel: it stays fully invisible below [EDGE_HINT_START_PROGRESS]
 * (the page already follows the finger, which conveys direction early). Three segments, continuous at
 * `progress = 1f` so the brighten is finger-driven (no separate animation primitive in the draw phase,
 * which reads state synchronously without recomposition):
 * - `0f..EDGE_HINT_START_PROGRESS`: nothing (`0f`).
 * - `EDGE_HINT_START_PROGRESS..1f`: ramp to [EDGE_HINT_MAX_ALPHA] as the swipe nears arming.
 * - past `1f`: a quick further brighten to [EDGE_HINT_ARMED_ALPHA] over the [EDGE_HINT_ARMED_RAMP]
 *   window, so crossing the threshold visibly intensifies the glow — the user sees that releasing will
 *   validate, in lock-step with the arming haptic tick.
 */
fun swipeEdgeHintAlpha(progress: Float): Float =
    when {
        progress <= EDGE_HINT_START_PROGRESS -> 0f
        progress <= 1f -> {
            val ramp = (progress - EDGE_HINT_START_PROGRESS) / (1f - EDGE_HINT_START_PROGRESS)
            ramp * EDGE_HINT_MAX_ALPHA
        }
        else -> {
            val armedProgress = ((progress - 1f) / EDGE_HINT_ARMED_RAMP).coerceAtMost(1f)
            EDGE_HINT_MAX_ALPHA + (EDGE_HINT_ARMED_ALPHA - EDGE_HINT_MAX_ALPHA) * armedProgress
        }
    }

/**
 * Edge hint for a page swipe (#282 (c), shared with the MP thread by #351). Draws a glow on the edge
 * the neighbour page is being pulled in from — right edge for a leftward drag (next page), left edge
 * for a rightward drag — whose opacity ramps with the swipe's progress toward the commit distance and
 * brightens to an armed accent ([swipeEdgeHintAlpha]) once the swipe is armed, so the user sees that
 * releasing will validate. It reads [dragOffset] at draw time only, so following the finger never
 * recomposes.
 *
 * The glow is the "release brings the neighbour page" affordance, so it is **suppressed at a blocked
 * edge**: when the dragged direction has no target page ([currentPage]/[totalPages] = first/last), the
 * damped wall already communicates the boundary and lighting a glow there would contradict it. Only a
 * direction that can actually commit gets the hint.
 *
 * Per-frame work is **allocation-free**: the two edge brushes (left / right, baked at full opacity,
 * Transparent → [accent]) and the band geometry are cached in [drawWithCache], keyed on size; the
 * per-frame draw block only reads the offset, computes the opacity and calls `drawRect(brush, …,
 * alpha = …)` on the matching sub-rect. Only the edge band (a [EDGE_HINT_WIDTH_FRACTION] sub-rect of
 * the page) is painted — never a full-screen brush, never a brush re-allocated per frame.
 *
 * Must sit **before** the gesture modifier in the modifier chain: it draws in the element's own
 * (untranslated) space, so the glow stays pinned to the screen edge while the page itself is
 * translated by the `graphicsLayer` the gesture adds further down the chain. [accent] is read from
 * the theme by the (composable) caller and passed in, since a draw scope cannot. [enabled] gates the
 * glow off with the gesture (e.g. mid nav transition for the topic; always-on for the in-place MP
 * pager).
 */
fun Modifier.pageSwipeEdgeHint(
    currentPage: Int,
    totalPages: () -> Int,
    dragOffset: MutableFloatState,
    accent: Color,
    enabled: () -> Boolean,
): Modifier = drawWithCache {
    // Cache (per size) what does not vary per frame: the band geometry and the two full-opacity edge
    // brushes. The per-frame opacity is applied via `drawRect`'s `alpha`, so neither brush nor the
    // colors `List` is re-allocated while the finger moves.
    val commitDistancePx = swipeCommitDistancePx(size.width, MIN_COMMIT_DISTANCE.toPx())
    val band = minOf(size.width * EDGE_HINT_WIDTH_FRACTION, EDGE_HINT_MAX_WIDTH.toPx())
    val rightTopLeft = Offset(size.width - band, 0f)
    val bandSize = Size(band, size.height)
    val rightBrush = Brush.horizontalGradient(
        colors = listOf(Color.Transparent, accent),
        startX = size.width - band,
        endX = size.width,
    )
    val leftBrush = Brush.horizontalGradient(
        colors = listOf(accent, Color.Transparent),
        startX = 0f,
        endX = band,
    )
    onDrawWithContent {
        drawContent()
        // No glow while the gesture is gated off (e.g. the topic's nav entry mid-transition): an
        // exiting page parked off-screen must not keep its glow.
        if (!enabled()) return@onDrawWithContent
        val offset = dragOffset.floatValue
        if (offset == 0f || commitDistancePx <= 0f) return@onDrawWithContent
        val leftward = offset < 0f
        // Suppress the glow when this direction is a blocked edge (no neighbour page to bring in).
        if (swipeTargetPage(currentPage, totalPages(), forward = leftward) == null) return@onDrawWithContent
        val alpha = swipeEdgeHintAlpha(abs(offset) / commitDistancePx)
        if (leftward) {
            drawRect(brush = rightBrush, topLeft = rightTopLeft, size = bandSize, alpha = alpha)
        } else {
            drawRect(brush = leftBrush, topLeft = Offset.Zero, size = bandSize, alpha = alpha)
        }
    }
}

// Commit thresholds: a swipe changes page only past a clear intent, never on the bare touch slop.
// Public because each consumer's gesture resolves them against its own layout/density.
private const val COMMIT_FRACTION = 0.20f
val MIN_COMMIT_DISTANCE = 72.dp
val FLING_VELOCITY_THRESHOLD = 900.dp

// Drag-follow feel (#282 (b)+(c)). All purely visual — they never affect the commit decision.
// Past the commit point the follow saturates: `tanh` asymptote bounded to this × commit distance so
// the page can never drift arbitrarily far on a long drag.
private const val OVERPULL_MAX_FRACTION = 0.5f
private const val EDGE_RESISTANCE = 0.30f // damping into a blocked edge (first/last page)
private const val EDGE_MAX_FRACTION = 0.4f // a blocked edge can travel at most this × commit distance
private const val EDGE_HINT_WIDTH_FRACTION = 0.10f // edge-glow band width, as a fraction of the page…
private val EDGE_HINT_MAX_WIDTH = 40.dp // …but never wider than this, so it reads as an edge accent
private const val EDGE_HINT_START_PROGRESS = 0.65f // glow stays invisible below this fraction of commit
private const val EDGE_HINT_MAX_ALPHA = 0.2f // edge-glow opacity right at the arming point
private const val EDGE_HINT_ARMED_ALPHA = 0.3f // brighter edge-glow once the swipe is fully armed
private const val EDGE_HINT_ARMED_RAMP = 0.15f // overpull-progress window over which the armed glow fills
