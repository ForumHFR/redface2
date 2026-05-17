package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline

/**
 * Best-effort BBCode → [PostContent] parser for the Phase 2B editor preview.
 *
 * Two design constraints govern this implementation:
 *
 * 1. **User input is never trusted.** The parser must never crash, loop forever, or
 *    throw on malformed BBCode. Unknown tags, missing closes and inverted nesting
 *    degrade to plain text rather than blowing up the preview.
 * 2. **Subset coverage.** Phase 2B covers the inlines and blocks the toolbar can
 *    insert plus the constructs HFR pre-fills (notably `[quotemsg=…]`). Anything
 *    outside the subset is preserved as raw text so the editor stays usable while
 *    follow-up issues (#11 smileys/images, color picker UX) catch up.
 *
 * The parser intentionally lives in `:core:parser` per ADR-011 and the contract
 * documented in `docs/specs/architecture.md` (`fun parsePostContentFromBbcode(...)`).
 */
class BbcodeContentParser {

    fun parse(bbcode: String): PostContent {
        val tokens = if (bbcode.isEmpty()) emptyList() else Tokenizer(bbcode).tokenize()
        val blocks = parseBlocks(tokens, 0, tokens.size).blocks
        return if (blocks.isEmpty()) EMPTY_AST else PostContent(blocks = blocks)
    }

    private fun parseBlocks(tokens: List<Token>, from: Int, to: Int): BlockSlice {
        val out = mutableListOf<PostBlock>()
        val paragraph = mutableListOf<PostInline>()
        var i = from

        fun flushParagraph() {
            val cleaned = collapseInlines(paragraph)
            if (cleaned.isNotEmpty()) {
                out += PostBlock.Paragraph(cleaned)
            }
            paragraph.clear()
        }

        while (i < to) {
            i = when (val token = tokens[i]) {
                is Token.Text -> {
                    appendTextWithLineBreaks(token.value, paragraph) { flushParagraph() }
                    i + 1
                }

                is Token.Open -> handleOpenToken(
                    tokens = tokens,
                    i = i,
                    to = to,
                    token = token,
                    out = out,
                    paragraph = paragraph,
                    flushParagraph = ::flushParagraph,
                )

                is Token.Close -> {
                    // Stray close — keep the raw text so the user sees what they wrote.
                    paragraph += PostInline.Text(token.raw)
                    i + 1
                }
            }
        }

        flushParagraph()
        return BlockSlice(out, i)
    }

    @Suppress("LongParameterList") // dispatcher needs all the buffers and the flush hook
    private fun handleOpenToken(
        tokens: List<Token>,
        i: Int,
        to: Int,
        token: Token.Open,
        out: MutableList<PostBlock>,
        paragraph: MutableList<PostInline>,
        flushParagraph: () -> Unit,
    ): Int {
        val tagName = token.name.lowercase()
        return when (tagName) {
            "quote", "quotemsg", "spoiler" -> {
                flushParagraph()
                handleNestableBlock(tokens, i, to, token, tagName, out)
            }

            "fixed", "code", "cpp" -> {
                flushParagraph()
                handleRawTextBlock(tokens, i, to, token, tagName, out)
            }

            "img" -> handleImageBlock(tokens, i, to, tagName, out, flushParagraph)

            else -> handleInlineOpen(tokens, i, to, token, paragraph)
        }
    }

    @Suppress("LongParameterList") // intentionally explicit — context object would add noise
    private fun handleNestableBlock(
        tokens: List<Token>,
        i: Int,
        to: Int,
        token: Token.Open,
        tagName: String,
        out: MutableList<PostBlock>,
    ): Int {
        val close = findMatchingClose(tokens, i, to, tagName)
        val inner = if (close > i) parseBlocks(tokens, i + 1, close).blocks else emptyList()
        out += buildNestableBlock(tagName, token, inner)
        return if (close > i) close + 1 else i + 1
    }

    private fun buildNestableBlock(
        tagName: String,
        token: Token.Open,
        inner: List<PostBlock>,
    ): PostBlock {
        val nested = PostContent(blocks = inner)
        return when (tagName) {
            "quote" -> PostBlock.Quote(author = null, numreponse = null, page = null, content = nested)
            "quotemsg" -> {
                val params = parseQuoteMsgParams(token.params)
                PostBlock.Quote(author = null, numreponse = params.numreponse, page = null, content = nested)
            }
            "spoiler" -> PostBlock.Spoiler(label = null, content = nested)
            else -> error("Unsupported nestable tag $tagName")
        }
    }

    @Suppress("LongParameterList") // intentionally explicit — context object would add noise
    private fun handleRawTextBlock(
        tokens: List<Token>,
        i: Int,
        to: Int,
        token: Token.Open,
        tagName: String,
        out: MutableList<PostBlock>,
    ): Int {
        val close = findMatchingClose(tokens, i, to, tagName)
        val text = if (close > i) flattenRawText(tokens, i + 1, close) else ""
        out += when (tagName) {
            "fixed" -> PostBlock.Fixed(text = text)
            "code" -> PostBlock.CodeBlock(text = text, language = token.params.takeIf { it.isNotBlank() })
            "cpp" -> PostBlock.CodeBlock(text = text, language = "cpp")
            else -> error("Unsupported raw-text tag $tagName")
        }
        return if (close > i) close + 1 else i + 1
    }

    @Suppress("LongParameterList") // intentionally explicit — context object would add noise
    private fun handleImageBlock(
        tokens: List<Token>,
        i: Int,
        to: Int,
        tagName: String,
        out: MutableList<PostBlock>,
        flushParagraph: () -> Unit,
    ): Int {
        val close = findMatchingClose(tokens, i, to, tagName)
        val url = if (close > i) flattenRawText(tokens, i + 1, close).trim() else ""
        if (url.isNotEmpty()) {
            // HFR inserts images via the toolbar as block-level — render the same way so
            // the preview matches what users see on the web.
            flushParagraph()
            out += PostBlock.Image(url = url, description = null)
        }
        return if (close > i) close + 1 else i + 1
    }

    private fun handleInlineOpen(
        tokens: List<Token>,
        i: Int,
        to: Int,
        token: Token.Open,
        paragraph: MutableList<PostInline>,
    ): Int {
        val inline = parseInline(tokens, i, to)
        return if (inline.consumed == 0) {
            paragraph += PostInline.Text(token.raw)
            i + 1
        } else {
            paragraph += inline.node
            i + inline.consumed
        }
    }

    private fun parseInline(tokens: List<Token>, from: Int, to: Int): InlineSlice {
        val open = tokens[from] as Token.Open
        val tag = open.name.lowercase()
        return when (tag) {
            "b" -> wrapInline(tokens, from, to, tag) { PostInline.Strong(it) }
            "i" -> wrapInline(tokens, from, to, tag) { PostInline.Emphasis(it) }
            "u" -> wrapInline(tokens, from, to, tag) { PostInline.Underline(it) }
            "strike", "s" -> wrapInline(tokens, from, to, tag) { PostInline.Strike(it) }
            "url" -> parseInlineUrl(tokens, from, to, open)
            "email" -> parseInlineEmail(tokens, from, to, open)
            else -> {
                if (tag.startsWith("#") && tag.length == 7 && tag.drop(1).all { it.isHexDigit() }) {
                    parseInlineColor(tokens, from, to, tag)
                } else {
                    InlineSlice(PostInline.Text(open.raw), 0)
                }
            }
        }
    }

    private fun wrapInline(
        tokens: List<Token>,
        from: Int,
        to: Int,
        tagName: String,
        wrap: (List<PostInline>) -> PostInline,
    ): InlineSlice {
        val close = findMatchingClose(tokens, from, to, tagName)
        if (close <= from) {
            // Unclosed inline → keep raw open tag as text, but keep walking
            return InlineSlice(PostInline.Text((tokens[from] as Token.Open).raw), 1)
        }
        val children = parseInlinesOnly(tokens, from + 1, close)
        return InlineSlice(wrap(children), close - from + 1)
    }

    private fun parseInlineUrl(
        tokens: List<Token>,
        from: Int,
        to: Int,
        open: Token.Open,
    ): InlineSlice {
        val close = findMatchingClose(tokens, from, to, "url")
        if (close <= from) {
            return InlineSlice(PostInline.Text(open.raw), 1)
        }
        val children = parseInlinesOnly(tokens, from + 1, close)
        val url = open.params.takeIf { it.isNotBlank() } ?: flattenInlineText(children)
        return InlineSlice(
            PostInline.Link(url = url, children = children),
            close - from + 1,
        )
    }

    private fun parseInlineEmail(
        tokens: List<Token>,
        from: Int,
        to: Int,
        open: Token.Open,
    ): InlineSlice {
        val close = findMatchingClose(tokens, from, to, "email")
        if (close <= from) {
            return InlineSlice(PostInline.Text(open.raw), 1)
        }
        val children = parseInlinesOnly(tokens, from + 1, close)
        val address = open.params.takeIf { it.isNotBlank() } ?: flattenInlineText(children)
        return InlineSlice(
            PostInline.Link(url = "mailto:$address", children = children),
            close - from + 1,
        )
    }

    private fun parseInlineColor(
        tokens: List<Token>,
        from: Int,
        to: Int,
        tag: String,
    ): InlineSlice {
        val close = findMatchingClose(tokens, from, to, tag)
        if (close <= from) {
            return InlineSlice(PostInline.Text((tokens[from] as Token.Open).raw), 1)
        }
        val children = parseInlinesOnly(tokens, from + 1, close)
        return InlineSlice(
            PostInline.Color(colorHex = tag.uppercase(), children = children),
            close - from + 1,
        )
    }

    private fun parseInlinesOnly(tokens: List<Token>, from: Int, to: Int): List<PostInline> {
        val out = mutableListOf<PostInline>()
        var i = from
        while (i < to) {
            val token = tokens[i]
            when (token) {
                is Token.Text -> {
                    appendTextWithLineBreaks(token.value, out) { /* preserve line breaks in-place */ }
                    i += 1
                }

                is Token.Open -> {
                    val inline = parseInline(tokens, i, to)
                    if (inline.consumed == 0) {
                        out += PostInline.Text(token.raw)
                        i += 1
                    } else {
                        out += inline.node
                        i += inline.consumed
                    }
                }

                is Token.Close -> {
                    out += PostInline.Text(token.raw)
                    i += 1
                }
            }
        }
        return collapseInlines(out)
    }

    private fun findMatchingClose(
        tokens: List<Token>,
        openIndex: Int,
        to: Int,
        tagName: String,
    ): Int {
        var depth = 1
        var i = openIndex + 1
        while (i < to) {
            val token = tokens[i]
            if (token is Token.Open && token.name.equals(tagName, ignoreCase = true)) {
                depth += 1
            } else if (token is Token.Close && token.name.equals(tagName, ignoreCase = true)) {
                depth -= 1
                if (depth == 0) return i
            }
            i += 1
        }
        return -1
    }

    private fun flattenRawText(tokens: List<Token>, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) {
            sb.append(
                when (val token = tokens[i]) {
                    is Token.Text -> token.value
                    is Token.Open -> token.raw
                    is Token.Close -> token.raw
                },
            )
        }
        return sb.toString()
    }

    private fun flattenInlineText(inlines: List<PostInline>): String = buildString {
        inlines.forEach { node ->
            when (node) {
                is PostInline.Text -> append(node.value)
                is PostInline.LineBreak -> append('\n')
                is PostInline.Strong -> append(flattenInlineText(node.children))
                is PostInline.Emphasis -> append(flattenInlineText(node.children))
                is PostInline.Underline -> append(flattenInlineText(node.children))
                is PostInline.Strike -> append(flattenInlineText(node.children))
                is PostInline.Color -> append(flattenInlineText(node.children))
                is PostInline.Link -> append(flattenInlineText(node.children))
                is PostInline.InlineImage -> Unit
                is PostInline.Smiley -> Unit
            }
        }
    }

    private fun appendTextWithLineBreaks(
        text: String,
        out: MutableList<PostInline>,
        onParagraphBreak: () -> Unit,
    ) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) return

        val segments = normalized.split("\n\n")
        segments.forEachIndexed { segmentIndex, segment ->
            val lines = segment.split('\n')
            lines.forEachIndexed { lineIndex, line ->
                if (line.isNotEmpty()) {
                    out += PostInline.Text(line)
                }
                if (lineIndex < lines.lastIndex) {
                    out += PostInline.LineBreak
                }
            }
            if (segmentIndex < segments.lastIndex) {
                onParagraphBreak()
            }
        }
    }

    private fun collapseInlines(inlines: List<PostInline>): List<PostInline> {
        if (inlines.isEmpty()) return inlines
        val out = mutableListOf<PostInline>()
        // Strip leading/trailing line breaks inside a paragraph for cosmetics, mirroring
        // PostContentParser's behaviour on HTML input.
        var start = 0
        var end = inlines.size
        while (start < end && inlines[start] is PostInline.LineBreak) start += 1
        while (end > start && inlines[end - 1] is PostInline.LineBreak) end -= 1
        for (i in start until end) {
            val current = inlines[i]
            val previous = out.lastOrNull()
            if (current is PostInline.Text && previous is PostInline.Text) {
                out[out.lastIndex] = PostInline.Text(previous.value + current.value)
            } else {
                out += current
            }
        }
        return out
    }

    private data class BlockSlice(val blocks: List<PostBlock>, val consumed: Int)

    private data class InlineSlice(val node: PostInline, val consumed: Int)

    private companion object {
        val EMPTY_AST: PostContent = PostContent(blocks = emptyList())
    }
}

internal data class QuoteMsgParams(
    val numreponse: Int?,
    val opaqueSecond: String?,
    val userId: Int?,
)

internal fun parseQuoteMsgParams(raw: String): QuoteMsgParams {
    if (raw.isBlank()) return QuoteMsgParams(null, null, null)
    val parts = raw.split(',').map { it.trim() }
    val numreponse = parts.getOrNull(0)?.toIntOrNull()
    val opaque = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    val userId = parts.getOrNull(2)?.toIntOrNull()
    return QuoteMsgParams(numreponse = numreponse, opaqueSecond = opaque, userId = userId)
}

internal sealed interface Token {
    data class Text(val value: String) : Token

    /** `[name]` or `[name=params]`. `raw` keeps the original substring for fallback rendering. */
    data class Open(val name: String, val params: String, val raw: String) : Token

    /** `[/name]`. `raw` keeps the original substring. */
    data class Close(val name: String, val raw: String) : Token
}

internal class Tokenizer(private val source: String) {

    private var index: Int = 0
    private val tokens: MutableList<Token> = mutableListOf()
    private val buffer: StringBuilder = StringBuilder()

    fun tokenize(): List<Token> {
        while (index < source.length) {
            val char = source[index]
            if (char == '[') {
                val nextClose = source.indexOf(']', startIndex = index + 1)
                if (nextClose == -1) {
                    // No matching ']' anywhere — flush remainder as text.
                    buffer.append(source.substring(index))
                    index = source.length
                    break
                }
                val rawTag = source.substring(index, nextClose + 1)
                val parsed = parseTag(rawTag)
                if (parsed != null) {
                    flushBuffer()
                    tokens += parsed
                    index = nextClose + 1
                } else {
                    buffer.append(char)
                    index += 1
                }
            } else {
                buffer.append(char)
                index += 1
            }
        }
        flushBuffer()
        return tokens
    }

    private fun parseTag(raw: String): Token? {
        // raw is "[…]". Strip braces.
        val inner = raw.substring(1, raw.length - 1)
        if (inner.isEmpty() || inner.any { it == '\n' || it == '\r' }) return null

        return if (inner.startsWith('/')) {
            val name = inner.substring(1).trim()
            if (isValidTagName(name)) Token.Close(name = name, raw = raw) else null
        } else {
            buildOpenToken(inner, raw)
        }
    }

    private fun buildOpenToken(inner: String, raw: String): Token.Open? {
        val equalsIndex = inner.indexOf('=')
        val name = if (equalsIndex == -1) inner.trim() else inner.substring(0, equalsIndex).trim()
        val params = if (equalsIndex == -1) "" else inner.substring(equalsIndex + 1)
        return if (isValidTagName(name)) Token.Open(name = name, params = params, raw = raw) else null
    }

    private fun isValidTagName(name: String): Boolean = when {
        name.isEmpty() -> false
        // Color shortcut [#RRGGBB] — six hex digits.
        name.startsWith('#') -> name.length == 7 && name.drop(1).all { it.isHexDigit() }
        else -> name.all { it.isLetterOrDigit() || it == '*' || it == '-' || it == '_' }
    }

    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        tokens += Token.Text(buffer.toString())
        buffer.clear()
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
