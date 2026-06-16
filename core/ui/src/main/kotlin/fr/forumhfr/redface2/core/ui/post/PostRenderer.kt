package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import kotlin.math.roundToInt

/**
 * Maximum number of nested quote levels that render expanded inline. Issue #3 explicit
 * contract: "Max N=3 niveaux visibles, reste collapsible". Beyond that the quote tail
 * collapses to an "Afficher les citations imbriquées" Card so the user can opt in.
 *
 * Exposed `internal` (not `private`) so the JVM unit test in [PostRendererQuoteDepthTest] can
 * pin both the value and the depth predicate without instantiating the `@Composable`
 * [QuoteBlock] (which would require Robolectric — see issue #130 for that path).
 */
internal const val MAX_VISIBLE_QUOTE_DEPTH = 3

/**
 * Returns true when a `Quote` block at the given recursion depth must render as
 * `CollapsedQuoteBlock` instead of expanding inline. Pure decision so the rule is testable
 * without entering Compose; `QuoteBlock` is the only call site.
 */
internal fun isCollapsedQuoteDepth(depth: Int): Boolean = depth >= MAX_VISIBLE_QUOTE_DEPTH

/**
 * Which themed accent the [QuoteFrame] left bar uses. Issue #252 — a **bare** `[quote]` (typed by
 * hand, no author) is the user formatting their own text, not citing a sourced post, so it must read
 * differently from a real HFR citation (`[quotemsg=]`, author set) and from a nested citation:
 *
 *  - [BARE] → neutral `outline` accent, regardless of depth: "quoted text, no source".
 *  - [SOURCED_EVEN] / [SOURCED_ODD] → the existing `primary` / `tertiary` alternation that keeps a
 *    subtle hierarchy for nested real citations (cf. [QuoteFrame] KDoc).
 *
 * The author palette stays colored (red/gold) so a citation always looks "sourced"; the bare quote
 * gets the muted neutral so the two are unambiguous in light, dark and AMOLED (where `secondary`
 * would collide with `primary`). Pure decision so it is pinned in [PostRendererQuoteDepthTest]
 * without entering Compose.
 */
internal enum class QuoteAccentRole { BARE, SOURCED_EVEN, SOURCED_ODD }

internal fun quoteAccentRole(quoteDepth: Int, isBareQuote: Boolean): QuoteAccentRole = when {
    isBareQuote -> QuoteAccentRole.BARE
    quoteDepth % 2 == 0 -> QuoteAccentRole.SOURCED_EVEN
    else -> QuoteAccentRole.SOURCED_ODD
}

/**
 * #252/#254 — a quote is "bare" (a hand-typed `[quote]`, no source) only when it carries **no source
 * metadata at all**. Author alone is not enough: a sourced `[quotemsg=id,page,user]` parsed in the
 * editor preview (`BbcodeContentParser`) yields `author == null` but a non-null `numreponse`, so an
 * author-only test would wrongly paint a sourced citation with the bare neutral accent + "Citation"
 * header. Reading-path citations always carry an author, so this only changes the editor-preview case.
 */
internal fun isBareQuote(quote: PostBlock.Quote): Boolean =
    quote.author == null && quote.numreponse == null && quote.page == null

@Composable
fun PostRenderer(
    content: PostContent,
    modifier: Modifier = Modifier,
    // #281 — opt-in, default OFF so callers make the choice explicitly and we never silently change
    // surfaces outside scope (the editor BBCode preview and private-message thread keep their prior
    // non-selectable behaviour). Topic posts pass `selectable = true`.
    selectable: Boolean = false,
) {
    if (selectable) {
        // #281 — allow selecting / copying a post's text. The SelectionContainer is wrapped at this
        // ENTRY POINT only, never inside the recursive PostBlocksRenderer (Quote/Spoiler): a nested
        // SelectionContainer silently breaks selection. Links (LinkAnnotation.Url) stay tappable
        // inside a SelectionContainer; inline media carry a U+FFFC placeholder that can pollute a
        // copied selection spanning them (known, acceptable limitation).
        SelectionContainer(modifier = modifier) {
            PostBlocksRenderer(blocks = content.blocks, quoteDepth = 0)
        }
    } else {
        PostBlocksRenderer(blocks = content.blocks, modifier = modifier, quoteDepth = 0)
    }
}

@Composable
private fun PostBlocksRenderer(
    blocks: List<PostBlock>,
    modifier: Modifier = Modifier,
    quoteDepth: Int,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is PostBlock.Paragraph -> ParagraphBlock(block.inlines)
                is PostBlock.Quote -> QuoteBlock(block, quoteDepth)
                is PostBlock.Spoiler -> SpoilerBlock(block, quoteDepth)
                is PostBlock.Image -> ImageBlock(block)
                is PostBlock.Fixed -> FixedBlock(block)
                is PostBlock.CodeBlock -> CodeBlockBlock(block)
            }
        }
    }
}

@Composable
private fun ParagraphBlock(inlines: List<PostInline>) {
    val primary = MaterialTheme.colorScheme.primary
    val linkStyles = remember(primary) {
        TextLinkStyles(
            style = SpanStyle(
                color = primary,
                textDecoration = TextDecoration.Underline,
            ),
        )
    }
    val imageAlt = stringResource(R.string.post_inline_image_alt)
    // The AnnotatedString is INVARIANT — it carries only the U+FFFC markers + IDs (via MediaCounter),
    // never a size — so it is never rebuilt when a measurement lands (#175 stability pivot).
    val annotated = remember(inlines, linkStyles, imageAlt) {
        buildInlineText(inlines, linkStyles, imageAlt)
    }
    val hasMedia = remember(inlines) { hasInlineMedia(inlines) }

    // Plain-text paragraph (the common case): no inline media to size, so render directly with the
    // bodyMedium rhythm and skip the whole #175 machinery — no cache reads, no measurement effect, no
    // BoxWithConstraints/SubcomposeLayout wrapper.
    if (!hasMedia) {
        if (annotated.text.isNotBlank()) {
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    // #175 — media paragraph: adaptive smiley sizing. Read each smiley's measured native size from the
    // URL cache; the reads are tracked snapshot reads, so when a measurement lands the SnapshotStateMap
    // write recomposes this block and only the inline-content Map (not the AnnotatedString) is rebuilt
    // at the final size. Cold/miss → a provisional fallback to minimise reflow (builtin ~16, perso 70×50).
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    // #175 perso smileys + #224 (option A) inline images — both sized by their measured intrinsic size.
    val measurableUrls = remember(inlines) {
        collectMeasurableSmileyUrls(inlines) + collectMeasurableImageUrls(inlines)
    }
    val measuredSizes: Map<String, IntSize?> = measurableUrls.associateWith { sizeCache.get(it) }

    // #416 — a smiley URL with a FRESH FAILURE on record is DEAD (HFR's BBCode engine turns any
    // unknown `:code:` into an <img> that 404s) : its token replaces the sprite as body-sized text.
    // Failures land from two writers — the #175 measurement effect below (perso smileys) and the
    // render-time error slot of smileyInlineContent (builtins, which are deliberately not measured).
    // isFailureFresh reads the same SnapshotStateMap as the sizes, so a failure landing recomposes
    // this block ; the AnnotatedString stays invariant (#175 pivot), only the inline-content map
    // and the placeholder box change.
    val allSmileyUrls = remember(inlines) { collectSmileyUrls(inlines) }
    val deadSmileyUrls: Set<String> =
        allSmileyUrls.filterTo(HashSet()) { sizeCache.isFailureFresh(it, System.currentTimeMillis()) }

    // Measure the not-yet-known URLs. Coil's execute() is a main-safe suspend call (it dispatches its
    // own I/O), and reuses the shared SingletonImageLoader caches the rendering AsyncImage hits — so no
    // double network fetch. A dead URL is recorded as a failure (TTL) so it is not re-fetched.
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(measurableUrls) {
        val loader = SingletonImageLoader.get(platformContext)
        measurableUrls.forEach { url ->
            val now = System.currentTimeMillis()
            if (sizeCache.get(url) == null && !sizeCache.isFailureFresh(url, now)) {
                val size = measureIntrinsicMediaSize(url, platformContext, loader)
                if (size != null) sizeCache.putSuccess(url, size) else sizeCache.putFailure(url, now)
            }
        }
    }

    // #224 (option B) — a paragraph whose only content is image(s) (a gallery, or a lone posted image
    // the parser kept inline because of a stray sibling) is promoted to full-width centred blocks once
    // a measurement shows at least one is larger than the inline caps (a left-aligned 240sp thumbnail).
    // cc-image emoji / small reactions never trip the threshold, so they keep their inline size. The
    // measure LaunchedEffect above feeds the same cache the threshold reads.
    val galleryImages = remember(inlines) { imageOnlyParagraphImages(inlines) }
    if (galleryImages != null && shouldPromoteImagesToBlocks(galleryImages, measuredSizes)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            galleryImages.forEach { promoted ->
                BlockImage(
                    url = promoted.image.url,
                    description = promoted.image.description,
                    linkUrl = promoted.linkUrl,
                )
            }
        }
        return
    }

    // Two guards against a tall/large inline smiley overlapping the text:
    //  - width: cap each smiley to RF1's `img { max-width: 90% }` of the content width (read from
    //    BoxWithConstraints, which shrinks with quote depth) so it never overflows a narrow quote;
    //  - height: drop bodyMedium's fixed `lineHeight` so the LINE GROWS to contain the placeholder
    //    (now TextBottom-aligned, #224). With the fixed lineHeight a tall sprite overflowed off its line
    //    onto the line above; unspecified lineHeight lets the line expand to the placeholder → zero overlap.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // #175 (smileys) + #224 (inline images) — RF1's `img { max-width: 90% }` relative cap, read
        // from the container width here (the only place it's known; it shrinks with quote depth).
        // `maxWidth` is dp but the media placeholders are sized in sp, so convert to the sp-equivalent
        // (Dp.toSp() divides by fontScale): without this, at fontScale > 1 a `0.9 × maxWidth` sp cap
        // renders ~fontScale× wider than the container and overflows a narrow quote (Codex review #246).
        val maxMediaWidthSp = with(LocalDensity.current) {
            (maxWidth.toSp().value * SMILEY_RELATIVE_MAX_WIDTH_FRACTION).roundToInt()
        }
        val inlineContent = remember(inlines, measuredSizes, deadSmileyUrls, maxMediaWidthSp) {
            collectInlineMedia(
                inlines,
                smileyBox = { smiley ->
                    val url = smiley.imageUrl
                    if (url != null && url in deadSmileyUrls) {
                        // #416 — the box must fit the body-sized token BEFORE the placeholder is
                        // laid out, otherwise the text is clipped to the sprite footprint.
                        PostMediaDisplayPolicy.deadSmileyTokenBox(smiley.kind.token(), maxMediaWidthSp)
                    } else {
                        smileyDisplayBox(smiley, measuredSizes, maxMediaWidthSp)
                    }
                },
                imageBox = { image -> imageDisplayBox(image, measuredSizes, maxMediaWidthSp) },
                deadSmileyUrls = deadSmileyUrls,
            )
        }
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = TextUnit.Unspecified),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun QuoteBlock(block: PostBlock.Quote, quoteDepth: Int) {
    if (isCollapsedQuoteDepth(quoteDepth)) {
        CollapsedQuoteBlock(block, quoteDepth)
        return
    }
    QuoteFrame(quoteDepth = quoteDepth, isBareQuote = isBareQuote(block)) {
        Text(
            // #252 — a bare quote still gets a "Citation" header (no author), mirroring the
            // "Citation de X" header of a sourced citation, so the framed block always reads
            // as a quotation and not as a stray indented paragraph.
            text = block.author
                ?.let { stringResource(R.string.post_quote_author, it) }
                ?: stringResource(R.string.post_quote_bare),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PostBlocksRenderer(
            blocks = block.content.blocks,
            quoteDepth = quoteDepth + 1,
        )
    }
}

/**
 * Issue #202 — quote container with a thick left accent bar so citations are immediately
 * distinguishable from post content even on AMOLED, where `surface` (`#000000`) and
 * `surfaceContainerHighest` (`#1B1616`) are visually indistinguishable. The bar uses the
 * theme `primary` / `tertiary` accent (alternating by depth) so nested quotes keep a
 * subtle hierarchy without redefining `quoteDepth` semantics — the existing N=3 collapse
 * rule still applies above this layer (cf. `isCollapsedQuoteDepth`).
 *
 * Issue #252 — a **bare** `[quote]` (`isBareQuote`, no author) instead gets a neutral `outline`
 * accent so the user's own quoted text reads differently from a sourced HFR citation and from a
 * nested citation. See [quoteAccentRole] for the (pure, tested) role decision.
 */
@Composable
private fun QuoteFrame(
    quoteDepth: Int,
    isBareQuote: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = when (quoteAccentRole(quoteDepth, isBareQuote)) {
        QuoteAccentRole.BARE -> MaterialTheme.colorScheme.outline
        QuoteAccentRole.SOURCED_EVEN -> MaterialTheme.colorScheme.primary
        QuoteAccentRole.SOURCED_ODD -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        // Quote accent bar (4dp): outline for a bare [quote] (#252), else primary/tertiary
        // alternated by depth (#202). Colour resolved above via quoteAccentRole.
        //
        // History: the original `Row(height = IntrinsicSize.Min) + Box.fillMaxHeight()` crashes
        // on quotes containing `[img]` because `SubcomposeAsyncImage` (used by
        // `PostBlock.Image`) does not support intrinsic measurement — Compose throws
        // `IllegalStateException: "Asking for intrinsic measurements of SubcomposeLayout"`.
        // The round-2 fix swapped to `Box(fillMaxWidth) { accent.matchParentSize() ; Column }`,
        // but `Modifier.matchParentSize()` is documented to size the child to the **full** Box
        // size and Compose may resolve it ahead of `.width(4.dp)` — the accent then risks
        // painting across the entire card instead of staying a thin left border. The Codex
        // rereview on PR #207 flagged this as a real rendering hazard.
        //
        // Final form: draw the 4dp accent directly with `drawBehind` on the content Column.
        // No intrinsic measurement on a SubcomposeLayout subtree, no parent-matching child,
        // and the bar's width is hard-coded in pixels so no ordering of constraints can grow
        // it. The Column reserves `QUOTE_ACCENT_WIDTH + 12.dp` of left padding so the text
        // starts at exactly the same x as the round-1 layout (gutter accent↔text = 12dp).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = accent,
                        topLeft = Offset.Zero,
                        size = Size(
                            width = QUOTE_ACCENT_WIDTH.toPx(),
                            height = this.size.height,
                        ),
                    )
                }
                .padding(start = QUOTE_ACCENT_WIDTH + 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private val QUOTE_ACCENT_WIDTH: Dp = 4.dp

@Composable
private fun CollapsedQuoteBlock(block: PostBlock.Quote, quoteDepth: Int) {
    // Issue #3 mandates the masked tail beyond N=3 nested quotes stays *collapsible* — the user
    // must be able to ask for the deeper sub-tree on demand. We reset quoteDepth to 0 once
    // revealed so the user gets another N levels before the next collapse, instead of an
    // unbounded recursion that would defeat the depth guard entirely.
    var revealed by rememberSaveable(block) { mutableStateOf(false) }
    QuoteFrame(
        quoteDepth = quoteDepth,
        isBareQuote = isBareQuote(block),
        modifier = Modifier.clickable { revealed = !revealed },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (revealed) {
                    stringResource(R.string.post_quote_collapsed_revealed)
                } else {
                    stringResource(R.string.post_quote_collapsed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (revealed) {
                    stringResource(R.string.post_quote_hide)
                } else {
                    stringResource(R.string.post_quote_show)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (revealed) {
            Text(
                // #252 — same "Citation"/"Citation de X" header rule as the expanded QuoteBlock.
                text = block.author
                    ?.let { stringResource(R.string.post_quote_author, it) }
                    ?: stringResource(R.string.post_quote_bare),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PostBlocksRenderer(
                blocks = block.content.blocks,
                quoteDepth = 0,
            )
        }
    }
}

@Composable
private fun SpoilerBlock(block: PostBlock.Spoiler, quoteDepth: Int) {
    var revealed by rememberSaveable(block) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable { revealed = !revealed },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.label ?: stringResource(R.string.post_spoiler_default_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (revealed) {
                        stringResource(R.string.post_spoiler_hide)
                    } else {
                        stringResource(R.string.post_spoiler_show)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (revealed) {
                PostBlocksRenderer(
                    blocks = block.content.blocks,
                    quoteDepth = quoteDepth,
                )
            }
        }
    }
}

@Composable
private fun ImageBlock(block: PostBlock.Image) = BlockImage(url = block.url, description = block.description)

/**
 * Full-width, centred, bounded image. The home of a standalone `PostBlock.Image`, and (since #224
 * option B) of a large image promoted out of an image-only paragraph.
 *
 * When [linkUrl] is non-null the image was posted as `[url=…][img]` (the "click to enlarge" pattern):
 * the whole block is tappable and opens that URL (#257), so a linked image gets the full-width
 * treatment AND keeps its tap-through instead of being kept as a small inline thumbnail.
 *
 * Bounded so a 4000×3000 RAW screenshot can't blow up the post and destroy the scroll position.
 * SubcomposeAsyncImage exposes loading/error slots so the user gets visual feedback when an HFR image
 * host (rehost.diberie.com, super-h.fr, …) is offline rather than a silent empty Box.
 *
 * #249 — anti-CLS: instead of reserving the legacy `minHeight` slot (which then SNAPS to the bitmap's
 * real height on arrival = a bump), reserve the EXACT final height from the measured intrinsic size
 * (`width × h/w`, same #175/#224 cache) so the shimmer placeholder occupies the loaded image's slot and
 * nothing below moves. The image then `crossfade`s in (Coil native) into the already-sized box. A
 * not-yet-measured image (standalone `PostBlock.Image`, no paragraph measure effect) keeps the legacy
 * min/max slot. Animations honour the system reduce-motion preference ([rememberAnimationsEnabled]).
 */
@Composable
private fun BlockImage(url: String, description: String?, linkUrl: String? = null) {
    val uriHandler = LocalUriHandler.current
    val openLabel = stringResource(R.string.post_image_open_link)
    val animationsEnabled = rememberAnimationsEnabled()

    // #249 — reserve the exact final box from the measured intrinsic size when known. The same cache the
    // #175/#224 paragraph measure effect fills feeds promoted images; a standalone PostBlock.Image is
    // unmeasured (null) and falls back to the legacy min/max slot below.
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    val measured: IntSize? = sizeCache.get(url)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val reservedHeight = PostMediaDisplayPolicy.reservedBlockImageHeight(
            measured = measured?.let { PixelSize(it.width, it.height) },
            availableWidthDp = maxWidth.value,
        )
        // Reserved box: an exact height when measured (anti-CLS), else the legacy min/max slot.
        val sizeModifier = if (reservedHeight != null) {
            Modifier.height(reservedHeight)
        } else {
            Modifier
                .defaultMinSize(minHeight = PostMediaDisplayPolicy.blockImageMinHeight)
                .heightIn(max = PostMediaDisplayPolicy.blockImageMaxHeight)
        }
        val containerModifier = Modifier
            .fillMaxWidth()
            .then(sizeModifier)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (linkUrl != null) {
                    // Role.Image (not Button): the element IS an image that opens its full version on
                    // tap; the localized onClickLabel carries the action for TalkBack.
                    Modifier.clickable(role = Role.Image, onClickLabel = openLabel) {
                        runCatching { uriHandler.openUri(linkUrl) }
                    }
                } else {
                    Modifier
                },
            )
        val context = LocalPlatformContext.current
        val request = remember(url, animationsEnabled, context) {
            ImageRequest.Builder(context)
                .data(url)
                // #249 — fondu natif Coil dans la box déjà dimensionnée → zéro saut. Désactivé quand le
                // système demande de réduire les animations (apparition directe, §4 de l'issue).
                .crossfade(animationsEnabled)
                .build()
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = containerModifier,
            loading = {
                // Measured: fill the exact reserved box. Unmeasured (max-only constraint): a STABLE
                // min-height placeholder — NOT fillMaxSize, which would balloon to the max slot and then
                // collapse to the loaded intrinsic height (a visible shift, Codex review). The legacy
                // min→intrinsic grow on load remains for these rare standalone images.
                val shimmerModifier = if (reservedHeight != null) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().height(PostMediaDisplayPolicy.blockImageMinHeight)
                }
                ImageShimmer(animated = animationsEnabled, modifier = shimmerModifier)
            },
            error = { ImageBlockError(description) },
            success = { SubcomposeAsyncImageContent() },
        )
    }
}

@Composable
private fun ImageBlockError(description: String?) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = description?.takeIf(String::isNotBlank)?.let {
                stringResource(R.string.post_image_error_with_alt, it)
            } ?: stringResource(R.string.post_image_error),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FixedBlock(block: PostBlock.Fixed) {
    // [fixed] = column-aligned ASCII art/tables → keep no-wrap + horizontal scroll (#244).
    MonospaceContainer(scrollHorizontally = true) {
        Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

@Composable
private fun CodeBlockBlock(block: PostBlock.CodeBlock) {
    // [code] = often prose / long pasted lines → WRAP within the card width so it stays readable on
    // mobile (#244, dogfood). No horizontal scroll. A left line-number gutter (like HFR's web render)
    // makes the wrap unambiguous: one number per LOGICAL line, wrapped continuations stay unnumbered.
    MonospaceContainer(scrollHorizontally = false) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.language?.let { lang ->
                Text(
                    text = lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CodeWithLineNumbers(
                code = block.text,
                codeStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                codeColor = MaterialTheme.colorScheme.onSurface,
                gutterColor = MaterialTheme.colorScheme.onSurfaceVariant,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Gap between the right edge of the line-number gutter and the start of the code text. */
private val CodeGutterGap = 8.dp

/**
 * Renders `[code]` with a left line-number gutter that stays aligned when long lines soft-wrap.
 *
 * The whole block is a SINGLE soft-wrapping [Text] (so selection/copy stay contiguous and the
 * composable count is O(1) regardless of line count). Numbers are PAINTED in [Modifier.drawBehind] —
 * never part of the text content — by mapping each LOGICAL line's start offset to its first visual
 * line via [TextLayoutResult.getLineForOffset] then [TextLayoutResult.getLineTop]. A wrapped
 * continuation visual line is never visited, so it gets no number: that is what lets the reader tell
 * a soft-wrap apart from a real newline.
 *
 * The gutter width comes from the digit count of the line total (monospace ⇒ fixed advance), so
 * numbers are right-aligned and the code column never shifts. [layout] is read only in the draw phase
 * to avoid a recomposition loop.
 */
@Composable
private fun CodeWithLineNumbers(
    code: String,
    codeStyle: TextStyle,
    codeColor: Color,
    gutterColor: Color,
    dividerColor: Color,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val gutterStyle = remember(codeStyle, gutterColor) { codeStyle.copy(color = gutterColor) }

    // Start offset of each LOGICAL line, computed from the raw source before layout.
    val lineStartOffsets = remember(code) {
        buildList {
            add(0)
            code.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
            if (code.endsWith("\n")) removeAt(lastIndex)
        }
    }

    val digitCount = lineStartOffsets.size.toString().length
    val digitAdvancePx = remember(gutterStyle, density) { measurer.measure("0", gutterStyle).size.width }
    val gutterTextWidthPx = digitCount * digitAdvancePx
    val gapPx = with(density) { CodeGutterGap.toPx() }
    val gutterWidthDp = with(density) { (gutterTextWidthPx + gapPx).toDp() }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Code is LTR by nature; force it so the painted gutter (absolute-left coords) and the Text's
    // `start` padding agree under RTL locales (Codex review on the #244 PR) — otherwise `start` flips
    // to the right while the gutter stays on the left and overlaps the code.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val dividerX = gutterTextWidthPx + gapPx / 2f
                    drawLine(
                        color = dividerColor,
                        start = Offset(dividerX, 0f),
                        end = Offset(dividerX, size.height),
                    )
                    lineStartOffsets.forEachIndexed { index, offset ->
                        val visualLine = result.getLineForOffset(offset).coerceIn(0, result.lineCount - 1)
                        val label = (index + 1).toString()
                        val x = (gutterTextWidthPx - label.length * digitAdvancePx).toFloat()
                        drawText(
                            textMeasurer = measurer,
                            text = label,
                            topLeft = Offset(x, result.getLineTop(visualLine)),
                            style = gutterStyle,
                        )
                    }
                },
        ) {
            Text(
                text = code,
                style = codeStyle,
                color = codeColor,
                softWrap = true,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = gutterWidthDp),
            )
        }
    }
}

/**
 * Wraps a `[fixed]` / `[code]` body in a tinted monospace card. [scrollHorizontally] picks the
 * overflow behaviour per block kind (#244) :
 *
 * - **`true` (`[fixed]`)** : long lines OVERFLOW horizontally (children opt out of soft wrap, the
 *   inner [Column] scrolls). `[fixed]` is column-aligned ASCII art / tables, so wrapping would
 *   mangle the alignment — horizontal scroll preserves it.
 * - **`false` (`[code]`)** : the body WRAPS within the card width. HFR `[code]` is most often prose
 *   or long pasted lines (e.g. articles), where a single horizontally-scrolling line is unreadable
 *   on mobile (the original dogfood bug — RF1's WebView wraps it). The inner [Column] fills the
 *   width so the soft-wrapping monospace [Text] flows.
 *
 * Modifier order (scroll mode): the **outer** [Card] carries [Modifier.fillMaxWidth] so the card
 * spans the parent; the **inner** [Column] must NOT carry [Modifier.fillMaxWidth] before
 * [Modifier.horizontalScroll] (that would clamp the children's measured width to the card's width
 * and turn the scroll into a no-op). [Modifier.padding] sits before the scroll so the inset stays
 * fixed and the children scroll inside it.
 */
@Composable
private fun MonospaceContainer(scrollHorizontally: Boolean, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(12.dp)
                .let { base ->
                    if (scrollHorizontally) base.horizontalScroll(scrollState) else base.fillMaxWidth()
                },
        ) {
            content()
        }
    }
}

internal fun buildInlineText(
    inlines: List<PostInline>,
    linkStyles: TextLinkStyles,
    imageAlt: String,
): AnnotatedString = buildAnnotatedString {
    val media = MediaCounter()
    appendInlines(inlines, linkStyles, media, imageAlt)
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<PostInline>,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
    imageAlt: String,
) {
    inlines.forEach { inline -> appendInline(inline, linkStyles, media, imageAlt) }
}

@Suppress("CyclomaticComplexMethod")
private fun AnnotatedString.Builder.appendInline(
    inline: PostInline,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
    imageAlt: String,
) {
    when (inline) {
        is PostInline.Text -> append(inline.value)
        PostInline.LineBreak -> append('\n')
        is PostInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.Color -> withStyle(SpanStyle(color = parseColor(inline.colorHex))) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.Link -> withLink(LinkAnnotation.Url(inline.url, linkStyles)) {
            appendInlines(inline.children, linkStyles, media, imageAlt)
        }

        is PostInline.InlineImage -> appendInlineContent(media.nextImage(), inline.description ?: imageAlt)
        is PostInline.Smiley -> {
            val token = inline.kind.token()
            if (inline.imageUrl == null) {
                append(token)
            } else {
                appendInlineContent(media.nextSmiley(), token)
            }
        }
    }
}

/**
 * Builds the `InlineTextContent` map keyed by the same IDs [buildInlineText] emits (the MediaCounter
 * symmetry invariant). [smileyBox] resolves the placeholder size for each smiley: the production
 * caller ([ParagraphBlock]) passes a cache-backed resolver (#175 intrinsic sizing), while tests can
 * pass a stub. The default keeps the legacy fixed buckets as the cold fallback.
 */
internal fun collectInlineMedia(
    inlines: List<PostInline>,
    smileyBox: (PostInline.Smiley) -> InlineMediaBox = { PostMediaDisplayPolicy.smileyBox(it) },
    imageBox: (PostInline.InlineImage) -> InlineMediaBox = { PostMediaDisplayPolicy.inlineImage },
    deadSmileyUrls: Set<String> = emptySet(),
): Map<String, InlineTextContent> {
    val out = mutableMapOf<String, InlineTextContent>()
    val media = MediaCounter()
    walkInlinesForMedia(inlines, out, media, smileyBox, imageBox, deadSmileyUrls)
    return out
}

@Suppress("LongParameterList") // Recursive walker — every param is the same threaded context.
private fun walkInlinesForMedia(
    inlines: List<PostInline>,
    out: MutableMap<String, InlineTextContent>,
    media: MediaCounter,
    smileyBox: (PostInline.Smiley) -> InlineMediaBox,
    imageBox: (PostInline.InlineImage) -> InlineMediaBox,
    deadSmileyUrls: Set<String>,
) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.InlineImage ->
                out += media.nextImage() to imageInlineContent(inline, imageBox(inline))

            is PostInline.Smiley -> {
                val url = inline.imageUrl ?: return@forEach
                out += media.nextSmiley() to
                    smileyInlineContent(inline, smileyBox(inline), dead = url in deadSmileyUrls)
            }

            is PostInline.Strong ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            is PostInline.Emphasis ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            is PostInline.Underline ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            is PostInline.Strike ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            is PostInline.Color ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            is PostInline.Link ->
                walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox, deadSmileyUrls)
            else -> Unit
        }
    }
}

/**
 * Collects distinct perso smiley image URLs of [inlines], for #175 intrinsic measurement.
 *
 * Builtin HFR smileys are intentionally skipped: their historical 16×16-ish size is known and stable,
 * so measuring/fetching every `:jap:`/`:o` on a cold topic would contradict the pre-seed contract and
 * add avoidable work to the common path.
 */
internal fun collectMeasurableSmileyUrls(inlines: List<PostInline>): Set<String> {
    val urls = LinkedHashSet<String>()
    collectMeasurableSmileyUrlsInto(inlines, urls)
    return urls
}

private fun collectMeasurableSmileyUrlsInto(inlines: List<PostInline>, urls: MutableSet<String>) {
    inlines.forEach { inline -> collectMeasurableSmileyUrl(inline, urls) }
}

/**
 * #416 — collects EVERY smiley image URL (builtins included), unlike [collectMeasurableSmileyUrls]
 * which deliberately skips builtins for the measurement pre-seed contract. The dead-sprite check
 * needs the full set : an unknown `:code:` is served by HFR as a 404 builtin-style sprite, and its
 * failure is recorded at render time (error slot), not by the measurement effect.
 */
internal fun collectSmileyUrls(inlines: List<PostInline>): Set<String> {
    val urls = LinkedHashSet<String>()
    collectSmileyUrlsInto(inlines, urls)
    return urls
}

private fun collectSmileyUrlsInto(inlines: List<PostInline>, urls: MutableSet<String>) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.Smiley -> inline.imageUrl?.let { urls += it }
            is PostInline.Strong -> collectSmileyUrlsInto(inline.children, urls)
            is PostInline.Emphasis -> collectSmileyUrlsInto(inline.children, urls)
            is PostInline.Underline -> collectSmileyUrlsInto(inline.children, urls)
            is PostInline.Strike -> collectSmileyUrlsInto(inline.children, urls)
            is PostInline.Color -> collectSmileyUrlsInto(inline.children, urls)
            is PostInline.Link -> collectSmileyUrlsInto(inline.children, urls)
            else -> Unit
        }
    }
}

private fun collectMeasurableSmileyUrl(inline: PostInline, urls: MutableSet<String>) {
    when (inline) {
        is PostInline.Smiley -> {
            if (inline.kind is SmileyKind.Perso) inline.imageUrl?.let { urls += it }
        }

        is PostInline.Strong -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        is PostInline.Emphasis -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        is PostInline.Underline -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        is PostInline.Strike -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        is PostInline.Color -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        is PostInline.Link -> collectMeasurableSmileyUrlsInto(inline.children, urls)
        else -> Unit
    }
}

/**
 * #224 (option A) — collects distinct inline `[img]` URLs of [inlines], for intrinsic measurement
 * (no-upscale native sizing, like #175 smileys). The `:core:ui` parser has already stripped
 * non-http(s) schemes, so every collected URL is safe to hand to Coil. Recurses into inline
 * containers (e.g. an `[img]` wrapped in a `[url=…]` link), mirroring [walkInlinesForMedia].
 */
internal fun collectMeasurableImageUrls(inlines: List<PostInline>): Set<String> {
    val urls = LinkedHashSet<String>()
    collectMeasurableImageUrlsInto(inlines, urls)
    return urls
}

private fun collectMeasurableImageUrlsInto(inlines: List<PostInline>, urls: MutableSet<String>) {
    inlines.forEach { inline -> collectMeasurableImageUrl(inline, urls) }
}

private fun collectMeasurableImageUrl(inline: PostInline, urls: MutableSet<String>) {
    when (inline) {
        is PostInline.InlineImage -> urls += inline.url
        is PostInline.Strong -> collectMeasurableImageUrlsInto(inline.children, urls)
        is PostInline.Emphasis -> collectMeasurableImageUrlsInto(inline.children, urls)
        is PostInline.Underline -> collectMeasurableImageUrlsInto(inline.children, urls)
        is PostInline.Strike -> collectMeasurableImageUrlsInto(inline.children, urls)
        is PostInline.Color -> collectMeasurableImageUrlsInto(inline.children, urls)
        is PostInline.Link -> collectMeasurableImageUrlsInto(inline.children, urls)
        else -> Unit
    }
}

/**
 * #175 — resolve a smiley's placeholder box: measured native size (no-upscale + absolute cap) when
 * known, else a provisional fallback (pre-seeded builtin / dominant 70×50 perso) to minimise reflow
 * while the measurement is in flight. Finally clamped to [maxWidthSp] (RF1's relative `max-width:90%`)
 * so a large perso cannot overflow a narrow quote line.
 */
private fun smileyDisplayBox(
    smiley: PostInline.Smiley,
    measured: Map<String, IntSize?>,
    maxWidthSp: Int,
): InlineMediaBox {
    val size = smiley.imageUrl?.let { measured[it] }
    val base = if (size != null) {
        intrinsicSmileyDisplaySize(PixelSize(size.width, size.height))
    } else {
        when (smiley.kind) {
            is SmileyKind.Builtin -> builtinPreseedSize
            is SmileyKind.Perso -> persoColdFallbackSize
        }
    }
    val capped = capToWidth(base, maxWidthSp)
    return InlineMediaBox(capped.width.sp, capped.height.sp)
}

/**
 * #224 (option A) — resolve an inline `[img]` placeholder box from its measured intrinsic size:
 * no-upscale + absolute cap ([INLINE_IMAGE_MAX_WIDTH_SP]×[INLINE_IMAGE_MAX_HEIGHT_SP]) via the shared
 * [intrinsicSmileyDisplaySize] policy, then the relative `0.9 × contentWidth` cap ([maxWidthSp]).
 *
 * #253 — while the measurement is in flight (cold cache / miss) it falls back to a small square of
 * [INLINE_IMAGE_MIN_HEIGHT_SP] (≈ one text line) rather than the old 240×180 bucket. With
 * `ContentScale.Fit` filling the box, a 240×180 cold box upscaled a 16×16 cc-image emoji to a giant
 * 180×180 flash until the measurement landed; a min-height square means the dominant cold case (the
 * 16×16 emoji) is already at its final size — zero flash — and any larger image just grows from a
 * one-line slot once measured instead of shrinking from a giant one. Still relative-capped so even
 * the fallback never overflows a narrow quote. Mirrors [smileyDisplayBox].
 */
internal fun imageDisplayBox(
    image: PostInline.InlineImage,
    measured: Map<String, IntSize?>,
    maxWidthSp: Int,
): InlineMediaBox {
    val size = measured[image.url]
    val base = if (size != null) {
        // #175 no-upscale + cap with the inline-image caps, then a min-height floor so a SUB-16 low-res
        // source can't render below ~one text line. A cc-image emoji (16×16) sits exactly at the floor
        // → kept native (per @XaaT dogfood); only smaller sources get enlarged. NB: the floor only
        // grows the BOX — the bitmap fills it via ContentScale.Fit.
        //
        // Re-apply the absolute caps AFTER the floor: a very wide/short source (e.g. 250×10) capped to
        // 240×10 then floored to height 16 would grow to ~384×16 — past both the 240sp width cap and its
        // native width. The second cap clamps that back (the floor simply doesn't apply when it can't fit
        // the width cap), so the no-upscale/cap contract holds for every aspect ratio (Codex review #246).
        intrinsicSmileyDisplaySize(
            upscaleToMinHeight(
                intrinsicSmileyDisplaySize(
                    PixelSize(size.width, size.height),
                    maxWidthSp = INLINE_IMAGE_MAX_WIDTH_SP,
                    maxHeightSp = INLINE_IMAGE_MAX_HEIGHT_SP,
                ),
                INLINE_IMAGE_MIN_HEIGHT_SP,
            ),
            maxWidthSp = INLINE_IMAGE_MAX_WIDTH_SP,
            maxHeightSp = INLINE_IMAGE_MAX_HEIGHT_SP,
        )
    } else {
        // #253 cold-fallback: a one-line square, not the 240×180 bucket (no giant Fit upscale flash).
        PixelSize(INLINE_IMAGE_MIN_HEIGHT_SP, INLINE_IMAGE_MIN_HEIGHT_SP)
    }
    val capped = capToWidth(base, maxWidthSp)
    return InlineMediaBox(capped.width.sp, capped.height.sp)
}

/** #224/#257 — an image eligible for block promotion, paired with its enclosing `[url=…]` link (if any). */
internal data class PromotedImage(val image: PostInline.InlineImage, val linkUrl: String?)

/**
 * #224 (option B) / #257 — if a paragraph's only meaningful content is image(s) (a single posted image
 * the parser kept inline because of a stray sibling, or a gallery of several), return them in order,
 * each paired with the URL of its enclosing `[url=…]` link if there is one, so they can be promoted to
 * full-width centred blocks. Blank text and line breaks are ignored.
 *
 * Returns null when the paragraph has any other meaningful inline (non-blank text, a smiley): genuine
 * prose keeps its inline image treatment. A link wrapping ONLY an image is fine — #257 promotes it to a
 * block that opens the link on tap, so the "click to enlarge" tap-through is preserved AND the image
 * fills the width (before #257 a linked image was kept inline → small + the inline pixelation path).
 * A link wrapping image **+** text still counts as other content → null (stays inline prose).
 */
@Suppress("CyclomaticComplexMethod") // exhaustive when over the PostInline sealed type, like appendInline
internal fun imageOnlyParagraphImages(inlines: List<PostInline>): List<PromotedImage>? {
    val images = mutableListOf<PromotedImage>()
    var hasOtherContent = false
    fun walk(list: List<PostInline>, linkUrl: String?) {
        list.forEach { inline ->
            when (inline) {
                is PostInline.InlineImage -> images += PromotedImage(inline, linkUrl)
                is PostInline.Text -> if (inline.value.isNotBlank()) hasOtherContent = true
                PostInline.LineBreak -> Unit
                is PostInline.Smiley -> hasOtherContent = true
                is PostInline.Strong -> walk(inline.children, linkUrl)
                is PostInline.Emphasis -> walk(inline.children, linkUrl)
                is PostInline.Underline -> walk(inline.children, linkUrl)
                is PostInline.Strike -> walk(inline.children, linkUrl)
                is PostInline.Color -> walk(inline.children, linkUrl)
                is PostInline.Link -> walk(inline.children, inline.url)
            }
        }
    }
    walk(inlines, linkUrl = null)
    return images.takeIf { it.isNotEmpty() && !hasOtherContent }
}

/**
 * #224 (option B) — promote an image-only paragraph to full-width blocks only once at least one image
 * has measured larger than the inline display caps (so inline rendering would shrink it to a small
 * left-aligned thumbnail). A cc-image emoji (16×16) or a small reaction never trips this, so they keep
 * their inline size; a real posted photo / gallery does, and gets the centred full-width treatment.
 * Returns false while every size is unknown (cold) so promotion only kicks in after measurement.
 */
internal fun shouldPromoteImagesToBlocks(
    images: List<PromotedImage>,
    measured: Map<String, IntSize?>,
): Boolean = images.any { promoted ->
    val size = measured[promoted.image.url] ?: return@any false
    size.width > INLINE_IMAGE_MAX_WIDTH_SP || size.height > INLINE_IMAGE_MAX_HEIGHT_SP
}

/** True when [inlines] contains at least one renderable inline media (a smiley with a URL, or an image). */
private fun hasInlineMedia(inlines: List<PostInline>): Boolean = inlines.any { inline ->
    when (inline) {
        is PostInline.InlineImage -> true
        is PostInline.Smiley -> inline.imageUrl != null
        is PostInline.Strong -> hasInlineMedia(inline.children)
        is PostInline.Emphasis -> hasInlineMedia(inline.children)
        is PostInline.Underline -> hasInlineMedia(inline.children)
        is PostInline.Strike -> hasInlineMedia(inline.children)
        is PostInline.Color -> hasInlineMedia(inline.children)
        is PostInline.Link -> hasInlineMedia(inline.children)
        else -> false
    }
}

internal fun imageInlineContent(image: PostInline.InlineImage, box: InlineMediaBox): InlineTextContent {
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
            // TextBottom (dogfood choice): the image bottom sits at the bottom of the surrounding
            // text, so a small inline emoji (cc-image) rides the line with the words rather than
            // floating in the middle (Center) — consistent with smileys, which use the same alignment.
            // A taller inline image grows the line (lineHeight Unspecified on media paragraphs).
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom,
        ),
    ) {
        // The image fills the placeholder via fillMaxSize() (ContentScale.Fit) so the rendered size
        // tracks the sp-based placeholder under any fontScale; the no-upscale rule lives in the BOX
        // sizing (imageDisplayBox), not the content scale.
        //
        // #257 — decode at a STABLE size (the inline display cap in px, bounded) instead of letting
        // Coil resolve the size from the placeholder constraints. The box grows from the cold-fallback
        // square to the measured size when the measurement lands; with constraint-driven sizing Coil
        // re-decodes at the new size and, meanwhile, paints the previous tiny bitmap upscaled →
        // pixelated. A fixed decode size keeps ONE sharp bitmap that Fit scales into whatever box (Coil
        // never upscales the decode past the source, so a small image still decodes at native). The
        // request is remembered so a measurement landing doesn't rebuild it. Smileys keep their own
        // (much smaller) path — this cap is photo-sized.
        val density = LocalDensity.current
        val context = LocalPlatformContext.current
        val widthPx = with(density) { INLINE_IMAGE_MAX_WIDTH_SP.sp.roundToPx() }
            .coerceAtMost(INLINE_IMAGE_DECODE_CAP_PX)
        val heightPx = with(density) { INLINE_IMAGE_MAX_HEIGHT_SP.sp.roundToPx() }
            .coerceAtMost(INLINE_IMAGE_DECODE_CAP_PX)
        val request = remember(image.url, widthPx, heightPx, context) {
            ImageRequest.Builder(context)
                .data(image.url)
                .size(widthPx, heightPx)
                .scale(Scale.FIT)
                .precision(Precision.INEXACT)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = image.description,
            contentScale = PostMediaDisplayPolicy.inlineImageContentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal fun smileyInlineContent(
    smiley: PostInline.Smiley,
    box: InlineMediaBox,
    dead: Boolean = false,
): InlineTextContent {
    val description = smiley.kind.token()
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
            // TextBottom (dogfood choice, same alignment as inline [img]): the sprite bottom sits at
            // the bottom of the surrounding text so the smiley rides the line with the words. Media
            // paragraphs drop bodyMedium's fixed lineHeight (see ParagraphBlock) so a tall smiley grows
            // the line instead of overlapping the line above. (AboveBaseline / Center were trialled
            // earlier; TextBottom chosen in dogfood for consistency with [img].)
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom,
        ),
    ) {
        if (dead) {
            // #416 (round 2, retour dev v118) — the sprite is KNOWN dead (fresh failure on record :
            // HFR 404s any unknown `:code:`) and the box was sized for the token by deadSmileyTokenBox,
            // so render the token at body size — no image attempt, no ellipsis budget needed (Clip is
            // a guard for extreme fontScale).
            Text(
                text = description,
                fontSize = DEAD_SMILEY_TOKEN_FONT_SP.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            return@InlineTextContent
        }
        SubcomposeAsyncImage(
            model = smiley.imageUrl,
            contentDescription = description,
            contentScale = PostMediaDisplayPolicy.smileyContentScale,
            modifier = Modifier.fillMaxSize(),
            success = { SubcomposeAsyncImageContent() },
            error = {
                // #416 — first failure of a sprite that was NOT known dead when this content was
                // built (builtins are never measured ; a perso can die between measure and render).
                // Record the failure so the paragraph recomposes onto the dead-token path above
                // (readable, body-sized) ; the tiny ellipsised token below is only the transient
                // frame between this error and that recomposition.
                val cache = LocalIntrinsicMediaSizeCache.current
                val url = smiley.imageUrl
                if (url != null) {
                    LaunchedEffect(url) {
                        if (!cache.isFailureFresh(url, System.currentTimeMillis())) {
                            cache.putFailure(url, System.currentTimeMillis())
                        }
                    }
                }
                Text(
                    text = description,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

/** #416 — body-sized token for a dead sprite (bodyMedium is 14 sp ; keep in sync with the box). */
private const val DEAD_SMILEY_TOKEN_FONT_SP = 14

private fun SmileyKind.token(): String = when (this) {
    is SmileyKind.Builtin -> code
    is SmileyKind.Perso -> "[:$name]"
}

internal fun parseColor(hex: String): Color {
    // Pure-Kotlin parsing keeps :core:ui testable on plain JVM (no Android runtime). The parser
    // already normalises the input to #RRGGBB or #RRGGBBAA in PostContentParser.normalizeColorHex,
    // so we do not need android.graphics.Color.parseColor's permissive behaviour.
    val value = hex.removePrefix("#")
    return when (value.length) {
        6 -> Color(0xFF000000L or value.toLong(16))
        8 -> {
            // HFR BBCode never carries an alpha channel today; accepting RRGGBBAA is purely
            // defensive in case a future producer drifts to the longer shape.
            val rgba = value.toLong(16)
            val rgb = rgba ushr 8
            val alpha = rgba and 0xFFL
            Color((alpha shl 24) or rgb)
        }

        else -> Color.Unspecified
    }
}

/**
 * MUST be created fresh per Text/AnnotatedString — sharing it across paragraphs would offset the
 * IDs emitted by [appendInlineContent] from the keys produced by [collectInlineMedia], leading to
 * orphan entries (placeholder rendered with no Composable) or stranded Composables (no anchor in
 * the AnnotatedString). The parallel walks in [appendInline] and [walkInlinesForMedia] match
 * because they advance the counter under the exact same conditions; do not break that symmetry.
 */
private class MediaCounter {
    private var image = 0
    private var smiley = 0
    fun nextImage(): String = "post-image-${image++}"
    fun nextSmiley(): String = "post-smiley-${smiley++}"
}
