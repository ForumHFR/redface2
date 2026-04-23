package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

@Composable
internal fun TopicHtmlRenderer(
    html: String,
    modifier: Modifier = Modifier,
    quoteDepth: Int = 0,
) {
    val blocks = remember(html) { parseBlocks(html) }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is TopicContentBlock.Paragraph -> {
                    val text = remember(block.html, linkStyles) { renderInlineTopicHtml(block.html, linkStyles) }
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                is TopicContentBlock.Quote -> {
                    if (quoteDepth >= 3) {
                        Text(
                            text = "Citation imbriquée masquée",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
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
                                TopicHtmlRenderer(
                                    html = block.html,
                                    quoteDepth = quoteDepth + 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal sealed interface TopicContentBlock {
    data class Paragraph(
        val html: String,
    ) : TopicContentBlock

    data class Quote(
        val author: String?,
        val html: String,
    ) : TopicContentBlock
}

internal fun parseTopicBlocks(html: String): List<TopicContentBlock> {
    val body = Jsoup.parseBodyFragment(html).body()
    val blocks = mutableListOf<TopicContentBlock>()
    val paragraphBuffer = StringBuilder()

    fun flushParagraph() {
        val htmlFragment = paragraphBuffer.toString().trim()
        if (htmlFragment.isNotBlank()) {
            blocks += TopicContentBlock.Paragraph(htmlFragment)
        }
        paragraphBuffer.clear()
    }

    body.childNodes().forEach { node ->
        when (val block = parseBlock(node)) {
            null -> flushParagraph()
            is TopicContentBlock.Quote -> {
                flushParagraph()
                blocks += block
            }
            is TopicContentBlock.Paragraph -> paragraphBuffer.append(block.html)
        }
    }

    flushParagraph()
    return blocks
}

private fun parseBlocks(html: String): List<TopicContentBlock> = parseTopicBlocks(html)

private fun parseBlock(node: Node): TopicContentBlock? {
    val element = node as? Element
    return when {
        node is TextNode -> node.text()
            .takeIf { it.isNotBlank() }
            ?.let(TopicContentBlock::Paragraph)
        element == null -> null
        element.tagName() == "div" && element.selectFirst("table.citation") != null -> parseQuoteBlock(element)
        element.tagName() == "div" && element.attr("style").contains("clear: both") -> null
        element.tagName() == "br" -> null
        else -> TopicContentBlock.Paragraph(element.outerHtml().trim())
    }
}

private fun parseQuoteBlock(element: Element): TopicContentBlock? {
    val table = element.selectFirst("table.citation")
    val cell = table?.selectFirst("td")?.clone()
    return if (table == null || cell == null) {
        null
    } else {
        val author = table
            .selectFirst("b.s1 a.Topic")
            ?.text()
            ?.substringBefore(" a écrit")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        cell.select("b.s1").remove()
        while (cell.children().firstOrNull()?.tagName() == "br") {
            cell.children().firstOrNull()?.remove()
        }
        TopicContentBlock.Quote(
            author = author,
            html = cell.html().trim(),
        )
    }
}

internal fun renderInlineTopicHtml(
    html: String,
    linkStyles: TextLinkStyles,
): AnnotatedString = buildAnnotatedString {
    appendInlineHtml(html, linkStyles)
}

private fun AnnotatedString.Builder.appendInlineHtml(
    html: String,
    linkStyles: TextLinkStyles,
) {
    val body = Jsoup.parseBodyFragment(html).body()
    appendNodes(body.childNodes(), linkStyles)
}

private fun AnnotatedString.Builder.appendNodes(
    nodes: List<Node>,
    linkStyles: TextLinkStyles,
) {
    nodes.forEach { node ->
        when (node) {
            is TextNode -> appendNormalizedText(node.text())
            is Element -> appendElement(node, linkStyles)
        }
    }
}

private fun AnnotatedString.Builder.appendElement(
    element: Element,
    linkStyles: TextLinkStyles,
) {
    when (element.tagName()) {
        "br" -> append('\n')
        "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendNodes(element.childNodes(), linkStyles)
        }

        "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendNodes(element.childNodes(), linkStyles)
        }

        "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendNodes(element.childNodes(), linkStyles)
        }

        "s", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendNodes(element.childNodes(), linkStyles)
        }

        "a" -> {
            val href = sanitizeLinkHref(element.attr("href"))
            if (href == null) {
                appendNodes(element.childNodes(), linkStyles)
            } else {
                withLink(LinkAnnotation.Url(href, linkStyles)) {
                    appendNodes(element.childNodes(), linkStyles)
                }
            }
        }

        "img" -> {
            val text = element.attr("alt")
                .ifBlank { element.attr("title") }
                .ifBlank { "[image]" }
            appendNormalizedText(text)
        }

        else -> appendNodes(element.childNodes(), linkStyles)
    }
}

private fun AnnotatedString.Builder.appendNormalizedText(text: String) {
    val normalized = text
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) {
        return
    }
    val hasLeadingSpace = normalized.startsWith(' ')
    val hasTrailingSpace = normalized.endsWith(' ')
    val content = normalized.trim()
    val current = toString()
    val shouldPrefixSpace = hasLeadingSpace && current.isNotEmpty() && current.last() !in listOf(' ', '\n')
    if (shouldPrefixSpace) {
        append(' ')
    }
    append(content)
    if (hasTrailingSpace) {
        append(' ')
    }
}

private fun sanitizeLinkHref(rawHref: String): String? =
    rawHref.trim()
        .takeIf(String::isNotBlank)
        ?.let { href ->
            when {
                href.startsWith("/") -> "https://forum.hardware.fr$href"
                href.startsWith("http://", ignoreCase = true) -> href
                href.startsWith("https://", ignoreCase = true) -> href
                else -> null
            }
        }
