package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import kotlin.math.roundToInt

/**
 * Pure, JVM-testable mapping from a media inline node to the bucket size used by Compose's
 * [androidx.compose.foundation.text.InlineTextContent] `Placeholder`. The matching `AsyncImage`
 * fills the placeholder via `Modifier.fillMaxSize()` rather than carrying its own `dp` size — so
 * the image always tracks the `sp`-sized placeholder, even when `fontScale != 1` (accessibility).
 *
 * Why buckets and not measured intrinsic sizes (cf. arbitrage Codex on issue #109): Compose
 * `InlineTextContent` requires a **fixed** `Placeholder` size at the time the `AnnotatedString`
 * is built. Real HFR smileys range from 15×15 (`:tinostar:`, `:grilled:`) up to ~70×50 for the
 * common perso, with rare larger sprites. Async measurement via `ImageLoader.execute()` would
 * force a recomposition pass with a visible "size pop" on first scroll, plus a per-URL cache to
 * maintain. Phase 1 therefore picks two smiley buckets keyed on [SmileyKind] (the parser already
 * classifies the BBCode token via `alt`/`title`) plus one inline-image bucket.
 *
 * [smileyContentScale] is `ContentScale.Fit`: HFR perso smileys are expressive sprites, and tiny
 * 15×15 sources become unreadable on phones when left at native size. The line-height bug from the
 * initial 64×64 policy came from the old `Modifier.size(.dp)` child drifting from the `sp`
 * placeholder, plus an overly large bucket — not from scaling the sprite to the placeholder.
 * Keeping `Modifier.fillMaxSize()` makes the rendered smiley track the reserved text line under
 * `fontScale`, while the 70×50 bucket follows the dominant wikismilies corpus shape without
 * returning to the old broken 64sp line height.
 *
 * Re-evaluate in Phase 2/4 if a fixed bucket still feels wrong on real corpora; intrinsic-size
 * measurement remains the open Phase 2/4 option.
 */
internal object PostMediaDisplayPolicy {

    /**
     * Smileys are textual/emotive glyphs: fit them to the reserved bucket so even tiny historical
     * perso sprites remain visible on high-density phones. Ratio is preserved; square smileys fill
     * the bucket, wide/tall ones are letterboxed.
     */
    val smileyContentScale: ContentScale = ContentScale.Fit

    /**
     * Inline `[img]` content is arbitrary user media, not an emotive glyph. Keep the no-upscale
     * rule there so a tiny linked image is not blown up to the 240×180 inline bucket.
     */
    val inlineImageContentScale: ContentScale = ContentScale.Inside

    /**
     * Built-in HFR smileys (`:jap:`, `:o`, `:D`, `;)`, `:??:`, …) — served from `/icones/<x>.gif`
     * and `/icones/smilies/<x>.gif`. HFR ships them at 16×16 historically; the 18-sp bucket is
     * a tiny pad so a slightly taller variant doesn't get clipped at the baseline.
     */
    val builtinSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 18.sp,
        placeholderHeight = 18.sp,
    )

    /**
     * User-uploaded persona smileys (`[:cosmoschtroumpf]`, `[:rofl]`, …) — served from
     * `/images/perso/<x>.gif` or `/images/perso/<N>/<x>.gif`. Sizes are heterogeneous; the bulk
     * of the exhaustive wikismilies corpus lands on height 50 px, with width commonly ranging up
     * to 70 px. The top sizes found during dogfood were 70×50 (8047), 50×50 (2811), 67×50
     * (1142), then many W×50 variants; tiny historical sprites (15×15, 19×19, 16×16) exist too.
     *
     * 70×50 is a corpus-first bucket after dogfood on v32-v34:
     * - 40×40 + Inside fixed overlap but made common perso unreadable on phones;
     * - 56×56 + Fit made tiny sprites readable but letterboxed the dominant 70×50 shape;
     * - 70×50 + Fit keeps common perso at their native HFR ratio while upscaling tiny ones to a
     *   readable 50 px-high glyph;
     * - placeholder height stays below the old broken 64sp line rhythm.
     */
    val persoSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 70.sp,
        placeholderHeight = 50.sp,
    )

    /**
     * Inline `[img]` BBCode embedded inside a paragraph (`PostInline.InlineImage`). 240×180 is the
     * historical HFR thumbnail aspect (4:3) that fits next to wrapped text on a phone without
     * blowing the line height; landscape and portrait shots both downscale via
     * [inlineImageContentScale]. The `:core:ui` parser already strips data:/javascript:/file:
     * schemes so only http(s) URLs reach this bucket.
     */
    val inlineImage: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 240.sp,
        placeholderHeight = 180.sp,
    )

    /**
     * Block-level `[img]` rendered standalone via `PostBlock.Image`. Width matches the parent
     * column; height is bounded so a 4000×3000 RAW screenshot doesn't blow up the post and
     * destroy the scroll position.
     *
     * The min/max pair matters during the async lifecycle of `SubcomposeAsyncImage`: while
     * loading or on error the bitmap has no intrinsic size yet, so without [blockImageMinHeight]
     * the container would collapse to the height of the loading label (~16dp), making the
     * loading/error UX barely visible AND causing a layout jump when the bitmap finally
     * resolves. The min reserves a stable visual slot; the max keeps long portrait shots in
     * check (cf. issue #109 review by Codex on PR #126).
     */
    val blockImageMaxHeight: Dp = 480.dp
    val blockImageMinHeight: Dp = 160.dp

    fun smileyBox(smiley: PostInline.Smiley): InlineMediaBox = when (smiley.kind) {
        is SmileyKind.Builtin -> builtinSmiley
        is SmileyKind.Perso -> persoSmiley
    }
}

internal data class InlineMediaBox(
    val placeholderWidth: TextUnit,
    val placeholderHeight: TextUnit,
)

/**
 * Source dimensions in raw pixels (not Dp/sp) — Coil hands intrinsic image sizes back in pixels,
 * so the policy stays in pixel arithmetic for the pure-JVM corpus tests.
 */
internal data class PixelSize(val width: Int, val height: Int)

/**
 * Pure mirror of [ContentScale.Fit]: uniformly scale [source] so it fits inside [bucket] while
 * preserving aspect ratio. Returns the resulting size.
 *
 * The result is clamped to at least 1×1 to guard against extreme aspect ratios where rounding
 * would otherwise collapse one dimension to 0 (e.g. a 1×100 banner downscaled into a 70×50
 * bucket would `roundToInt()` to 0×50 without the clamp — visually invisible, technically
 * "fitting").
 *
 * Exposed and tested in pure JVM so the corpus-of-real-HFR-perso assertions don't need a
 * Compose runtime. **Important** : this function models the `Fit` decision at density = 1
 * and fontScale = 1. At runtime the placeholder is `70.sp × 50.sp × density × fontScale` pixels, so the
 * absolute output sizes shift accordingly — but the *invariant* "preserve aspect ratio and fit
 * the bucket" survives any positive density/fontScale because `Fit` is invariant by uniform
 * scaling of the bucket. The numeric examples (`15×15 → 50×50`, etc.) are correct **at density
 * 1**; on a real xxhdpi device a 70×50 sprite is scaled to a different absolute size, but the
 * same proportions and fit guarantee hold.
 */
internal fun fitScaledMediaSize(source: PixelSize, bucket: PixelSize): PixelSize {
    require(source.width > 0 && source.height > 0) { "source must be positive" }
    require(bucket.width > 0 && bucket.height > 0) { "bucket must be positive" }
    val scale = minOf(
        bucket.width.toFloat() / source.width.toFloat(),
        bucket.height.toFloat() / source.height.toFloat(),
    )
    return PixelSize(
        width = (source.width * scale).roundToInt().coerceAtLeast(1),
        height = (source.height * scale).roundToInt().coerceAtLeast(1),
    )
}
