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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
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

@Composable
fun PostRenderer(
    content: PostContent,
    modifier: Modifier = Modifier,
) {
    PostBlocksRenderer(blocks = content.blocks, modifier = modifier, quoteDepth = 0)
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

    // #175 — adaptive smiley sizing. Read each smiley's measured native size from the URL cache;
    // the reads are tracked snapshot reads, so when a measurement lands the SnapshotStateMap write
    // recomposes this block and only the inline-content Map (not the AnnotatedString) is rebuilt at
    // the final size. Cold/miss → a provisional fallback to minimise reflow (builtin ~16, perso 70×50).
    val sizeCache = LocalIntrinsicMediaSizeCache.current
    val smileyUrls = remember(inlines) { collectSmileyUrls(inlines) }
    val measuredSizes: Map<String, IntSize?> = smileyUrls.associateWith { sizeCache.get(it) }

    // Measure the not-yet-known URLs. Coil's execute() is a main-safe suspend call (it dispatches its
    // own I/O), and reuses the shared SingletonImageLoader caches the rendering AsyncImage hits — so
    // no double network fetch. A dead URL is recorded as a failure (TTL) so it is not re-fetched.
    val platformContext = LocalPlatformContext.current
    LaunchedEffect(smileyUrls) {
        val loader = SingletonImageLoader.get(platformContext)
        smileyUrls.forEach { url ->
            val now = System.currentTimeMillis()
            if (sizeCache.get(url) == null && !sizeCache.isFailureFresh(url, now)) {
                val size = measureIntrinsicMediaSize(url, platformContext, loader)
                if (size != null) sizeCache.putSuccess(url, size) else sizeCache.putFailure(url, now)
            }
        }
    }

    val hasMedia = remember(inlines) { hasInlineMedia(inlines) }
    if (annotated.text.isBlank() && !hasMedia) {
        return
    }
    // #175 — two guards against a tall/large inline smiley overlapping the text:
    //  - width: cap each smiley to RF1's `img { max-width: 90% }` of the content width (read from
    //    BoxWithConstraints, which shrinks with quote depth) so it never overflows a narrow quote;
    //  - height: for media paragraphs drop bodyMedium's fixed `lineHeight` so the LINE GROWS to
    //    contain the (baseline-aligned) placeholder. With the clamp a tall sprite overflowed UP off
    //    its line onto the line above (measured top y=-22 over a 28sp first line); unspecified
    //    lineHeight lets the ascent expand → zero overlap. Plain-text paragraphs keep the bodyMedium rhythm.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxSmileyWidthSp = (maxWidth.value * SMILEY_RELATIVE_MAX_WIDTH_FRACTION).roundToInt()
        val inlineContent = remember(inlines, measuredSizes, maxSmileyWidthSp) {
            collectInlineMedia(inlines) { smiley -> smileyDisplayBox(smiley, measuredSizes, maxSmileyWidthSp) }
        }
        val baseStyle = MaterialTheme.typography.bodyMedium
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = if (hasMedia) baseStyle.copy(lineHeight = TextUnit.Unspecified) else baseStyle,
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
    QuoteFrame(quoteDepth = quoteDepth) {
        block.author?.let { author ->
            Text(
                text = stringResource(R.string.post_quote_author, author),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 */
@Composable
private fun QuoteFrame(
    quoteDepth: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = if (quoteDepth % 2 == 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        // Quote accent bar (4dp, primary/tertiary alternated by depth).
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
            block.author?.let { author ->
                Text(
                    text = stringResource(R.string.post_quote_author, author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun ImageBlock(block: PostBlock.Image) {
    // Bounded so a 4000×3000 RAW screenshot can't blow up the post and destroy the scroll
    // position. SubcomposeAsyncImage exposes loading/error slots so the user gets visual
    // feedback when an HFR image host (rehost.diberie.com, super-h.fr, …) is offline rather
    // than a silent empty Box. Phase 1 keeps the loading + error layout minimal — no
    // material-icons-extended dependency just for a placeholder glyph.
    val containerModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = PostMediaDisplayPolicy.blockImageMinHeight)
        .heightIn(max = PostMediaDisplayPolicy.blockImageMaxHeight)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    SubcomposeAsyncImage(
        model = block.url,
        contentDescription = block.description,
        contentScale = ContentScale.Fit,
        modifier = containerModifier,
        loading = { ImageBlockLoading() },
        error = { ImageBlockError(block.description) },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun ImageBlockLoading() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.post_image_loading),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    MonospaceContainer {
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
    MonospaceContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.language?.let { lang ->
                Text(
                    text = lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

/**
 * Wraps a `[fixed]` / `[code]` body in a tinted card with horizontal scroll. Long lines (raw URL,
 * indented snippets, syntax-highlighted source) must overflow horizontally instead of wrapping —
 * wrap would mangle indentation and break the visual contract of a monospace block.
 *
 * Modifier order matters here: the **outer** [Card] carries [Modifier.fillMaxWidth] so the card
 * itself spans the parent. The **inner** [Column] must NOT carry [Modifier.fillMaxWidth] before
 * [Modifier.horizontalScroll] — that would clamp the children's measured width to the card's
 * width and turn the scroll into a no-op. [Modifier.padding] sits before the scroll modifier so
 * the inset is fixed and the children scroll inside it (otherwise the left padding would slide
 * out of view on overflow). The monospace [Text] children opt out of soft wrap explicitly.
 */
@Composable
private fun MonospaceContainer(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
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
): Map<String, InlineTextContent> {
    val out = mutableMapOf<String, InlineTextContent>()
    val media = MediaCounter()
    walkInlinesForMedia(inlines, out, media, smileyBox)
    return out
}

private fun walkInlinesForMedia(
    inlines: List<PostInline>,
    out: MutableMap<String, InlineTextContent>,
    media: MediaCounter,
    smileyBox: (PostInline.Smiley) -> InlineMediaBox,
) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.InlineImage ->
                out += media.nextImage() to imageInlineContent(inline)

            is PostInline.Smiley -> {
                if (inline.imageUrl == null) return@forEach
                out += media.nextSmiley() to smileyInlineContent(inline, smileyBox(inline))
            }

            is PostInline.Strong -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            is PostInline.Emphasis -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            is PostInline.Underline -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            is PostInline.Strike -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            is PostInline.Color -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            is PostInline.Link -> walkInlinesForMedia(inline.children, out, media, smileyBox)
            else -> Unit
        }
    }
}

/** Collects the distinct (non-null) smiley image URLs of [inlines], for #175 measurement. */
private fun collectSmileyUrls(inlines: List<PostInline>): Set<String> {
    val urls = LinkedHashSet<String>()
    fun walk(list: List<PostInline>) {
        list.forEach { inline ->
            when (inline) {
                is PostInline.Smiley -> inline.imageUrl?.let { urls += it }
                is PostInline.Strong -> walk(inline.children)
                is PostInline.Emphasis -> walk(inline.children)
                is PostInline.Underline -> walk(inline.children)
                is PostInline.Strike -> walk(inline.children)
                is PostInline.Color -> walk(inline.children)
                is PostInline.Link -> walk(inline.children)
                else -> Unit
            }
        }
    }
    walk(inlines)
    return urls
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

internal fun imageInlineContent(image: PostInline.InlineImage): InlineTextContent {
    val box = PostMediaDisplayPolicy.inlineImage
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
            // Inline [img] deliberately keeps Center, unlike smileys (which moved to AboveBaseline
            // for web parity in #203). An embedded image is a 240×180 block of user media, not an
            // emotive glyph riding the text baseline: centring it on the line reads better and
            // matches how a wrapped thumbnail sits next to text. Do not "unify" this with the
            // smiley alignment without a visual pass — the two contracts are intentionally distinct.
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {
        // The image fills the placeholder via fillMaxSize() so the rendered size tracks the
        // sp-based placeholder under any fontScale. Inline [img] keeps a no-upscale content
        // scale, unlike smileys: arbitrary small user images should not be blown up.
        AsyncImage(
            model = image.url,
            contentDescription = image.description,
            contentScale = PostMediaDisplayPolicy.inlineImageContentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal fun smileyInlineContent(smiley: PostInline.Smiley, box: InlineMediaBox): InlineTextContent {
    val description = smiley.kind.token()
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
            // #175 — AboveBaseline: the sprite bottom sits on the text baseline, exactly like a bare
            // <img> in the browser (web/RF1 parity, consistent with #203). The line's text stays on its
            // baseline (aligned with neighbouring lines) and a tall smiley rises above it. This avoids
            // overlap ONLY because media paragraphs drop bodyMedium's fixed lineHeight (see
            // ParagraphBlock): with the clamp, AboveBaseline pushed a tall sprite UP off its line onto
            // the line above (measured top y=-22 over a 28sp first line); letting the line grow lets the
            // ascent expand to contain it → ZERO overlap. (Center was trialled — zero overlap too — but
            // it floated the line's text at the smiley's mid-height, breaking its baseline vs neighbours.)
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        ),
    ) {
        AsyncImage(
            model = smiley.imageUrl,
            contentDescription = description,
            contentScale = PostMediaDisplayPolicy.smileyContentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

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
