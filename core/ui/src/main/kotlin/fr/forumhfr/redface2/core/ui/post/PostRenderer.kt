package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.ui.motion.rememberAnimationsEnabled
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.theme.LocalBlockedQuoteAuthors
import fr.forumhfr.redface2.core.ui.theme.LocalFoldLongQuotes
import fr.forumhfr.redface2.core.ui.theme.LocalIgnoreInlineColors
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
 * Issue #332 — character budget above which a TOP-LEVEL `[quote]`/`[quotemsg]` block renders
 * folded to a one-line header by default ("longues citations… repliées sur une ligne, dépliables
 * au clic puis repliables"), mirroring the long-quote behaviour of the existing HFR userscript.
 *
 * 280 ≈ a few lines of body text: a one- or two-sentence citation (the common "je cite la phrase à
 * laquelle je réponds" case) stays expanded so the reader never has to tap for a short quote, while
 * a wall-of-text citation folds away by default and can be opened on demand.
 *
 * This is ORTHOGONAL to [MAX_VISIBLE_QUOTE_DEPTH] / [isCollapsedQuoteDepth] (issue #3/#82 — collapse
 * by NESTING depth, not length): the length fold only applies at depth 0 (see [isLongQuote]) so the
 * two never compete on the same block.
 */
internal const val LONG_QUOTE_CHAR_THRESHOLD = 280

/**
 * Total length of the visible text carried by a [PostContent] (paragraph text + nested quote text,
 * line breaks counted as one char, plus one separator per block boundary since consecutive blocks
 * render on their own lines). Pure so the long-quote rule is pinned in a JVM test without entering
 * Compose; smiley tokens and image alt-text are deliberately ignored — they are not "reading length"
 * and a smiley-heavy line should not force a fold. A nested spoiler contributes nothing: its body is
 * hidden behind its own toggle by default, so it adds no visible reading length to the fold decision
 * (else a short quote wrapping a long spoiler would fold before the spoiler toggle even shows).
 */
internal fun quoteVisibleTextLength(content: PostContent): Int {
    if (content.blocks.isEmpty()) return 0
    // +1 per block boundary: consecutive paragraphs/blocks render on separate lines, so a quote made
    // of many short blocks accumulates the visible breaks between them (Codex review).
    return content.blocks.sumOf(::blockVisibleTextLength) + (content.blocks.size - 1)
}

private fun blockVisibleTextLength(block: PostBlock): Int = when (block) {
    is PostBlock.Paragraph -> block.inlines.sumOf(::inlineVisibleTextLength)
    is PostBlock.Quote -> quoteVisibleTextLength(block.content)
    is PostBlock.Spoiler -> 0
    is PostBlock.Fixed -> block.text.length
    is PostBlock.CodeBlock -> block.text.length
    is PostBlock.Image -> 0
}

private fun inlineVisibleTextLength(inline: PostInline): Int = when (inline) {
    is PostInline.Text -> inline.value.length
    PostInline.LineBreak -> 1
    is PostInline.Strong -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.Emphasis -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.Underline -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.Strike -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.Color -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.Link -> inline.children.sumOf(::inlineVisibleTextLength)
    is PostInline.InlineImage -> 0
    is PostInline.Smiley -> 0
}

/**
 * Issue #332 — true when a quote must fold by default because it is "long" (since #784 the fold
 * shows a bounded PREVIEW instead of a bare header line, cf. [FoldableQuoteBlock]).
 * Restricted to [quoteDepth] == 0: a nested quote is already governed by the depth rule
 * ([isCollapsedQuoteDepth]) and the parent fold, so folding it again by length would stack two
 * different toggles on the same sub-tree. Pure decision, pinned in [PostRendererQuoteDepthTest].
 */
internal fun isLongQuote(block: PostBlock.Quote, quoteDepth: Int): Boolean =
    quoteDepth == 0 && quoteVisibleTextLength(block.content) > LONG_QUOTE_CHAR_THRESHOLD

/**
 * #784 — how many `bodyMedium` lines of a folded long quote stay visible as a PREVIEW. 5 lines ≈
 * the reading depth of a short citation: enough context to decide whether the wall of text is
 * worth unfolding, small enough that a folded quote never dominates the post. Pure constant so
 * the budget is pinned in [PostRendererQuoteDepthTest] and any change is a deliberate review step.
 */
internal const val LONG_QUOTE_PREVIEW_LINES = 5

/** #784 — fallback line height (sp) when the theme leaves `bodyMedium.lineHeight` unspecified. */
internal const val LONG_QUOTE_FALLBACK_LINE_HEIGHT_SP = 20f

/**
 * #784 — max height (sp) of the folded preview container: [LONG_QUOTE_PREVIEW_LINES] ×
 * the body line height. Sp on purpose so the preview grows with the user's font scale (a fixed
 * dp cap would show fewer lines at accessibility font sizes). Pure so the sizing rule is
 * testable without composing anything ([PostRendererQuoteDepthTest]).
 */
internal fun longQuotePreviewMaxHeightSp(bodyLineHeightSp: Float): Float {
    val line = if (bodyLineHeightSp > 0f) bodyLineHeightSp else LONG_QUOTE_FALLBACK_LINE_HEIGHT_SP
    return line * LONG_QUOTE_PREVIEW_LINES
}

/** #784 — tolerance for the clip decision below (sub-pixel rounding of the constrained height). */
internal const val LONG_QUOTE_CLIP_TOLERANCE_PX = 1f

/**
 * #784 — whether the folded preview actually CLIPPED its content: the constrained box reports a
 * height at (or within a rounding tolerance of) the cap only when the content wanted more room.
 * Gates the bottom fade so a quote that is « long » by character count but renders short (wide
 * screen, media-light text) is not painted with a misleading « more below » scrim. Pure decision,
 * pinned in [PostRendererQuoteDepthTest].
 */
internal fun isLongQuotePreviewClipped(contentHeightPx: Float, maxHeightPx: Float): Boolean =
    contentHeightPx >= maxHeightPx - LONG_QUOTE_CLIP_TOLERANCE_PX

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

/**
 * #785 — true when a quote cites a black-listed author: the quote's parsed author matches (by the
 * canonical key, cf. [canonicalizePseudo]) one of the blocked canonicals the reading surface
 * provided through [LocalBlockedQuoteAuthors]. Author-only on purpose: a citation HFR served in
 * the dynamic `forum2.php` form keeps `page`/`numreponse` null but still carries the author, so
 * the mask must never depend on the jump coordinates. A `[quotemsg]` forged with an arbitrary
 * pseudo masks too — acceptable, the author line is the only identity a citation carries. Pure
 * decision so it is pinned in [PostRendererQuoteDepthTest] without entering Compose.
 */
internal fun isBlockedQuoteAuthor(author: String?, blockedCanonicals: Set<String>): Boolean {
    if (author == null || blockedCanonicals.isEmpty()) return false
    return canonicalizePseudo(author) in blockedCanonicals
}

@Composable
fun PostRenderer(
    content: PostContent,
    modifier: Modifier = Modifier,
    // #281 — opt-in, default OFF so callers make the choice explicitly and we never silently change
    // surfaces outside scope (the editor BBCode preview and private-message thread keep their prior
    // non-selectable behaviour). Topic posts pass `selectable = true`.
    selectable: Boolean = false,
    // #699 — invoked with the cited post's `(page, numreponse)` when the reader taps a sourced
    // quote's header. Null (default) keeps the header inert — only the topic reading surface wires
    // it (the editor preview, MP threads and signatures have nowhere meaningful to navigate).
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    if (selectable) {
        // #281 — allow selecting / copying a post's text. The SelectionContainer is wrapped at this
        // ENTRY POINT only, never inside the recursive PostBlocksRenderer (Quote/Spoiler): a nested
        // SelectionContainer silently breaks selection. Links (LinkAnnotation.Url) stay tappable
        // inside a SelectionContainer; inline media carry a U+FFFC placeholder that can pollute a
        // copied selection spanning them (known, acceptable limitation).
        SelectionContainer(modifier = modifier) {
            PostBlocksRenderer(blocks = content.blocks, quoteDepth = 0, onGoToCitedPost = onGoToCitedPost)
        }
    } else {
        PostBlocksRenderer(
            blocks = content.blocks,
            modifier = modifier,
            quoteDepth = 0,
            onGoToCitedPost = onGoToCitedPost,
        )
    }
}

@Composable
private fun PostBlocksRenderer(
    blocks: List<PostBlock>,
    modifier: Modifier = Modifier,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is PostBlock.Paragraph -> ParagraphBlock(block.inlines)
                is PostBlock.Quote -> QuoteBlock(block, quoteDepth, onGoToCitedPost)
                is PostBlock.Spoiler -> SpoilerBlock(block, quoteDepth, onGoToCitedPost)
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
    // #553 — signatures provide LocalIgnoreInlineColors = true so author `[color]` is dropped.
    val ignoreColors = LocalIgnoreInlineColors.current
    // State-hygiene audit 2026-07-05 — author `[color]` legibility: dark is detected from the
    // surface luminance so it follows AMOLED and a forced ThemeMode, not just the system flag
    // (same rule as CreatorHighlight). isDark keys the remember so a live theme switch rebuilds
    // the spans; it never changes during media measurements, so the #175 invariance holds.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE
    // The AnnotatedString is INVARIANT — it carries only the U+FFFC markers + IDs (via MediaCounter),
    // never a size — so it is never rebuilt when a measurement lands (#175 stability pivot).
    val annotated = remember(inlines, linkStyles, imageAlt, ignoreColors, isDark) {
        buildInlineText(inlines, linkStyles, imageAlt, ignoreColors, isDark)
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
            measureAndCacheIntrinsicMediaSize(url, sizeCache, platformContext, loader)
        }
    }

    // #224 (option B) — a paragraph whose only content is image(s) (a gallery, or a lone posted image
    // the parser kept inline because of a stray sibling) is promoted to centred blocks once a
    // measurement shows at least one is larger than the promotion thresholds. Since #610 the block
    // SIZE equals the inline size (unified parity policy): promotion only buys the layout semantics
    // (own centred line, block loading/error UX, #257 tap-through). cc-image emoji / small reactions
    // never trip the threshold, so they keep their inline flow. The measure LaunchedEffect above
    // feeds the same cache the threshold reads.
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

// ReturnCount: the guard chain (blocked author first, then the folds) IS the dispatch.
@Suppress("ReturnCount")
@Composable
private fun QuoteBlock(
    block: PostBlock.Quote,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    // #785 — the blacklist applies INSIDE quotes too: a citation whose author is black-listed is
    // masked like that author's own posts are. This branch runs BEFORE the depth/length folds so a
    // blocked quote can never leak through an expanded render; the QuoteBlock recursion covers
    // nested citations natively, and the local's empty default keeps every non-topic surface
    // (editor preview, MP threads, signatures) unchanged.
    if (isBlockedQuoteAuthor(block.author, LocalBlockedQuoteAuthors.current)) {
        BlockedQuoteBlock(block, quoteDepth, onGoToCitedPost)
        return
    }
    if (isCollapsedQuoteDepth(quoteDepth)) {
        CollapsedQuoteBlock(block, quoteDepth, onGoToCitedPost)
        return
    }
    // #332 — the long-quote fold is gated on the user preference (default ON = historical fold).
    // When OFF we skip FoldableQuoteBlock entirely and fall through to the normal expanded render,
    // exactly as if the quote were not "long". The depth fold above is unaffected.
    if (LocalFoldLongQuotes.current && isLongQuote(block, quoteDepth)) {
        FoldableQuoteBlock(block, quoteDepth, onGoToCitedPost)
        return
    }
    QuoteFrame(quoteDepth = quoteDepth, isBareQuote = isBareQuote(block)) {
        QuoteHeader(block, onGoToCitedPost)
        PostBlocksRenderer(
            blocks = block.content.blocks,
            quoteDepth = quoteDepth + 1,
            onGoToCitedPost = onGoToCitedPost,
        )
    }
}

/**
 * #252 — the "Citation de X" / "Citation" header shown above every framed quote so the block always
 * reads as a quotation, not a stray indented paragraph. A bare quote (no author) falls back to the
 * generic "Citation" label. Extracted so the expanded, depth-collapsed and long-fold variants share
 * one source of truth for the header text.
 *
 * #699 — when the quote carries its source coordinates (`page` + `numreponse`, parsed from the
 * citation href) AND the surface wired [onGoToCitedPost], the header becomes the « go to the cited
 * post » affordance: primary tint + tap. Quotes whose href HFR served in the dynamic `forum2.php`
 * form (coordinates unparsed, cf. PostContentParser) keep the inert neutral header — no invented
 * fallback target.
 */
@Composable
private fun QuoteHeader(
    block: PostBlock.Quote,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    val page = block.page
    val numreponse = block.numreponse
    val text = block.author
        ?.let { stringResource(R.string.post_quote_author, it) }
        ?: stringResource(R.string.post_quote_bare)
    if (onGoToCitedPost != null && page != null && numreponse != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                onClickLabel = stringResource(R.string.post_quote_go_to),
            ) { onGoToCitedPost(page, numreponse) },
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Issue #332 — a "long" top-level citation (`isLongQuote`) folds by default, dépliable au clic puis
 * repliable. Since #784 the folded state is a bounded PREVIEW ([LongQuotePreview]: the first
 * ~[LONG_QUOTE_PREVIEW_LINES] lines, clipped, with a bottom fade) instead of a bare header line, so
 * the reader gets enough context to decide whether to unfold. Reuses [QuoteFrame] so the accent
 * bar, surface and bare-quote palette stay identical to a normal quote. Unlike
 * [CollapsedQuoteBlock] (issue #3 depth fold, which resets depth to 0 on reveal) this fold keeps the
 * real [quoteDepth] when expanded so a long quote that also nests deeply still hits the depth rule.
 *
 * Gesture contract (#784, Codex framing): the FRAME — preview body, fade, « Déplier »/« Replier »
 * label — toggles the fold, with its own a11y `onClickLabel`; the HEADER keeps its distinct #699
 * « go to the cited post » tap (its clickable consumes the event before the frame's). Inline links
 * inside the preview keep consuming their own taps, like everywhere else in the renderer.
 */
@Composable
private fun FoldableQuoteBlock(
    block: PostBlock.Quote,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
    var expanded by rememberSaveable(block) { mutableStateOf(false) }
    QuoteFrame(
        quoteDepth = quoteDepth,
        isBareQuote = isBareQuote(block),
        modifier = Modifier.clickable(
            onClickLabel = stringResource(
                if (expanded) R.string.post_quote_collapse_label else R.string.post_quote_expand_label,
            ),
        ) { expanded = !expanded },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // #699 — the header keeps its own go-to-cited-post tap INSIDE the frame's fold toggle:
            // its clickable consumes the tap, the rest of the frame still folds/unfolds.
            QuoteHeader(block, onGoToCitedPost)
            Text(
                text = if (expanded) {
                    stringResource(R.string.post_quote_collapse)
                } else {
                    stringResource(R.string.post_quote_expand)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            PostBlocksRenderer(
                blocks = block.content.blocks,
                quoteDepth = quoteDepth + 1,
                onGoToCitedPost = onGoToCitedPost,
            )
        } else {
            LongQuotePreview(block, quoteDepth, onGoToCitedPost)
        }
    }
}

/** #784 — height of the bottom fade hinting at the clipped remainder of a folded preview. */
private val LONG_QUOTE_FADE_HEIGHT: Dp = 28.dp

/**
 * #784 — bounded, clipped preview of a folded long quote: the normal [PostBlocksRenderer] inside a
 * `heightIn(max = ~5 bodyMedium lines)` + `clipToBounds` container, with a bottom fade towards the
 * frame's own container colour hinting at the hidden remainder.
 *
 * STRICTLY a container: no AnnotatedString is ever rebuilt to measure or truncate the text (the
 * #175 invariance pivot — the paragraphs inside are byte-identical to the expanded render), and no
 * intrinsic measurement is asked of the subtree (`SubcomposeAsyncImage` crashes under
 * `IntrinsicSize`, cf. the [QuoteFrame] history note). The fade is skipped when the content
 * actually fits under the cap ([isLongQuotePreviewClipped]) so a char-count-long but visually
 * short quote is not painted with a misleading « more below » scrim.
 */
@Composable
private fun LongQuotePreview(
    block: PostBlock.Quote,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)?,
) {
    val bodyLineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val density = LocalDensity.current
    // Sp-based cap so the preview keeps showing ~the same LINE COUNT at any font scale.
    val maxHeight = with(density) {
        longQuotePreviewMaxHeightSp(
            bodyLineHeightSp = if (bodyLineHeight.isSp) bodyLineHeight.value else 0f,
        ).sp.toDp()
    }
    val maxHeightPx = with(density) { maxHeight.toPx() }
    // The fade dissolves the clipped last line into the quote card's own surface colour.
    val fadeColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                if (isLongQuotePreviewClipped(contentHeightPx = size.height, maxHeightPx = maxHeightPx)) {
                    val fadeHeightPx = LONG_QUOTE_FADE_HEIGHT.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, fadeColor),
                            startY = size.height - fadeHeightPx,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - fadeHeightPx),
                        size = Size(size.width, fadeHeightPx),
                    )
                }
            },
    ) {
        PostBlocksRenderer(
            blocks = block.content.blocks,
            quoteDepth = quoteDepth + 1,
            onGoToCitedPost = onGoToCitedPost,
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
private fun CollapsedQuoteBlock(
    block: PostBlock.Quote,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
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
            // #252 — same "Citation"/"Citation de X" header rule as the expanded QuoteBlock.
            QuoteHeader(block, onGoToCitedPost)
            PostBlocksRenderer(
                blocks = block.content.blocks,
                quoteDepth = 0,
                onGoToCitedPost = onGoToCitedPost,
            )
        }
    }
}

/**
 * #785 — placeholder for a quote whose author is black-listed, mirroring the [CollapsedQuoteBlock]
 * interaction (one-line label + « Afficher »/« Masquer », the whole frame toggles) and the topic
 * screen's `HiddenPostCard` copy (the pseudo stays visible, consistent with the post-level mask).
 * The reveal is per-quote and transient (`rememberSaveable`, same lifetime as the other folds).
 * Unlike [CollapsedQuoteBlock] the reveal keeps the REAL depth (`quoteDepth + 1`, like the expanded
 * render): revealing a blocked quote must not grant extra nesting levels, and a blocked citation
 * nested inside the revealed body stays masked through the recursion.
 */
@Composable
private fun BlockedQuoteBlock(
    block: PostBlock.Quote,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
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
                // isBlockedQuoteAuthor never matches a null author, so the fallback is defensive.
                text = stringResource(R.string.post_quote_blocked_author, block.author.orEmpty()),
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
            // #252/#699 — same header rule as the expanded QuoteBlock (and same jump affordance).
            QuoteHeader(block, onGoToCitedPost)
            PostBlocksRenderer(
                blocks = block.content.blocks,
                quoteDepth = quoteDepth + 1,
                onGoToCitedPost = onGoToCitedPost,
            )
        }
    }
}

@Composable
private fun SpoilerBlock(
    block: PostBlock.Spoiler,
    quoteDepth: Int,
    onGoToCitedPost: ((page: Int, numreponse: Int) -> Unit)? = null,
) {
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
                    onGoToCitedPost = onGoToCitedPost,
                )
            }
        }
    }
}

@Composable
private fun ImageBlock(block: PostBlock.Image) = BlockImage(url = block.url, description = block.description)

/**
 * Centred, bounded block image on its own line. The home of a standalone `PostBlock.Image`, and
 * (since #224 option B) of a large image promoted out of an image-only paragraph.
 *
 * When [linkUrl] is non-null the image was posted as `[url=…][img]` (the "click to enlarge" pattern):
 * the whole block is tappable and opens that URL (#257), so a linked image gets the block treatment
 * AND keeps its tap-through instead of being kept as a small inline thumbnail.
 *
 * #610 — a MEASURED image renders in a box of EXACTLY its web-parity display size
 * ([PostMediaDisplayPolicy.blockImageDisplaySize]: native size, no upscale, width ≤ 90% of the
 * column, height ≤ 200 dp), centred. Before #610 the container FILLED the column width — upscaling
 * any narrower source — with its height clamped to the legacy [160, 480] dp slot; that slot now only
 * hosts a not-yet-measured image (cold cache / failed measurement). Bounded either way, so a
 * 4000×3000 RAW screenshot can't blow up the post and destroy the scroll position.
 *
 * #249 — anti-CLS survives the #610 unification: the exact box is computed BEFORE the bitmap arrives
 * (same measured-intrinsic cache as #175/#224), so the shimmer placeholder occupies the loaded
 * image's slot and nothing below moves; the image then `crossfade`s in (Coil native) into the
 * already-sized box. SubcomposeAsyncImage exposes loading/error slots so the user gets visual
 * feedback when an HFR image host (rehost.diberie.com, super-h.fr, …) is offline rather than a
 * silent empty Box. Animations honour the system reduce-motion preference
 * ([rememberAnimationsEnabled]).
 */
@Composable
private fun BlockImage(url: String, description: String?, linkUrl: String? = null) {
    val uriHandler = LocalUriHandler.current
    val openLabel = stringResource(R.string.post_image_open_link)
    val animationsEnabled = rememberAnimationsEnabled()

    // #249 — reserve the exact final box from the measured intrinsic size when known. The cache is fed by
    // the #175/#224 paragraph measure effect (promoted images) AND, since #249 follow-up, by the effect
    // just below for standalone PostBlock.Image. Until a measurement lands (cold cache) it is null and the
    // legacy min/max slot is used for that first frame.
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    val measured: IntSize? = sizeCache.get(url)
    // #249 follow-up — a standalone PostBlock.Image is NOT covered by the paragraph measure effect, so
    // without this its intrinsic size never lands in the cache: blockImageDisplaySize stays null, the
    // image falls into the legacy min/max slot and loses both the exact parity box (#610) and the
    // reserved loading space (#249 anti-CLS). Measure it here through the same guarded seam the
    // paragraph effect uses; the SnapshotStateMap write then recomposes this block onto the exact-box
    // path.
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(url, sizeCache, platformContext) {
        measureAndCacheIntrinsicMediaSize(
            url = url,
            cache = sizeCache,
            context = platformContext,
            imageLoader = SingletonImageLoader.get(platformContext),
        )
    }

    // contentAlignment centres the (usually narrower-than-column, #610) exact box on its own line —
    // the same visual centring the pre-#610 full-width Fit letterboxing produced.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val displaySize = PostMediaDisplayPolicy.blockImageDisplaySize(
            measured = measured?.let { PixelSize(it.width, it.height) },
            availableWidthDp = maxWidth.value,
        )
        // #610/#249 — the EXACT web-parity box when measured (no upscale, ≤ 90% width, ≤ 200 dp tall;
        // anti-CLS: it is also the reserved loading slot), else the legacy full-width min/max slot.
        val sizeModifier = if (displaySize != null) {
            Modifier.size(displaySize.width.dp, displaySize.height.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PostMediaDisplayPolicy.blockImageMinHeight)
                .heightIn(max = PostMediaDisplayPolicy.blockImageMaxHeight)
        }
        // #831 — contextual image menu on long-press. The tap contract is preserved EXACTLY (Codex
        // framing, firm reserve): a linked image (#257) keeps its tap-through and gains the
        // long-press through ONE combinedClickable (a tap already exists there); an unlinked block
        // image gets a long-press-ONLY handler (no onClick — its tap stays inert as before). When
        // the surface provides no actions (MP threads, editor preview, signatures: default null),
        // or the URL is not actionable (data:/blob:/empty), the historical modifiers are
        // reproduced verbatim.
        val imageActions = LocalPostImageActions.current?.takeIf { isEligiblePostImageUrl(url) }
        val interactionModifier = when {
            imageActions != null && linkUrl != null ->
                // Role.Image (not Button): the element IS an image that opens its full version on
                // tap; the localized labels carry both actions for TalkBack. combinedClickable
                // brings the built-in long-press haptics (#436 precedent, MultiQuoteFab).
                Modifier.combinedClickable(
                    role = Role.Image,
                    onClickLabel = openLabel,
                    onLongClickLabel = stringResource(R.string.post_image_options_action),
                    onLongClick = {
                        imageActions.onLongPress(
                            PostImageTarget(url = url, description = description, linkUrl = linkUrl),
                        )
                    },
                ) {
                    runCatching { uriHandler.openUri(linkUrl) }
                }

            imageActions != null -> Modifier.postImageLongPress(
                actions = imageActions,
                target = PostImageTarget(url = url, description = description, linkUrl = null),
                haptics = LocalHapticFeedback.current,
                optionsLabel = stringResource(R.string.post_image_options_action),
            )

            linkUrl != null ->
                // Role.Image (not Button): the element IS an image that opens its full version on
                // tap; the localized onClickLabel carries the action for TalkBack.
                Modifier.clickable(role = Role.Image, onClickLabel = openLabel) {
                    runCatching { uriHandler.openUri(linkUrl) }
                }

            else -> Modifier
        }
        // #610 — the exact parity box centres via the BoxWithConstraints contentAlignment; no
        // fillMaxWidth here (the pre-#610 full-width shape is gone).
        val containerModifier = sizeModifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(interactionModifier)
        val request = remember(url, animationsEnabled, platformContext) {
            ImageRequest.Builder(platformContext)
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
                // Measured: fill the exact parity box (#610). Unmeasured (max-only constraint): a STABLE
                // min-height placeholder — NOT fillMaxSize, which would balloon to the max slot and then
                // collapse to the loaded intrinsic height (a visible shift, Codex review). The legacy
                // min→intrinsic grow on load remains for these rare unmeasured images.
                val shimmerModifier = if (displaySize != null) {
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
    // #553 — when true, the author's inline `[color]` styling is dropped (used for signatures, whose
    // web-tuned colours read as garish/illegible on the app theme). Text falls back to the caller's
    // colour. Default false: post bodies keep author colours.
    ignoreColors: Boolean = false,
    // State-hygiene audit 2026-07-05 — when true, author `[color]` values are clamped for
    // legibility on a dark surface via [ensureReadableColor] (and symmetrically on light).
    // Default false: existing callers/tests keep the light-theme behaviour.
    isDark: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    val media = MediaCounter()
    appendInlines(inlines, linkStyles, media, imageAlt, ignoreColors, isDark)
}

@Suppress("LongParameterList") // Recursive walker — every param is the same threaded context.
private fun AnnotatedString.Builder.appendInlines(
    inlines: List<PostInline>,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
    imageAlt: String,
    ignoreColors: Boolean,
    isDark: Boolean,
) {
    inlines.forEach { inline -> appendInline(inline, linkStyles, media, imageAlt, ignoreColors, isDark) }
}

// LongParameterList: recursive walker — every param is the same threaded context.
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private fun AnnotatedString.Builder.appendInline(
    inline: PostInline,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
    imageAlt: String,
    ignoreColors: Boolean,
    isDark: Boolean,
) {
    when (inline) {
        is PostInline.Text -> append(inline.value)
        PostInline.LineBreak -> append('\n')
        is PostInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
        }

        is PostInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
        }

        is PostInline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
        }

        is PostInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
        }

        // #553 — drop the author colour when asked (signatures): render the children plain so they
        // inherit the caller's neutral colour instead of the web-tuned, often-illegible author hue.
        // Otherwise the colour is kept but clamped for legibility on the current theme
        // (state-hygiene audit 2026-07-05): a web-tuned navy is unreadable on a dark surface.
        is PostInline.Color -> if (ignoreColors) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
        } else {
            withStyle(SpanStyle(color = ensureReadableColor(parseColor(inline.colorHex), isDark))) {
                appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
            }
        }

        is PostInline.Link -> withLink(LinkAnnotation.Url(inline.url, linkStyles)) {
            appendInlines(inline.children, linkStyles, media, imageAlt, ignoreColors, isDark)
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
 *
 * #256 — URLs carrying the `hfr-cc-image=true` marker are EXCLUDED: their box is pinned by the
 * [imageDisplayBox] fast-path (never read from the measurement cache), so probing them would only
 * spend a useless network round-trip. Block promotion is unaffected: an unmeasured URL never trips
 * [shouldPromoteImagesToBlocks], which is exactly the intended always-inline treatment for a marked
 * emoji.
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
        // #256 — cc-image-marked URLs are not measurable (fixed one-line box, see the KDoc above).
        is PostInline.InlineImage -> if (!isCcImageUrl(inline.url)) urls += inline.url
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
 * #224 (option A) / #610 — resolve an inline `[img]` placeholder box from its measured intrinsic
 * size via the unified web-parity policy [imageParityDisplaySize]: no upscale, height capped to
 * [IMAGE_MAX_HEIGHT_UNITS] (web `max-height: 200px`), width capped to the relative
 * `0.9 × contentWidth` ([maxWidthSp], web `max-width: 90%`) — the former absolute 240 sp width cap
 * is gone, see [IMAGE_PROMOTION_WIDTH_UNITS].
 *
 * #253 — while the measurement is in flight (cold cache / miss) it falls back to a small square of
 * [INLINE_IMAGE_MIN_HEIGHT_SP] (≈ one text line) rather than the old 240×180 bucket. With
 * `ContentScale.Fit` filling the box, a 240×180 cold box upscaled a 16×16 cc-image emoji to a giant
 * 180×180 flash until the measurement landed; a min-height square means the dominant cold case (the
 * 16×16 emoji) is already at its final size — zero flash — and any larger image just grows from a
 * one-line slot once measured instead of shrinking from a giant one. Still relative-capped so even
 * the fallback never overflows a narrow quote. Mirrors [smileyDisplayBox].
 *
 * #256 — a URL carrying the `hfr-cc-image=true` marker short-circuits ALL of the above: fixed
 * one-line square, no measurement involved (see [isCcImageUrl] and the fast-path comment below).
 */
internal fun imageDisplayBox(
    image: PostInline.InlineImage,
    measured: Map<String, IntSize?>,
    maxWidthSp: Int,
): InlineMediaBox {
    // #256 — render-time fast-path: a URL carrying the `hfr-cc-image=true` marker declares itself a
    // community cc-image emoji (a one-line glyph). Pin its box to the one-line square immediately —
    // the same square as the #253 cold fallback below, so a marked emoji is at its final size from
    // the very first frame (zero flash, zero reflow) — and IGNORE any measured size on record: the
    // marker, not the measurement, is the contract (matching + duplicate rules in [isCcImageUrl]).
    // The companion exclusion in [collectMeasurableImageUrl] skips the async probe for these URLs
    // entirely. Still relative-capped so even this square cannot overflow a pathologically narrow
    // container. Everything else (AST, link semantics, MediaCounter, promotion) is untouched.
    if (isCcImageUrl(image.url)) {
        val fixed = capToWidth(
            PixelSize(INLINE_IMAGE_MIN_HEIGHT_SP, INLINE_IMAGE_MIN_HEIGHT_SP),
            maxWidthSp,
        )
        return InlineMediaBox(fixed.width.sp, fixed.height.sp)
    }
    val size = measured[image.url]
    val base = if (size != null) {
        // #610 web-parity sizing (no upscale, height ≤ IMAGE_MAX_HEIGHT_UNITS, width ≤ the relative
        // maxWidthSp cap), then the #253 min-height floor so a SUB-16 low-res source can't render
        // below ~one text line. A cc-image emoji (16×16) sits exactly at the floor → kept native (per
        // @XaaT dogfood); only smaller sources get enlarged. NB: the floor only grows the BOX — the
        // bitmap fills it via ContentScale.Fit.
        //
        // Re-apply the parity caps AFTER the floor: a very wide/short source (e.g. 250×10) floored to
        // height 16 grows to ~400×16 — potentially past the relative width cap. The second pass clamps
        // that back, so the box never exceeds the caps for any aspect ratio (Codex review #246). The
        // floor stays the one sanctioned upscale, now bounded by the relative cap instead of the
        // former absolute 240 sp cap (#610).
        imageParityDisplaySize(
            upscaleToMinHeight(
                imageParityDisplaySize(
                    PixelSize(size.width, size.height),
                    maxWidthUnits = maxWidthSp,
                ),
                INLINE_IMAGE_MIN_HEIGHT_SP,
            ),
            maxWidthUnits = maxWidthSp,
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
 * centred blocks (each on its own line — since #610 the block size equals the inline size, promotion
 * is layout semantics only). Blank text and line breaks are ignored.
 *
 * Returns null when the paragraph has any other meaningful inline (non-blank text, a smiley): genuine
 * prose keeps its inline image treatment. A link wrapping ONLY an image is fine — #257 promotes it to a
 * block that opens the link on tap, so the "click to enlarge" tap-through is preserved (before #257 a
 * linked image was kept inline → the link stayed a text-span affair).
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
 * #224 (option B) — promote an image-only paragraph to blocks only once at least one image has
 * measured larger than the promotion thresholds (a real posted photo, not an emoji / small reaction:
 * a 16×16 cc-image never trips this and keeps its inline treatment). Returns false while every size
 * is unknown (cold) so promotion only kicks in after measurement.
 *
 * #610 — inline and block now SIZE identically ([imageParityDisplaySize]), so promotion is purely
 * layout semantics: the image gets its own centred line, the block loading/error UX, and the #257
 * link tap-through. The thresholds keep their pre-#610 calibration
 * ([IMAGE_PROMOTION_WIDTH_UNITS] = the former absolute inline width cap, [IMAGE_MAX_HEIGHT_UNITS]
 * = the shared parity height cap).
 */
internal fun shouldPromoteImagesToBlocks(
    images: List<PromotedImage>,
    measured: Map<String, IntSize?>,
): Boolean = images.any { promoted ->
    val size = measured[promoted.image.url] ?: return@any false
    size.width > IMAGE_PROMOTION_WIDTH_UNITS || size.height > IMAGE_MAX_HEIGHT_UNITS
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
        // #257/#610 — decode at a STABLE size (the flat INLINE_IMAGE_DECODE_CAP_PX bound) instead of
        // letting Coil resolve the size from the placeholder constraints. The box grows from the
        // cold-fallback square to the measured size when the measurement lands; with constraint-driven
        // sizing Coil re-decodes at the new size and, meanwhile, paints the previous tiny bitmap
        // upscaled → pixelated. A fixed decode size keeps ONE sharp bitmap that Fit scales into
        // whatever box (Coil never upscales the decode past the source, so a small image still decodes
        // at native). Before #610 the size derived from the absolute 240×200 sp display cap in px;
        // that width cap is now relative to the container, so the request uses the flat px bound
        // directly (still covers 0.9 × container at any realistic phone density/fontScale). The
        // request is remembered so a measurement landing doesn't rebuild it. Smileys keep their own
        // (much smaller) path — this cap is photo-sized.
        val context = LocalPlatformContext.current
        val request = remember(image.url, context) {
            ImageRequest.Builder(context)
                .data(image.url)
                .size(INLINE_IMAGE_DECODE_CAP_PX, INLINE_IMAGE_DECODE_CAP_PX)
                .scale(Scale.FIT)
                .precision(Precision.INEXACT)
                .build()
        }
        // #831 — the hosting surface (topic reading) may provide image actions; the lambda of an
        // InlineTextContent is @Composable, so the CompositionLocal is read HERE, without touching
        // the invariant AnnotatedString (#175) nor the remember keys of ParagraphBlock. Codex
        // framing (firm reserve): LONG-PRESS ONLY — no combinedClickable / no-op onClick on inline
        // images, which would eat the tap and disturb text selection around the image. The
        // long-press detector does claim the initial down, which is precisely what keeps the
        // parent SelectionContainer (#281) from starting a word selection under a finger resting
        // on the image; selection drags STARTED on the surrounding text still travel across the
        // image unaffected, and taps outside the image keep their behaviour.
        val imageActions = LocalPostImageActions.current
        val longPressModifier = if (imageActions != null && isEligiblePostImageUrl(image.url)) {
            Modifier.postImageLongPress(
                actions = imageActions,
                target = PostImageTarget(url = image.url, description = image.description, linkUrl = null),
                haptics = LocalHapticFeedback.current,
                optionsLabel = stringResource(R.string.post_image_options_action),
            )
        } else {
            Modifier
        }
        AsyncImage(
            model = request,
            contentDescription = image.description,
            contentScale = PostMediaDisplayPolicy.inlineImageContentScale,
            modifier = Modifier.fillMaxSize().then(longPressModifier),
        )
    }
}

/**
 * #831 — long-press-ONLY gesture + a11y surface for a post image. Deliberately NOT a
 * `combinedClickable`: that would install an onClick and consume every tap (Codex framing, firm
 * reserve — cf. the call-site comments for the tap contracts it would break).
 *
 * `detectTapGestures(onLongPress)` DOES claim the gesture's down, and that claim is load-bearing:
 * a fully non-consuming variant (`awaitEachGesture` + `awaitLongPressOrCancellation`) was tried
 * after the Codex gate flagged the consumption, and its own tests proved it worse — with the down
 * unclaimed, the parent SelectionContainer (#281) ALSO reacts to the long press, starting a word
 * selection with its magnifier underneath the menu sheet (the Robolectric suite crashes in
 * `Magnifier.dismiss`, pinning exactly that). The claim costs what a tap on an inline image was
 * already documented to cost (a no-op — the known clear-selection loss), and does NOT break
 * scrolling: Compose `scrollable` starts from unconsumed MOVE deltas past the touch slop and is
 * insensitive to a consumed down. Selection drags STARTED on surrounding text never reach this
 * node (they are captured by the text at their own down) and keep travelling across the image.
 * The `semantics` block exposes the same action to TalkBack as a custom long-click action
 * labelled [optionsLabel] (`combinedClickable`'s `onLongClickLabel` equivalent).
 *
 * Non-composable on purpose (the resolved [haptics]/[optionsLabel] come in as parameters):
 * composable `Modifier` factories are flagged by the Compose lint (`ComposableModifierFactory`).
 */
private fun Modifier.postImageLongPress(
    actions: PostImageActions,
    target: PostImageTarget,
    haptics: HapticFeedback,
    optionsLabel: String,
): Modifier = this
    .pointerInput(actions, target) {
        detectTapGestures(
            onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.onLongPress(target)
            },
        )
    }
    .semantics {
        onLongClick(label = optionsLabel) {
            actions.onLongPress(target)
            true
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

/**
 * State-hygiene audit 2026-07-05 — clamps an author `[color]`'s relative luminance into the
 * current theme's readable range, hue preserved. A web-tuned navy (`#000080`) is invisible on a
 * dark/AMOLED surface; symmetrically a pale yellow washes out on light. Pure function (no
 * Composable dependency) so it stays JVM-testable like [parseColor]:
 *
 * - dark theme + luminance below [MIN_DARK_LUMINANCE] → lerp towards White just enough to reach
 *   the floor;
 * - light theme + luminance above [MAX_LIGHT_LUMINANCE] → lerp towards Black down to the ceiling;
 * - already-readable colours (e.g. `#CC0000`) pass through UNTOUCHED in both themes — this is a
 *   clamp, not a remap.
 *
 * The minimal lerp fraction is found by binary search: Compose's [lerp] interpolates in Oklab, so
 * luminance is not linear in the fraction (a closed form would be wrong). The invariant
 * `reached(high)` guarantees the returned colour satisfies the threshold. The original alpha is
 * preserved (the defensive #RRGGBBAA parse path).
 */
internal fun ensureReadableColor(color: Color, isDark: Boolean): Color {
    if (color == Color.Unspecified) return color
    val luminance = color.luminance()
    return when {
        isDark && luminance < MIN_DARK_LUMINANCE ->
            clampLuminance(color, towards = Color.White) { it >= MIN_DARK_LUMINANCE }
        !isDark && luminance > MAX_LIGHT_LUMINANCE ->
            clampLuminance(color, towards = Color.Black) { it <= MAX_LIGHT_LUMINANCE }
        else -> color
    }
}

/** Smallest-fraction lerp of [color] towards [towards] whose luminance satisfies [reached]. */
private fun clampLuminance(color: Color, towards: Color, reached: (Float) -> Boolean): Color {
    var low = 0f
    // `reached(1f)` always holds: a full lerp IS the target (White = 1.0, Black = 0.0).
    var high = 1f
    repeat(LUMINANCE_CLAMP_ITERATIONS) {
        val mid = (low + high) / 2f
        if (reached(lerp(color, towards, mid).luminance())) high = mid else low = mid
    }
    return lerp(color, towards, high).copy(alpha = color.alpha)
}

/**
 * Dark detection threshold on the surface's relative luminance — duplicated from
 * `CreatorHighlight.DARK_SURFACE_LUMINANCE` (private there): follows AMOLED and a forced
 * ThemeMode, not just the system flag.
 */
private const val DARK_SURFACE_LUMINANCE = 0.5f

/**
 * Author-colour luminance floor on dark surfaces. Deliberately conservative (a CLAMP for the
 * unreadable tail, not a beautifier): classic dark-but-readable hues like `#CC0000` (≈ 0.13) or
 * pure red `#FF0000` (≈ 0.21) pass untouched, while navy `#000080` (≈ 0.016) or pure blue
 * `#0000FF` (≈ 0.07) — invisible on near-black — get lifted to the floor.
 */
internal const val MIN_DARK_LUMINANCE = 0.1f

/** Author-colour luminance ceiling on light surfaces (pure yellow `#FFFF00` ≈ 0.93 gets darkened). */
internal const val MAX_LIGHT_LUMINANCE = 0.78f

/** Binary-search depth for [clampLuminance] — 2^-12 fraction precision, plenty for 8-bit channels. */
private const val LUMINANCE_CLAMP_ITERATIONS = 12

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
