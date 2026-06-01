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
import androidx.compose.ui.res.stringResource
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
    val hasMedia = remember(inlines) { hasInlineMedia(inlines) }

    // Plain-text paragraph (the common case): no inline media to size, so render directly with the
    // bodyMedium rhythm and skip the whole #175 machinery — no cache reads, no measurement effect, no
    // BoxWithConstraints/SubcomposeLayout wrapper.
    if (!hasMedia) {
        if (annotated.text.isBlank()) return
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

    // Two guards against a tall/large inline smiley overlapping the text:
    //  - width: cap each smiley to RF1's `img { max-width: 90% }` of the content width (read from
    //    BoxWithConstraints, which shrinks with quote depth) so it never overflows a narrow quote;
    //  - height: drop bodyMedium's fixed `lineHeight` so the LINE GROWS to contain the baseline-aligned
    //    placeholder. With the clamp a tall sprite overflowed UP off its line onto the line above
    //    (measured top y=-22 over a 28sp first line); unspecified lineHeight lets the ascent expand → zero overlap.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // #175 (smileys) + #224 (inline images) — RF1's `img { max-width: 90% }` relative cap, read
        // from the container width here (the only place it's known; it shrinks with quote depth).
        val maxMediaWidthSp = (maxWidth.value * SMILEY_RELATIVE_MAX_WIDTH_FRACTION).roundToInt()
        val inlineContent = remember(inlines, measuredSizes, maxMediaWidthSp) {
            collectInlineMedia(
                inlines,
                smileyBox = { smiley -> smileyDisplayBox(smiley, measuredSizes, maxMediaWidthSp) },
                imageBox = { image -> imageDisplayBox(image, measuredSizes, maxMediaWidthSp) },
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
): Map<String, InlineTextContent> {
    val out = mutableMapOf<String, InlineTextContent>()
    val media = MediaCounter()
    walkInlinesForMedia(inlines, out, media, smileyBox, imageBox)
    return out
}

private fun walkInlinesForMedia(
    inlines: List<PostInline>,
    out: MutableMap<String, InlineTextContent>,
    media: MediaCounter,
    smileyBox: (PostInline.Smiley) -> InlineMediaBox,
    imageBox: (PostInline.InlineImage) -> InlineMediaBox,
) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.InlineImage ->
                out += media.nextImage() to imageInlineContent(inline, imageBox(inline))

            is PostInline.Smiley -> {
                if (inline.imageUrl == null) return@forEach
                out += media.nextSmiley() to smileyInlineContent(inline, smileyBox(inline))
            }

            is PostInline.Strong -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
            is PostInline.Emphasis -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
            is PostInline.Underline -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
            is PostInline.Strike -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
            is PostInline.Color -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
            is PostInline.Link -> walkInlinesForMedia(inline.children, out, media, smileyBox, imageBox)
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
 * [intrinsicSmileyDisplaySize] policy, then a min-height floor ([INLINE_IMAGE_MIN_HEIGHT_SP]) so a tiny
 * low-res source (cc-image emoji, 16×16) is upscaled to a legible size like RF1, then the relative
 * `0.9 × contentWidth` cap ([maxWidthSp]). While
 * the measurement is in flight (cold cache / miss) it falls back to the historical 240×180 bucket,
 * still relative-capped so even the fallback never overflows a narrow quote. Mirrors [smileyDisplayBox];
 * this is what removes the empty frame around a small reaction image (vs the old fixed 240×180 box).
 */
internal fun imageDisplayBox(
    image: PostInline.InlineImage,
    measured: Map<String, IntSize?>,
    maxWidthSp: Int,
): InlineMediaBox {
    val size = measured[image.url]
    val base = if (size != null) {
        // Reuse the generic #175 no-upscale + cap policy with the inline-image caps (not smiley caps),
        // then floor the height so a tiny low-res source (cc-image emoji, 16×16) is upscaled to a
        // legible size like RF1 instead of staying microscopic.
        upscaleToMinHeight(
            intrinsicSmileyDisplaySize(
                PixelSize(size.width, size.height),
                maxWidthSp = INLINE_IMAGE_MAX_WIDTH_SP,
                maxHeightSp = INLINE_IMAGE_MAX_HEIGHT_SP,
            ),
            INLINE_IMAGE_MIN_HEIGHT_SP,
        )
    } else {
        val bucket = PostMediaDisplayPolicy.inlineImage
        PixelSize(bucket.placeholderWidth.value.roundToInt(), bucket.placeholderHeight.value.roundToInt())
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

internal fun imageInlineContent(image: PostInline.InlineImage, box: InlineMediaBox): InlineTextContent {
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
        // sp-based placeholder under any fontScale. ContentScale.Fit makes the bitmap fill that box
        // (the no-upscale rule lives in the BOX sizing, imageDisplayBox) — so a floored tiny emoji is
        // actually drawn at the box size, not left 16×16 in the middle of it.
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
