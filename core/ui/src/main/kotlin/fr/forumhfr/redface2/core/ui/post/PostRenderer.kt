package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind

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
    val annotated = remember(inlines, linkStyles, imageAlt) {
        buildInlineText(inlines, linkStyles, imageAlt)
    }
    val inlineContent = remember(inlines) { collectInlineMedia(inlines) }
    if (annotated.text.isBlank() && inlineContent.isEmpty()) {
        return
    }
    Text(
        text = annotated,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
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
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(QUOTE_ACCENT_WIDTH)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
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

internal fun collectInlineMedia(inlines: List<PostInline>): Map<String, InlineTextContent> {
    val out = mutableMapOf<String, InlineTextContent>()
    val media = MediaCounter()
    walkInlinesForMedia(inlines, out, media)
    return out
}

private fun walkInlinesForMedia(
    inlines: List<PostInline>,
    out: MutableMap<String, InlineTextContent>,
    media: MediaCounter,
) {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.InlineImage ->
                out += media.nextImage() to imageInlineContent(inline)

            is PostInline.Smiley -> {
                if (inline.imageUrl == null) return@forEach
                out += media.nextSmiley() to smileyInlineContent(inline)
            }

            is PostInline.Strong -> walkInlinesForMedia(inline.children, out, media)
            is PostInline.Emphasis -> walkInlinesForMedia(inline.children, out, media)
            is PostInline.Underline -> walkInlinesForMedia(inline.children, out, media)
            is PostInline.Strike -> walkInlinesForMedia(inline.children, out, media)
            is PostInline.Color -> walkInlinesForMedia(inline.children, out, media)
            is PostInline.Link -> walkInlinesForMedia(inline.children, out, media)
            else -> Unit
        }
    }
}

internal fun imageInlineContent(image: PostInline.InlineImage): InlineTextContent {
    val box = PostMediaDisplayPolicy.inlineImage
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
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

internal fun smileyInlineContent(smiley: PostInline.Smiley): InlineTextContent {
    val box = PostMediaDisplayPolicy.smileyBox(smiley)
    val description = smiley.kind.token()
    return InlineTextContent(
        placeholder = Placeholder(
            width = box.placeholderWidth,
            height = box.placeholderHeight,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
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
