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
 * [inlineMediaContentScale] is `ContentScale.Inside` (downscale only, never upscale): a common
 * 50×50 perso stays readable at native size in the 56×56 bucket, a 70×50 perso lands as 56×40,
 * and a tiny 15×15 perso stays at 15×15 centred with padding (no pixelated upscale). The previous
 * `ContentScale.Fit` upscaled small sprites and combined with a 64×64 bucket made line layout
 * buckle on `bodyMedium` (`lineHeight = 20.sp`) — see post #74625731 / fix PR for the bug capture.
 *
 * Re-evaluate in Phase 2/4 if a fixed bucket still feels wrong on real corpora; intrinsic-size
 * measurement remains the open Phase 2/4 option.
 */
internal object PostMediaDisplayPolicy {

    /**
     * `ContentScale.Inside` for inline media (smileys + inline `[img]`): downscale to fit the
     * bucket while preserving aspect ratio, but **never** upscale beyond the source's intrinsic
     * pixel size. Small sprites (15×15 perso) stay at native size centred in the bucket — no
     * pixelation, no blocky scale-up.
     */
    val inlineMediaContentScale: ContentScale = ContentScale.Inside

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
     * of the corpus measured live (~25 GIFs sampled) lands at 50×50 to 70×50 native pixels, with
     * a fraction at 15-30 px and rare large outliers.
     *
     * 56×56 is a readability-first bucket that:
     * - keeps median 50×50 perso at native size, instead of shrinking them on smartphone screens;
     * - downscales common 70×50 perso to 56×40 while preserving their ratio;
     * - leaves a 15×15 perso at native 15×15 thanks to `ContentScale.Inside` (no upscale);
     * - stays below the old broken 64sp bucket, so the line-height bump remains bounded.
     */
    val persoSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 56.sp,
        placeholderHeight = 56.sp,
    )

    /**
     * Inline `[img]` BBCode embedded inside a paragraph (`PostInline.InlineImage`). 240×180 is the
     * historical HFR thumbnail aspect (4:3) that fits next to wrapped text on a phone without
     * blowing the line height; landscape and portrait shots both downscale via
     * [inlineMediaContentScale]. The `:core:ui` parser already strips data:/javascript:/file:
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
 * Pure mirror of [ContentScale.Inside]: downscale [source] uniformly so it fits inside [bucket]
 * while preserving aspect ratio, but never scale up. Returns the resulting size.
 *
 * The result is clamped to at least 1×1 to guard against extreme aspect ratios where rounding
 * would otherwise collapse one dimension to 0 (e.g. a 1×100 banner downscaled into a 56×56
 * bucket would `roundToInt()` to 0×56 without the clamp — visually invisible, technically
 * "fitting").
 *
 * Exposed and tested in pure JVM so the corpus-of-real-HFR-perso assertions don't need a
 * Compose runtime. **Important** : this function models the `Inside` decision at density = 1
 * and fontScale = 1. At runtime the placeholder is `56.sp × density × fontScale` pixels, so the
 * absolute output sizes shift accordingly — but the *invariant* "never upscale, preserve aspect
 * ratio" survives any positive density/fontScale because `Inside` is invariant by uniform
 * scaling of the bucket. The numeric examples (`70×50 → 56×40`, etc.) are correct **at density
 * 1**; on a real xxhdpi device a 70×50 sprite is downscaled to a different absolute size, but
 * the same proportions and the same "no upscale" guarantee.
 */
internal fun insideScaledMediaSize(source: PixelSize, bucket: PixelSize): PixelSize {
    require(source.width > 0 && source.height > 0) { "source must be positive" }
    require(bucket.width > 0 && bucket.height > 0) { "bucket must be positive" }
    val scale = minOf(
        1f,
        bucket.width.toFloat() / source.width.toFloat(),
        bucket.height.toFloat() / source.height.toFloat(),
    )
    return PixelSize(
        width = (source.width * scale).roundToInt().coerceAtLeast(1),
        height = (source.height * scale).roundToInt().coerceAtLeast(1),
    )
}
