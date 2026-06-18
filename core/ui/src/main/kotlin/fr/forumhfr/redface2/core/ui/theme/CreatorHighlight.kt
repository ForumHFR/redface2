package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import fr.forumhfr.redface2.core.ui.post.rememberAnimationsEnabled

/**
 * #221 — the gold "sheen" [Brush] applied to a Redface 2 creator's pseudo (see
 * [fr.forumhfr.redface2.core.domain.author.isRf2Creator]). A metallic base gold with a brighter band
 * that slowly sweeps across the text — the "doré étincelant" easter egg.
 *
 * Two palettes keep the pseudo legible on every theme: a deeper gold on light surfaces (so it does not
 * wash out on white) and a brighter gold on dark / AMOLED. Dark is detected from the surface luminance
 * so it follows AMOLED too, not just the system dark flag.
 *
 * Accessibility (#221 §5 "réduire les animations") : when the OS animator scale is 0
 * ([rememberAnimationsEnabled], same source Compose's own animations honour) the sweep is dropped and a
 * STATIC base→highlight→base gold gradient is returned — the pseudo still reads "doré", just without
 * motion.
 *
 * Caller note: read the returned brush from a LEAF composable (e.g. a dedicated pseudo `Text`) so the
 * per-frame animation invalidates only that text node, never the enclosing post card.
 */
@Composable
fun rememberCreatorPseudoBrush(): Brush {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE
    val base = if (dark) CreatorGoldBaseDark else CreatorGoldBaseLight
    val highlight = if (dark) CreatorGoldHighlightDark else CreatorGoldHighlightLight

    if (!rememberAnimationsEnabled()) {
        return remember(base, highlight) { Brush.linearGradient(listOf(base, highlight, base)) }
    }

    val transition = rememberInfiniteTransition(label = "creator_gold")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CREATOR_SHEEN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "creator_gold_sheen",
    )
    return creatorSheenBrush(travel, base, highlight)
}

/**
 * A base-gold gradient carrying a narrow [highlight] band centred at the swept position. [travel] in
 * [0, 1] moves the band from off-left to off-right (it sits outside the text most of the period, so the
 * pseudo rests as plain gold between sweeps).
 */
private fun creatorSheenBrush(travel: Float, base: Color, highlight: Color): Brush =
    Brush.linearGradient(colorStops = creatorSheenStops(travel, base, highlight).toTypedArray())

/**
 * Builds the gradient colour stops for a sweep position. Every interior stop is added only while it
 * lies STRICTLY inside `(0, 1)`, so the endpoints `0f`/`1f` are never duplicated and the resulting list
 * is guaranteed strictly increasing — `Brush.linearGradient` rejects out-of-order / duplicate stops at
 * the band's extremities (`travel = 0` → right edge at `0f`; `travel = 1` → left edge at `1f`). Pure and
 * `internal` so [CreatorHighlightTest] can assert the monotonicity invariant across the whole sweep.
 */
internal fun creatorSheenStops(travel: Float, base: Color, highlight: Color): List<Pair<Float, Color>> {
    // Map travel so the band fully enters and exits: centre runs from -BAND to 1 + BAND.
    val centre = travel * (1f + 2f * SHEEN_BAND) - SHEEN_BAND
    return buildList {
        add(0f to base)
        val left = centre - SHEEN_BAND
        if (left > 0f && left < 1f) add(left to base)
        if (centre > 0f && centre < 1f) add(centre to highlight)
        val right = centre + SHEEN_BAND
        if (right > 0f && right < 1f) add(right to base)
        add(1f to base)
    }
}

/** Half-width of the bright sweeping band, as a fraction of the text width. */
private const val SHEEN_BAND = 0.22f

/** One full sweep. Slow enough to read as a gentle shimmer, not a strobe. */
private const val CREATOR_SHEEN_PERIOD_MS = 2600

/** Surfaces darker than this read as a dark/AMOLED theme → brighter gold palette. */
private const val DARK_SURFACE_LUMINANCE = 0.5f

// Deeper gold on light surfaces so the SemiBold pseudo keeps enough contrast on white. The highlight
// stays a saturated mid-gold (not a pale wash) so even the sweep's brightest pass remains legible — a
// pale gold like #FFD700 is inherently low-contrast on white, so on light we trade some sparkle for
// readability (#221 §5); the full brilliance lives on the dark palette below.
private val CreatorGoldBaseLight = Color(0xFF7A5C00)
private val CreatorGoldHighlightLight = Color(0xFFC8901A)

// Brighter gold on dark / AMOLED where a deep gold would be muddy.
private val CreatorGoldBaseDark = Color(0xFFD4AF37)
private val CreatorGoldHighlightDark = Color(0xFFFFE9A8)
