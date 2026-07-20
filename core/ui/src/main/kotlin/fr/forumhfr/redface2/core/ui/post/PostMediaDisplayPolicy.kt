package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
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
 * `[img]` — inline AND block — follows ONE policy since #610, the HFR-web parity rule
 * `img { max-width: 90%; max-height: 200px }` ([imageParityDisplaySize]): measured intrinsic native
 * size, no upscale, height capped to [IMAGE_MAX_HEIGHT_UNITS], width capped to the relative
 * `0.9 × contentWidth`. The inline path (`imageDisplayBox` in PostRenderer, #224 option A) applies it
 * in **sp** via the same `IntrinsicMediaSizeCache`; the block path ([blockImageDisplaySize]) applies
 * it in **dp** — so a lone posted photo and the same photo inside prose render at the same size (the
 * pre-#610 divergence was the issue). The **production cold fallback** (unmeasured inline `[img]`) is
 * the one-line [INLINE_IMAGE_MIN_HEIGHT_SP] square in `imageDisplayBox` (#253, no giant Fit flash).
 * The fixed 240×180 [inlineImage] bucket is now only the **default `collectInlineMedia` resolver**
 * (legacy bucket exercised by tests), not the runtime fallback. This kills the empty frame around a
 * small reaction image, the overflow in a narrow quote, and the full-width blow-up of block images.
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

    fun smileyBox(smiley: PostInline.Smiley): InlineMediaBox = when (smiley.kind) {
        is SmileyKind.Builtin -> builtinSmiley
        is SmileyKind.Perso -> persoSmiley
    }

    /**
     * #249/#610 — exact display size (in **dp** units) for a MEASURED block `[img]`, computed BEFORE
     * the bitmap arrives so the container occupies exactly the slot the loaded image will fill, hence
     * zero layout shift (anti-CLS, #249) when it crossfades in.
     *
     * #610/#842 — the size is the unified parity policy ([imageParityDisplaySize]): native size, no
     * upscale, width ≤ [SMILEY_RELATIVE_MAX_WIDTH_FRACTION] × [availableWidthDp] (web `max-width: 90%`),
     * height ≤ [maxHeightDp]. #610 passed a flat 200 here (matching the inline sp cap); #842 lets the
     * caller pass the mobile-recalibrated [blockImageMaxHeightDp] (`max(400, 0.5 × screenHeightDp)`) so
     * a square/portrait photo reaches ~90 % width instead of being squeezed to ~48 % by a 200 dp cap
     * with no web basis. Before #610: a measured block image FILLED the column width — upscaling any
     * source narrower than the column — with its height `width × h/w` clamped to the legacy grow-on-load
     * slot; it now renders at its capped NATIVE size (cold slot = §6 v1.4 since #957).
     *
     * [measured] is `null` for a not-yet-measured image — a cold cache before the measure effect lands,
     * or a measurement failure (dead host / 404). Both the paragraph effect (#175/#224) and, since the
     * #249 follow-up, the standalone `PostBlock.Image` effect feed the cache; callers fall back to the
     * deterministic §6 COLD slot (v1.4, #957) until (or unless) a size lands.
     */
    fun blockImageDisplaySize(
        measured: PixelSize?,
        availableWidthDp: Float,
        maxHeightDp: Int = IMAGE_MAX_HEIGHT_UNITS,
    ): PixelSize? {
        val size = measured?.takeIf { it.width > 0 && it.height > 0 }
        if (size == null || availableWidthDp <= 0f) return null
        val maxWidthDp = (availableWidthDp * SMILEY_RELATIVE_MAX_WIDTH_FRACTION).roundToInt()
        return imageParityDisplaySize(size, maxWidthUnits = maxWidthDp, maxHeightUnits = maxHeightDp)
    }

    /**
     * #416 — box for a DEAD smiley sprite (recorded as a fresh measurement failure). HFR's BBCode
     * engine turns ANY `:word:` into an `<img>` without checking existence, so an unknown code is
     * served as a 404 gif : web/RF1 then show the typed token at text size. The token replaces the
     * sprite in the inline content, so its placeholder must fit body-sized text, not a 16-px
     * sprite — width scales with the token length, clamped to the same relative cap as sprites
     * (the token of a long perso name must not overflow a narrow quote).
     */
    fun deadSmileyTokenBox(token: String, maxWidthSp: Int): InlineMediaBox {
        val width = (token.length * DEAD_SMILEY_TOKEN_CHAR_SP).coerceAtMost(maxWidthSp)
        return InlineMediaBox(width.sp, DEAD_SMILEY_TOKEN_HEIGHT_SP.sp)
    }

    /**
     * Generous bodyMedium average glyph advance (the token is mostly lowercase + `:`/`[]`) — a
     * slightly wide box only adds whitespace, a narrow one clips the token (the #416 symptom).
     */
    internal const val DEAD_SMILEY_TOKEN_CHAR_SP = 8

    /** One bodyMedium line (14 sp glyphs + breathing room), matching the surrounding text. */
    internal const val DEAD_SMILEY_TOKEN_HEIGHT_SP = 20
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
 * #959/[AMENDEMENT-v1.5-1] (D1 approuvée XaTriX) — the DEDICATED relative width cap of content
 * images (`fImage`), applied identically on the three image paths: inline ([imageDisplayBox]),
 * measured block ([PostMediaDisplayPolicy.blockImageDisplaySize]) and the cold block slot
 * (`COLD_BLOCK_WIDTH_FRACTION` is a LOCKED alias of this constant — single source of truth,
 * pinned by test). 0.95 is an assumed product divergence from the web `max-width:90%` (better
 * use of narrow phone columns). The smiley cap stays a separate 0.9
 * ([SMILEY_RELATIVE_MAX_WIDTH_FRACTION], §9 untouchable).
 */
internal const val IMAGE_RELATIVE_MAX_WIDTH_FRACTION = 0.95f

/**
 * #959 (Lot 3, contrat v1.5 §3) — the DEDICATED content-image sizing equation, all in PHYSICAL
 * pixels: `scale = min(1, maxWidthPx/w, maxHeightPx/h)`, the width rounds, and the height
 * DERIVES from the ROUNDED width by the native ratio — never rounded independently (§3 letter;
 * the derived height may exceed the height cap by one pixel, accepted: the caps constrain the
 * SCALE, not the rounded result). No-upscale comes from the `1` term — in physical pixels
 * (1 source px never spreads past 1 screen px), which is the whole density-aware point of the
 * lot. The HOSTS convert their caps (sp/dp → px) BEFORE calling and convert the result back at
 * the Compose boundary — no px↔dp/sp comparison ever happens in the policy (cadrage Sol r1).
 * A non-positive [maxWidthPx] applies no width cap (defensive, mirrors [imageParityDisplaySize]:
 * a zero-width container must not collapse the image). Both axes floor to 1 px AFTER the
 * derivation, so a degenerate rounded-to-zero width yields a 1×1 slot — never a layout bomb.
 * Smileys keep [intrinsicSmileyDisplaySize] strictly unchanged (§9: 240/70/0.9 untouchable).
 */
internal fun imageDisplaySizePx(nativePx: IntSize, maxWidthPx: Int, maxHeightPx: Int): IntSize {
    require(nativePx.width > 0 && nativePx.height > 0) { "nativePx must be positive" }
    val scale = minOf(
        1f,
        if (maxWidthPx > 0) maxWidthPx.toFloat() / nativePx.width else 1f,
        maxHeightPx.toFloat() / nativePx.height,
    )
    val width = (nativePx.width * scale).roundToInt()
    val height = (width.toFloat() * nativePx.height / nativePx.width).roundToInt()
    return IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
}

/**
 * #175 — provisional placeholder sizes used while a perso smiley's intrinsic size is still being
 * measured (cold cache), to minimise reflow when the real size lands. Builtins are never measured:
 * they use their known ~16×16 HFR icon size directly. Perso falls back to the dominant 70×50 corpus
 * size until measurement completes.
 */
internal val builtinPreseedSize = PixelSize(16, 16)
internal val persoColdFallbackSize = PixelSize(70, 50)

/**
 * Height cap for the INLINE `[img]` path and the pure/legacy default of [imageParityDisplaySize]
 * (native px treated as logical units, fed as `.sp` inline so the image tracks the text size).
 *
 * #610 originally applied this same 200 to BOTH paths as `img { max-height: 200px }` "web parity".
 * #842 walked that back for the BLOCK path only (see [blockImageMaxHeightDp]): the HFR fixtures carry
 * NO `max-height` on post images — the only web rule is `img { max-width: 90% }` — and 200 dp on a
 * ~360-411 dp phone column binds any image narrower than ~1.6:1, squeezing a square photo to ~48 %
 * width (the #842 report). The INLINE path keeps 200 sp: in-prose images stay conservative so a large
 * reaction image never grows tall enough to break the text flow, and small inline sources (cc-image
 * 16×16, reactions) never reach the cap anyway (no upscale).
 */
internal const val IMAGE_MAX_HEIGHT_UNITS = 200

/**
 * #842 — mobile-recalibrated height cap (in **dp**) for the BLOCK `[img]` path, replacing the flat
 * [IMAGE_MAX_HEIGHT_UNITS] that #610 applied there. Real photos land on the block path (a structural
 * MediaRun since #957 — contract v1.4 §2); the cap is relative to the viewport height so it scales
 * with the device while still guarding against a 4000×3000 RAW screenshot blowing up the post:
 * `max(400 dp, 0.5 × screenHeightDp)`. The 400 dp floor keeps a near-square image at ~90 % width on a
 * typical ~410 dp-wide phone (there the 90 % width cap ≈ 370 dp binds first, so the height cap no
 * longer bites), instead of re-creating a visible height cap. Pure so the caller
 * (`PostRenderer.BlockImage`, which knows `screenHeightDp` via `LocalConfiguration`) stays a one-liner
 * and the recalibration is JVM-testable.
 */
internal const val BLOCK_IMAGE_MAX_HEIGHT_FLOOR_DP = 400
internal const val BLOCK_IMAGE_MAX_HEIGHT_SCREEN_FRACTION = 0.5f

internal fun blockImageMaxHeightDp(screenHeightDp: Int): Int = maxOf(
    BLOCK_IMAGE_MAX_HEIGHT_FLOOR_DP,
    (screenHeightDp * BLOCK_IMAGE_MAX_HEIGHT_SCREEN_FRACTION).roundToInt(),
)


/**
 * #257/#610 — fixed decode size (px) for an inline `[img]`: the render request decodes at this bound
 * (INEXACT Fit) so Coil produces ONE stable bitmap that survives the cold→measured box growth without
 * a re-decode + pixelated upscale. Before #610 the request size derived from the absolute 240×200 sp
 * display cap converted to px (density × fontScale) and clamped here; that width cap is now relative
 * to the container, so the request uses this flat bound directly — it covers `0.9 × container` at any
 * realistic phone density/fontScale, stays cheap to decode and cache, and Coil never decodes past the
 * source, so a small image still decodes at native.
 *
 * Distinct from `INTRINSIC_PROBE_SIZE_PX` (also 1024): that one bounds the **measure** probe in
 * `IntrinsicMediaSizeMeasurer`, this one bounds the **render** decode here. Same value, different role —
 * don't merge them.
 */
internal const val INLINE_IMAGE_DECODE_CAP_PX = 1024

/**
 * #224/#253 — minimum display **height** (sp) for an inline `[img]`, so a sub-16 low-res source can't
 * render below ~one text line. The community "cc-image" emoji (served as 16×16 PNGs) sits exactly at
 * this floor → rendered at its native 16 (dogfood: the right size next to text, per @XaaT); anything
 * smaller is floored up to 16 (filled by [inlineImageContentScale] = Fit), anything taller is untouched
 * (no photo blow-up). 16 ≈ one text line (just under bodyMedium's 20sp lineHeight).
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
 * #610 — the unified `[img]` display policy shared by the inline path (`imageDisplayBox` in
 * PostRenderer, sp units) and the block path ([PostMediaDisplayPolicy.blockImageDisplaySize], dp
 * units): HFR-web `img { max-width: 90%; max-height: 200px }` parity. No upscale, height capped to
 * [maxHeightUnits] (default [IMAGE_MAX_HEIGHT_UNITS]), width capped to [maxWidthUnits] — the caller
 * passes the RELATIVE cap (≈ `0.9 ×` its container width, in its own units), the only width limit
 * since #610. A non-positive [maxWidthUnits] applies no width cap (defensive, mirrors [capToWidth]:
 * a zero-width container must not collapse the image to a sliver).
 *
 * Thin wrapper over [intrinsicSmileyDisplaySize] (same math: uniform scale ≤ 1, ≥ 1 px per axis),
 * named separately so `[img]` call sites read as the #610 contract and can evolve independently of
 * the smiley caps.
 */
internal fun imageParityDisplaySize(
    nativePx: PixelSize,
    maxWidthUnits: Int,
    maxHeightUnits: Int = IMAGE_MAX_HEIGHT_UNITS,
): PixelSize = intrinsicSmileyDisplaySize(
    nativePx = nativePx,
    maxWidthSp = if (maxWidthUnits > 0) maxWidthUnits else Int.MAX_VALUE,
    maxHeightSp = maxHeightUnits,
)

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
