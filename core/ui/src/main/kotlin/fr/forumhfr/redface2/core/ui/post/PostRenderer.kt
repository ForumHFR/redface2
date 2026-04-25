package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind

private const val MAX_VISIBLE_QUOTE_DEPTH = 3

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
            }
        }
    }
}

@Composable
private fun ParagraphBlock(inlines: List<PostInline>) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val annotated = remember(inlines, linkStyles) { buildInlineText(inlines, linkStyles) }
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
    if (quoteDepth >= MAX_VISIBLE_QUOTE_DEPTH) {
        Text(
            text = "Citation imbriquée masquée",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            block.author?.let { author ->
                Text(
                    text = "Citation de $author",
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
                    text = block.label ?: "Spoiler",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (revealed) "(masquer)" else "(afficher)",
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
    AsyncImage(
        model = block.url,
        contentDescription = block.description,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun buildInlineText(
    inlines: List<PostInline>,
    linkStyles: TextLinkStyles,
): AnnotatedString = buildAnnotatedString {
    val media = MediaCounter()
    appendInlines(inlines, linkStyles, media)
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<PostInline>,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
) {
    inlines.forEach { inline -> appendInline(inline, linkStyles, media) }
}

@Suppress("CyclomaticComplexMethod")
private fun AnnotatedString.Builder.appendInline(
    inline: PostInline,
    linkStyles: TextLinkStyles,
    media: MediaCounter,
) {
    when (inline) {
        is PostInline.Text -> append(inline.value)
        PostInline.LineBreak -> append('\n')
        is PostInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.Color -> withStyle(SpanStyle(color = parseColor(inline.colorHex))) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.Link -> withLink(LinkAnnotation.Url(inline.url, linkStyles)) {
            appendInlines(inline.children, linkStyles, media)
        }

        is PostInline.InlineImage -> appendInlineContent(media.nextImage(), inline.description ?: "[image]")
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

private fun collectInlineMedia(inlines: List<PostInline>): Map<String, InlineTextContent> {
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
                out += media.nextImage() to imageInlineContent(inline.url, inline.description)

            is PostInline.Smiley -> {
                val url = inline.imageUrl ?: return@forEach
                out += media.nextSmiley() to smileyInlineContent(url, inline.kind.token())
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

private fun imageInlineContent(url: String, description: String?): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(
            width = 240.sp,
            height = 180.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {
        AsyncImage(
            model = url,
            contentDescription = description,
            modifier = Modifier.fillMaxWidth(),
        )
    }

private fun smileyInlineContent(url: String, contentDescription: String): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(
            width = 18.sp,
            height = 18.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }

private fun SmileyKind.token(): String = when (this) {
    is SmileyKind.Builtin -> code
    is SmileyKind.Perso -> "[:$name]"
}

private fun parseColor(hex: String): Color {
    val value = hex.removePrefix("#")
    return when (value.length) {
        6, 8 -> Color(android.graphics.Color.parseColor("#$value"))
        else -> Color.Unspecified
    }
}

private class MediaCounter {
    private var image = 0
    private var smiley = 0
    fun nextImage(): String = "post-image-${image++}"
    fun nextSmiley(): String = "post-smiley-${smiley++}"
}
