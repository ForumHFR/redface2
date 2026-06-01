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
 * Pure, JVM-testable sizing for inline post media (smileys + inline images). The matching `AsyncImage`
 * fills the placeholder via `Modifier.fillMaxSize()` rather than carrying its own `dp` size — so the
 * image always tracks the `sp`-sized placeholder, even when `fontScale != 1` (accessibility).
 *
 * #175 — SMILEYS are sized by their **measured intrinsic** native dimensions (no-upscale + cap, see
 * [intrinsicSmileyDisplaySize] + [capToWidth]), fed by an async Coil measurement cached per URL
 * (`IntrinsicMediaSizeCache`, driven by `PostRenderer.ParagraphBlock`). The intrinsic px are treated
 * as logical/CSS pixels (→ `.sp` directly, NOT `/density`), reproducing the web/RF1 rendering. The old
 * fixed [builtinSmiley]/[persoSmiley] buckets now survive only as fallbacks: builtins use their known
 * small size directly, while perso smileys use the 70×50 cold-cache fallback while measurement is
 * in flight (and as the default `collectInlineMedia` resolver in tests).
 *
 * Inline `[img]` ([inlineImage]) is now sized like smileys (#224 option A): measured intrinsic native
 * size (no-upscale + absolute cap [INLINE_IMAGE_MAX_WIDTH_SP]×[INLINE_IMAGE_MAX_HEIGHT_SP]) then the
 * relative `0.9 × contentWidth` cap, via the same `IntrinsicMediaSizeCache` + `imageDisplayBox` in
 * PostRenderer. The **production cold fallback** (unmeasured `[img]`) is the one-line
 * [INLINE_IMAGE_MIN_HEIGHT_SP] square in `imageDisplayBox` (#253, no giant Fit flash). The fixed
 * 240×180 [inlineImage] bucket is now only the **default `collectInlineMedia` resolver** (legacy bucket
 * exercised by tests), not the runtime fallback. This kills both the empty frame around a small reaction
 * image and the overflow in a narrow quote.
 *
 * Why this took fixed buckets as a stopgap in #109: Compose `InlineTextContent` requires a **fixed**
 * `Placeholder` size when the `AnnotatedString` is built, so intrinsic sizing needs async-measure →
 * cache → recompose; a cold first paint can still reflow once before the measured size lands.
 */
internal object PostMediaDisplayPolicy {

    /**
     * #175 — the smiley placeholder is now sized to the smiley's measured intrinsic size (or a
     * provisional cold-cache fallback), so [ContentScale.Fit] just maps the bitmap into that
     * same-ratio box without distortion. It no longer upscales a tiny sprite to an oversized fixed
     * bucket (the pre-#175 model): the size difference between a 15×15 and a 70×50 now comes from
     * the source, matching web/RF1.
     */
    val smileyContentScale: ContentScale = ContentScale.Fit

    /**
     * Inline `[img]` uses [ContentScale.Fit] (like smileys) so the bitmap **fills** its placeholder
     * box. The no-upscale decision lives in the BOX sizing ([imageDisplayBox]: measured intrinsic,
     * capped, floored to [INLINE_IMAGE_MIN_HEIGHT_SP]) — not the content scale. With `Inside` a tiny
     * 16×16 cc-image emoji stayed 16×16 centred in its floored box (illegible in dogfood); `Fit` scales
     * it up to fill the box, while a large photo still scales DOWN into its capped box.
     */
    val inlineImageContentScale: ContentScale = ContentScale.Fit

    /**
     * **Not the #175 production size** — since intrinsic sizing landed, this fixed box survives only
     * as the default [collectInlineMedia] resolver used by tests (production sizes from the measured
     * native px, see the object KDoc; the in-flight cold fallback is [builtinPreseedSize]).
     *
     * Built-in HFR smileys (`:jap:`, `:o`, `:D`, `;)`, `:??:`, …) — served from `/icones/<x>.gif`
     * and `/icones/smilies/<x>.gif`. HFR ships them at 16×16 historically; the 18-sp box is
     * a tiny pad so a slightly taller variant doesn't get clipped at the baseline.
     */
    val builtinSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 18.sp,
        placeholderHeight = 18.sp,
    )

    /**
     * **Not the #175 production size** — since intrinsic sizing landed, this fixed box survives only
     * as the default [collectInlineMedia] resolver used by tests (production sizes from the measured
     * native px, see the object KDoc; the in-flight cold fallback is [persoColdFallbackSize]).
     *
     * User-uploaded persona smileys (`[:cosmoschtroumpf]`, `[:rofl]`, …) — served from
     * `/images/perso/<x>.gif` or `/images/perso/<N>/<x>.gif`. Sizes are heterogeneous; the bulk
     * of the exhaustive wikismilies corpus lands on height 50 px, with width commonly ranging up
     * to 70 px. The top sizes found during dogfood were 70×50 (8047), 50×50 (2811), 67×50
     * (1142), then many W×50 variants; tiny historical sprites (15×15, 19×19, 16×16) exist too.
     * 70×50 (the dominant corpus size) is therefore the natural provisional size to minimise reflow
     * before the measured size lands.
     */
    val persoSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 70.sp,
        placeholderHeight = 50.sp,
    )

    /**
     * Legacy 240×180 inline `[img]` bucket. 240×180 is the historical HFR thumbnail aspect (4:3); the
     * `:core:ui` parser strips data:/javascript:/file: schemes so only http(s) URLs reach it.
     *
     * Since #224 option A this is **no longer the runtime sizing**: production `[img]` size is the
     * measured intrinsic native size (no-upscale + capped) from `imageDisplayBox`, and the production
     * cold fallback (unmeasured) is the [INLINE_IMAGE_MIN_HEIGHT_SP] square (#253). This bucket now only
     * serves as the **default `collectInlineMedia` resolver** (the legacy value exercised by tests).
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
 * #175 note: this is **no longer on the production smiley path** (which uses
 * [intrinsicSmileyDisplaySize] + [capToWidth]); it is retained as a pure-JVM `Fit` reference and is
 * still exercised by tests. Cross-referenced from [intrinsicSmileyDisplaySize] only for its shared
 * anti-collapse clamp.
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

/**
 * #175 — absolute caps for an inline smiley, in **sp** (the intrinsic native px are treated as
 * logical/CSS px and fed to the placeholder as `.sp` directly — see [intrinsicSmileyDisplaySize]).
 *
 * Calibrated against the wikismilies corpus (dominant 70×50, then 50×50/67×50): the height cap of
 * 70sp lets the dominant 70×50 pass through untouched (~3.5× a 20sp body line, like the web) and
 * only clamps rare oversized sprites. The width cap is a coarse abuse guard; the *real* horizontal
 * limit is the relative `0.9 × contentWidth` (RF1's `img { max-width: 90% }`) applied renderer-side
 * where the container width is known. Values are starting points to calibrate in dogfood (#175).
 */
internal const val SMILEY_MAX_HEIGHT_SP = 70
internal const val SMILEY_MAX_WIDTH_SP = 240

/**
 * #175 — RF1's `img { max-width: 90% }` ported to the smiley path: a smiley never occupies more than
 * this fraction of the available content width. Applied renderer-side (the only place the container
 * width is known, via `BoxWithConstraints`) so a large perso shrinks to fit instead of overflowing /
 * overlapping the text in a narrow quote — and it stays correct as the width shrinks with quote depth.
 */
internal const val SMILEY_RELATIVE_MAX_WIDTH_FRACTION = 0.9f

/**
 * #175 — provisional placeholder sizes used while a perso smiley's intrinsic size is still being
 * measured (cold cache), to minimise reflow when the real size lands. Builtins are never measured:
 * they use their known ~16×16 HFR icon size directly. Perso falls back to the dominant 70×50 corpus
 * size until measurement completes.
 */
internal val builtinPreseedSize = PixelSize(16, 16)
internal val persoColdFallbackSize = PixelSize(70, 50)

/**
 * #224 (option A) — absolute caps for an inline `[img]`, in **sp** (intrinsic native px treated as
 * logical/CSS px, like the smiley path). More generous than the smiley height cap: an inline reaction
 * image or embedded photo can be taller than an emotive glyph, yet stays bounded so it never dominates
 * the post (a genuinely large photo belongs in a standalone `PostBlock.Image`, [blockImageMaxHeight]).
 * The real horizontal limit is the relative `0.9 × contentWidth` applied renderer-side via [capToWidth].
 */
internal const val INLINE_IMAGE_MAX_HEIGHT_SP = 200
internal const val INLINE_IMAGE_MAX_WIDTH_SP = 240

/**
 * #224 — minimum display **height** (sp) for an inline `[img]`. Tiny low-res sources — typically the
 * community "cc-image" emoji served as 16×16 PNGs — read too small at native size (dogfood vs RF1).
 * Floor the box height so they upscale to a legible size (filled by [inlineImageContentScale] = Fit);
 * images already taller are untouched (no photo blow-up). 20 ≈ the bodyMedium line height.
 */
internal const val INLINE_IMAGE_MIN_HEIGHT_SP = 16

/**
 * #175/#224 — the no-upscale + cap policy that replaces the fixed [InlineMediaBox] buckets for inline
 * media (smileys and inline `[img]`; callers pass the per-kind caps — the defaults are the smiley caps).
 *
 * Given a smiley's intrinsic native size [nativePx] (raw bitmap px from Coil, treated as logical/CSS
 * px), returns the display size to feed the placeholder (as `.sp`):
 *  - **no upscale**: never larger than native — a 15×15 stays 15×15 (the old `Fit` bucket blew it up
 *    to 50×50), a 70×50 stays 70×50, a `:jap:` ~16×16 stays small. The builtin↔perso size difference
 *    emerges from the source, matching RF1/web ;
 *  - **cap down** to [maxWidthSp]×[maxHeightSp] preserving aspect ratio (a huge sprite shrinks) ;
 *  - clamped ≥ 1 per axis (same anti-collapse guard as [fitScaledMediaSize]).
 *
 * The *relative* width cap (≈`0.9 × contentWidth`) is applied separately in the renderer, which is
 * the only place the container width is known (BoxWithConstraints).
 */
internal fun intrinsicSmileyDisplaySize(
    nativePx: PixelSize,
    maxWidthSp: Int = SMILEY_MAX_WIDTH_SP,
    maxHeightSp: Int = SMILEY_MAX_HEIGHT_SP,
): PixelSize {
    require(nativePx.width > 0 && nativePx.height > 0) { "nativePx must be positive" }
    val scale = minOf(
        maxWidthSp.toFloat() / nativePx.width.toFloat(),
        maxHeightSp.toFloat() / nativePx.height.toFloat(),
        1f,
    )
    return PixelSize(
        width = (nativePx.width * scale).roundToInt().coerceAtLeast(1),
        height = (nativePx.height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * #175 — scale [size] down so its width fits [maxWidthSp] (the relative cap, ≈90% of the content
 * width), preserving aspect ratio and clamping to ≥1 per axis. A no-op when it already fits or when
 * [maxWidthSp] is non-positive (defensive: a zero-width container should not collapse the smiley).
 */
internal fun capToWidth(size: PixelSize, maxWidthSp: Int): PixelSize {
    if (maxWidthSp <= 0 || size.width <= maxWidthSp) return size
    val scale = maxWidthSp.toFloat() / size.width.toFloat()
    return PixelSize(
        width = maxWidthSp,
        height = (size.height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * #224 — scale [size] UP so its height reaches [minHeightSp], preserving aspect ratio, when it is
 * smaller (a no-op otherwise). Counterpart to [capToWidth]: makes a tiny inline `[img]` (cc-image
 * emoji served as 16×16) legible instead of microscopic. The bitmap fills the resulting box via
 * [PostMediaDisplayPolicy.inlineImageContentScale] = Fit. Clamped ≥ 1 per axis.
 */
internal fun upscaleToMinHeight(size: PixelSize, minHeightSp: Int): PixelSize {
    if (minHeightSp <= 0 || size.height >= minHeightSp) return size
    val scale = minHeightSp.toFloat() / size.height.toFloat()
    return PixelSize(
        width = (size.width * scale).roundToInt().coerceAtLeast(1),
        height = minHeightSp,
    )
}
