package fr.forumhfr.redface2.core.ui.post

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind

/**
 * Pure, JVM-testable mapping from a media inline node to the bucket size used both by Compose's
 * [androidx.compose.foundation.text.InlineTextContent] `Placeholder` and by the inner
 * `Modifier.size(...)` of the matching `AsyncImage`.
 *
 * Why buckets and not measured intrinsic sizes (cf. arbitrage Codex on issue #109): Compose
 * `InlineTextContent` requires a **fixed** `Placeholder` size at the time the `AnnotatedString`
 * is built. Real HFR smileys range from 16×16 (`:jap:`) to ~200×150 (some perso sprites). Async
 * measurement via `ImageLoader.execute()` would force a recomposition pass with a visible "size
 * pop" on first scroll, plus a per-URL cache to maintain. Phase 1 therefore picks two smiley
 * buckets keyed on [SmileyKind] (the parser already classifies the BBCode token via `alt`/`title`)
 * plus one inline-image bucket. `ContentScale.Fit` prevents squash inside each bucket — a tall
 * 80×60 perso lands as 64×48 centred, not stretched to 64×64.
 *
 * Re-evaluate in Phase 2/4 if the perso downscale becomes a UX problem in practice.
 */
internal object PostMediaDisplayPolicy {

    /**
     * Built-in HFR smileys (`:jap:`, `:o`, `:D`, `;)`, `:??:`, …) — served from `/icones/<x>.gif`
     * and `/icones/smilies/<x>.gif`. HFR ships them at 16×16 historically; the 18-unit bucket is
     * a tiny pad so a slightly taller variant doesn't get clipped at the baseline.
     */
    val builtinSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 18.sp,
        placeholderHeight = 18.sp,
        modifierWidth = 18.dp,
        modifierHeight = 18.dp,
    )

    /**
     * User-uploaded persona smileys (`[:cosmoschtroumpf]`, `[:rofl]`, …) — served from
     * `/images/perso/<x>.gif` or `/images/perso/<N>/<x>.gif`. Sizes are wildly heterogeneous;
     * 64×64 is the smallest bucket that fits the iconic ones (cosmo, rofl, mc² messiah) without
     * squash while still inlining cleanly with body-medium text. Bigger sprites scale down via
     * `ContentScale.Fit` rather than getting deformed.
     */
    val persoSmiley: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 64.sp,
        placeholderHeight = 64.sp,
        modifierWidth = 64.dp,
        modifierHeight = 64.dp,
    )

    /**
     * Inline `[img]` BBCode embedded inside a paragraph (`PostInline.InlineImage`). 240×180 is the
     * historical HFR thumbnail aspect (4:3) that fits next to wrapped text on a phone without
     * blowing the line height; landscape and portrait shots both fit via `ContentScale.Fit`. The
     * `:core:ui` parser already strips data:/javascript:/file: schemes so only http(s) URLs reach
     * this bucket.
     */
    val inlineImage: InlineMediaBox = InlineMediaBox(
        placeholderWidth = 240.sp,
        placeholderHeight = 180.sp,
        modifierWidth = 240.dp,
        modifierHeight = 180.dp,
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
    val modifierWidth: Dp,
    val modifierHeight: Dp,
)
