package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class PostContentParser {
    fun parse(contentElement: Element?): ParsedPostContent {
        if (contentElement == null) {
            return ParsedPostContent(
                quotedAuthors = emptyList(),
                ast = deletedFallbackAst(),
            )
        }

        val sanitized = contentElement.clone().apply {
            select("${HfrSelectors.POST_EDITED}, ${HfrSelectors.POST_SIGNATURE}").remove()
        }

        val parsedBlocks = parseBlocks(sanitized.childNodes())
        val ast = if (parsedBlocks.isEmpty()) deletedFallbackAst() else PostContent(blocks = parsedBlocks)
        val quotedAuthors = collectQuotedAuthors(ast)

        return ParsedPostContent(
            quotedAuthors = quotedAuthors,
            ast = ast,
        )
    }

    private fun deletedFallbackAst(): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(DELETED_PLACEHOLDER)))),
    )

    private fun parseBlocks(nodes: List<Node>): List<PostBlock> {
        val blocks = mutableListOf<PostBlock>()
        val paragraph = mutableListOf<PostInline>()

        fun flushParagraph() {
            val cleaned = collapseInlines(paragraph)
            if (cleaned.any { it.isNonBlank() }) {
                blocks += PostBlock.Paragraph(cleaned)
            }
            paragraph.clear()
        }

        nodes.forEach { node ->
            when (classifyNode(node)) {
                NodeKind.IGNORE -> Unit
                NodeKind.LINE_BREAK -> flushParagraph()

                NodeKind.QUOTE -> {
                    flushParagraph()
                    parseQuote(node as Element)?.let(blocks::add)
                }

                NodeKind.SPOILER -> {
                    flushParagraph()
                    parseSpoiler(node as Element)?.let(blocks::add)
                }

                NodeKind.IMAGE_BLOCK -> {
                    flushParagraph()
                    parseImageBlock(node as Element)?.let(blocks::add)
                }

                NodeKind.FIXED_BLOCK -> {
                    flushParagraph()
                    parseFixedBlock(node as Element)?.let(blocks::add)
                }

                NodeKind.CODE_BLOCK -> {
                    flushParagraph()
                    parseCodeBlock(node as Element)?.let(blocks::add)
                }

                NodeKind.INLINE -> paragraph += parseInline(node)

                NodeKind.PARAGRAPH_CONTAINER -> {
                    flushParagraph()
                    blocks += parseBlocks((node as Element).childNodes())
                }
            }
        }

        flushParagraph()
        return blocks
    }

    private fun classifyNode(node: Node): NodeKind {
        val element = (node as? Element) ?: return if (node is TextNode) NodeKind.INLINE else NodeKind.IGNORE
        return when (element.tagName()) {
            "br" -> NodeKind.LINE_BREAK
            "div" -> classifyDiv(element)
            "p" -> NodeKind.PARAGRAPH_CONTAINER
            "img" -> if (isStandaloneImage(element)) NodeKind.IMAGE_BLOCK else NodeKind.INLINE
            // HFR emits [fixed] / [code] as <table> children direct of <div id="paraN">, with no
            // wrapping <div class="container"> like quotes have. The classifyDiv path therefore
            // never sees them; recognising them at the top level here is what keeps the body
            // out of the surrounding paragraph (see classifyTable for the per-class dispatch).
            "table" -> classifyTable(element)
            else -> NodeKind.INLINE
        }
    }

    private fun classifyDiv(element: Element): NodeKind = when {
        element.selectFirst("table.spoiler") != null -> NodeKind.SPOILER
        // HFR renders [quotemsg=...] as <table class="citation"> (with author anchor) and
        // [quote] (anonymous) as <table class="quote"> — both map to the same Quote block.
        // Logged-in users whose profile uses the classic citation style get <table
        // class="oldcitation"> instead of "citation" — same structure, different class.
        element.selectFirst("table.citation, table.oldcitation, table.quote") != null -> NodeKind.QUOTE
        // Defensive wrapper support: every fixture captured so far emits [fixed] / [code] as a
        // <table> child direct of <div id="paraN"> (handled by classifyTable above), but quotes
        // use <div class="container"> wrappers. Synthetic tests pin this fallback so a future HFR
        // skin wrapping monospace blocks would still keep them out of the surrounding paragraph.
        element.selectFirst("table.fixed") != null -> NodeKind.FIXED_BLOCK
        element.selectFirst("table.code") != null -> NodeKind.CODE_BLOCK
        element.attr("style").contains("clear: both") -> NodeKind.IGNORE
        else -> NodeKind.PARAGRAPH_CONTAINER
    }

    private fun classifyTable(element: Element): NodeKind = when {
        element.hasClass("spoiler") -> NodeKind.SPOILER
        element.hasClass("citation") || element.hasClass("oldcitation") || element.hasClass("quote") -> NodeKind.QUOTE
        element.hasClass("fixed") -> NodeKind.FIXED_BLOCK
        element.hasClass("code") -> NodeKind.CODE_BLOCK
        else -> NodeKind.INLINE
    }

    private fun isStandaloneImage(element: Element): Boolean {
        val parent = element.parent()
        return parseSmiley(element) == null && parent != null &&
            parent.childNodes().none { sibling -> sibling !== element && isContentSibling(sibling) }
    }

    private fun isContentSibling(node: Node): Boolean = when (node) {
        is TextNode -> node.text().isNotBlank()
        is Element -> true
        else -> false
    }

    private fun parseQuote(element: Element): PostBlock.Quote? {
        val table = resolveTable(element, "table.citation, table.oldcitation, table.quote")
        val cell = table?.selectFirst("td")?.clone()
        if (table == null || cell == null) return null

        val authorAnchor = table.selectFirst(HfrSelectors.POST_CITATION_AUTHOR)
        // <table class="quote"> (anonymous [quote]) has no a.Topic, so author/page/numreponse
        // stay null. The "Citation :" <b class="s1"> label is removed below for both shapes.
        val author = authorAnchor
            ?.text()
            ?.substringBefore(" a écrit")
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        // The citation header anchor links to the cited post:
        // .../sujet_<topicPost>_<page>.htm#t<numreponse>
        // Both page and numreponse are extractable from rendered HTML when present.
        val match = authorAnchor?.attr("href")?.let(CITATION_HREF_REGEX::find)
        val page = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val numreponse = match?.groupValues?.getOrNull(2)?.toIntOrNull()

        cell.children()
            .firstOrNull { it.tagName() == "b" && it.hasClass("s1") }
            ?.remove()
        while (cell.children().firstOrNull()?.tagName() == "br") {
            cell.children().firstOrNull()?.remove()
        }

        return PostBlock.Quote(
            author = author,
            numreponse = numreponse,
            page = page,
            content = PostContent(parseBlocks(cell.childNodes())),
        )
    }

    private fun parseSpoiler(element: Element): PostBlock.Spoiler? {
        val table = resolveTable(element, "table.spoiler")
        val cell = table?.selectFirst("td")?.clone()
        if (table == null || cell == null) return null

        // Header is <b class="s1Topic">Spoiler :</b> — keep the label without the trailing colon
        // so the renderer can format it consistently.
        val labelElement = cell.selectFirst("b.s1Topic")
        val label = labelElement
            ?.text()
            ?.removeSuffix(":")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        labelElement?.remove()
        while (cell.children().firstOrNull()?.tagName() == "br") {
            cell.children().firstOrNull()?.remove()
        }

        // The hidden body is wrapped in <div class="Topic masque"> — unwrap it before recursing
        // so paragraphs/inlines surface at the top level of the Spoiler content.
        val masque = cell.selectFirst("div.Topic.masque")
        val source = masque ?: cell
        return PostBlock.Spoiler(
            label = label,
            content = PostContent(parseBlocks(source.childNodes())),
        )
    }

    private fun parseImageBlock(element: Element): PostBlock.Image? {
        val url = sanitizeImageHref(element.attr("src")) ?: return null
        val description = element.attr("alt").ifBlank { element.attr("title") }.takeIf(String::isNotBlank)
        return PostBlock.Image(url = url, description = description)
    }

    private fun parseFixedBlock(element: Element): PostBlock.Fixed? {
        val cell = resolveTable(element, "table.fixed")?.selectFirst("td")?.clone() ?: return null
        // Defensive: HFR currently renders [fixed] without the "Code :" label, but if a future
        // skin re-uses <b class="s1"> as a header here, drop it the same way parseCodeBlock does.
        cell.select("b.s1").remove()
        val text = extractCellText(cell)
        return text.takeIf(String::isNotBlank)?.let { PostBlock.Fixed(it) }
    }

    private fun parseCodeBlock(element: Element): PostBlock.CodeBlock? {
        val cell = resolveTable(element, "table.code")?.selectFirst("td")?.clone() ?: return null
        cell.select("b.s1").remove()

        // [code lang] renders the body inside <pre class="<lang>"> (e.g. <pre class="cpp">). The
        // language hint sits on that element. Plain [code] uses <ol class="olcode"> directly under
        // the <td> with no <pre>, hence language stays null.
        val pre = cell.selectFirst("pre")
        val language = pre?.classNames()?.firstOrNull()?.takeIf(String::isNotBlank)

        // Both shapes ultimately wrap each source line in an <li>:
        //   - plain:    <ol class="olcode"><li>line</li>...</ol>
        //   - colored:  <pre class="cpp"><ol><li class="li1"><div class="de1">line</div></li>...</ol></pre>
        // Joining li.wholeText() with \n flattens the syntax-highlight spans (kw3/me1/st0/de1)
        // into raw source text — Phase 1 contract per issue #79; coloration is Phase 2 work.
        val items = cell.select("li")
        val text = if (items.isNotEmpty()) {
            items.joinToString("\n") { extractCellText(it) }
        } else {
            extractCellText(cell)
        }
        return text.takeIf(String::isNotBlank)
            ?.let { PostBlock.CodeBlock(text = it, language = language) }
    }

    /**
     * Extracts the visible source text from a `[fixed]` / `[code]` table cell. The clone is
     * mutated in place:
     *   - each `<br>` becomes a literal newline so single-line authoring survives ;
     *   - each `<p>` is suffixed with a newline so a multi-paragraph `[fixed]` body keeps its
     *     paragraph separations — `wholeText()` does not insert whitespace between sibling block
     *     elements, so `<p>A</p><p>B</p>` would otherwise collapse into `AB` ;
     * then `wholeText()` walks the DOM preserving whitespace so indentation inside `<pre>` stays
     * intact (unlike `text()`, which collapses runs of whitespace to a single space). Only empty
     * boundary lines injected by the HTML structure are trimmed; leading spaces on real content
     * lines are part of the `[fixed]` / `[code]` contract and must survive.
     */
    private fun extractCellText(element: Element): String {
        element.select("br").forEach { it.replaceWith(TextNode("\n")) }
        element.select("p").forEach { it.appendChild(TextNode("\n")) }
        return element.wholeText().trimStructuralBlankLines()
    }

    private fun String.trimStructuralBlankLines(): String {
        val lines = replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val firstContentLine = lines.indexOfFirst { it.isNotBlank() }
        if (firstContentLine == -1) return ""
        val lastContentLine = lines.indexOfLast { it.isNotBlank() }
        return lines.subList(firstContentLine, lastContentLine + 1).joinToString("\n")
    }

    /**
     * HFR can emit the same semantic block in two shapes:
     *   - `<div class="container"><table class="<kind>">...</table></div>` (typical for quotes)
     *   - `<table class="<kind>">...</table>` direct child of `<div id="paraN">` (typical for
     *     `[fixed]` / `[code]`).
     * The classifier dispatches both shapes to the same parse method; this helper resolves the
     * table element regardless of whether [element] is the wrapping div or the table itself.
     */
    private fun resolveTable(element: Element, selector: String): Element? =
        if (element.tagName() == "table") element else element.selectFirst(selector)

    private fun parseInline(node: Node): List<PostInline> = when (node) {
        is TextNode -> listOfNotNull(normalizeText(node.text())?.let(PostInline::Text))
        is Element -> parseInlineElement(node)
        else -> emptyList()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseInlineElement(element: Element): List<PostInline> = when (element.tagName()) {
        // Two granularities of vertical rhythm coexist:
        //   - top-level <br> flushes the current paragraph (handled in parseBlocks).
        //   - <br> nested inside any inline parent (<strong>, <a>, <span>, <font>, …) keeps
        //     the author's intra-paragraph break as an explicit LineBreak so the renderer
        //     does not silently merge the two text runs.
        "br" -> listOf(PostInline.LineBreak)
        "b", "strong" -> listOf(PostInline.Strong(parseInlineChildren(element)))
        "i", "em" -> listOf(PostInline.Emphasis(parseInlineChildren(element)))
        "u" -> listOf(PostInline.Underline(parseInlineChildren(element)))
        "s", "strike" -> listOf(PostInline.Strike(parseInlineChildren(element)))
        "a" -> parseAnchor(element)
        "img" -> parseImageInline(element)
        "font" -> parseFont(element)
        "span" -> parseSpan(element)
        else -> parseInlineChildren(element)
    }

    private fun parseInlineChildren(element: Element): List<PostInline> {
        val out = mutableListOf<PostInline>()
        element.childNodes().forEach { child -> out += parseInline(child) }
        return collapseInlines(out)
    }

    private fun parseAnchor(element: Element): List<PostInline> {
        val href = sanitizeLinkHref(element.attr("href"))
        val children = parseInlineChildren(element)
        return if (href == null) children else listOf(PostInline.Link(href, children))
    }

    private fun parseImageInline(element: Element): List<PostInline> {
        val smiley = parseSmiley(element)
        val src = sanitizeImageHref(element.attr("src"))
        val description = element.attr("alt").ifBlank { element.attr("title") }.takeIf(String::isNotBlank)
        return when {
            smiley != null -> listOf(smiley)
            src == null -> emptyList()
            else -> listOf(PostInline.InlineImage(url = src, description = description))
        }
    }

    private fun parseSmiley(element: Element): PostInline.Smiley? {
        if (element.tagName() != "img") return null
        // HFR keeps `title` canonical-cased (e.g. ":D") while `alt` can drift to lowercase
        // (`:d`) on legacy posts. Pick title first so :D and :d don't end up as two distinct
        // builtin tokens for the same emoticon.
        val title = element.attr("title").trim()
        val alt = element.attr("alt").trim()
        val token = title.ifBlank { alt }
        val imageUrl = sanitizeImageHref(element.attr("src"))
        return when {
            token.isEmpty() -> null
            PERSO_SMILEY_REGEX.matches(token) -> {
                // token = "[:name]" → strip "[:" prefix and trailing "]"
                val name = token.substring(2, token.length - 1)
                PostInline.Smiley(SmileyKind.Perso(name), imageUrl)
            }

            BUILTIN_SMILEY_REGEX.matches(token) -> {
                // The BBCode token is the canonical identity (`:)`, `;)`, `:jap:`, `:??:` …);
                // stripping the colons would collapse `:)` and `;)` to the same `)`.
                PostInline.Smiley(SmileyKind.Builtin(token), imageUrl)
            }

            else -> null
        }
    }

    private fun parseFont(element: Element): List<PostInline> {
        val color = element.attr("color").trim().takeIf(String::isNotEmpty)
        val normalized = normalizeColorHex(color)
        val children = parseInlineChildren(element)
        return if (normalized == null) children else listOf(PostInline.Color(normalized, children))
    }

    private fun parseSpan(element: Element): List<PostInline> {
        val children = parseInlineChildren(element)
        // HFR renders [u]...[/u] as <span class="u">…</span>. Without this branch the underline
        // semantics would be silently dropped (parseSpan only knew about color styles).
        if (element.hasClass("u")) {
            return listOf(PostInline.Underline(children))
        }
        val style = element.attr("style")
        val color = STYLE_COLOR_REGEX.find(style)?.groupValues?.getOrNull(1)?.trim()
        val normalized = normalizeColorHex(color)
        return if (normalized == null) children else listOf(PostInline.Color(normalized, children))
    }

    private fun normalizeColorHex(raw: String?): String? {
        if (raw == null) return null
        val hex = raw.trim().removePrefix("#")
        return when (hex.length) {
            6, 8 -> if (hex.matches(HEX_REGEX)) "#${hex.uppercase()}" else null
            3 -> {
                val expanded = hex.toCharArray().joinToString("") { "$it$it" }
                if (expanded.matches(HEX_REGEX)) "#${expanded.uppercase()}" else null
            }

            else -> null
        }
    }

    private fun normalizeText(text: String): String? {
        val normalized = text
            .replace(' ', ' ')
            .replace(WHITESPACE_REGEX, " ")
        return normalized.takeIf(String::isNotEmpty)
    }

    private fun collapseInlines(inlines: List<PostInline>): List<PostInline> {
        if (inlines.isEmpty()) return inlines
        val out = mutableListOf<PostInline>()
        inlines.forEach { current ->
            val previous = out.lastOrNull()
            if (previous is PostInline.Text && current is PostInline.Text) {
                out[out.lastIndex] = PostInline.Text(previous.value + current.value)
            } else {
                out += current
            }
        }
        return out
            .map(::normalizeEdge)
            .filterNot { it is PostInline.Text && it.value.isEmpty() }
    }

    private fun normalizeEdge(inline: PostInline): PostInline = when (inline) {
        is PostInline.Text -> PostInline.Text(inline.value.replace(WHITESPACE_REGEX, " "))
        else -> inline
    }

    private fun PostInline.isNonBlank(): Boolean = when (this) {
        is PostInline.Text -> value.isNotBlank()
        else -> true
    }

    private fun collectQuotedAuthors(content: PostContent): List<String> {
        val out = mutableListOf<String>()
        fun walk(blocks: List<PostBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is PostBlock.Quote -> {
                        block.author?.takeIf(String::isNotEmpty)?.let(out::add)
                        walk(block.content.blocks)
                    }

                    is PostBlock.Spoiler -> walk(block.content.blocks)
                    else -> Unit
                }
            }
        }
        walk(content.blocks)
        return out.distinct()
    }

    private enum class NodeKind {
        IGNORE,
        LINE_BREAK,
        QUOTE,
        SPOILER,
        IMAGE_BLOCK,
        FIXED_BLOCK,
        CODE_BLOCK,
        INLINE,
        PARAGRAPH_CONTAINER,
    }

    private companion object {
        const val DELETED_PLACEHOLDER = "[Message supprimé]"

        val WHITESPACE_REGEX = Regex("\\s+")
        val HEX_REGEX = Regex("[0-9a-fA-F]+")
        val STYLE_COLOR_REGEX = Regex("""color\s*:\s*(#?[0-9a-fA-F]{3,8})""")

        // HFR builtin smileys (~25 tokens) appear as alt="<token>" in the rendered HTML.
        // Two shapes coexist:
        //   - emoticons starting with `:` or `;` (`:)`, `;)`, `:D`, `:/`, `:o`)
        //   - named codes wrapped in colons (`:jap:`, `:lol:`, `:spamafote:`, `:??:`)
        // The character set covers the punctuation actually observed in the fixtures.
        val BUILTIN_SMILEY_REGEX = Regex("""^[:;][\w)(/?;\-']{1,15}:?$""")

        // HFR custom smileys are wrapped as alt="[:name]" — name may contain spaces and punctuation.
        val PERSO_SMILEY_REGEX = Regex("""^\[:[^]]+]$""")

        // Citation header href: https://forum.hardware.fr/hfr/.../sujet_<post>_<page>.htm#t<numreponse>
        // TODO(Phase 2): this only matches the static permalink form. In logged-in mode HFR
        // serves the dynamic `forum2.php?...&page=N...#t<numreponse>` form instead, which this
        // regex misses → Quote.page / Quote.numreponse stay null and scroll-to-cited-post is
        // inactive for authenticated users. Pinned by `logged-in oldcitation table …` test.
        val CITATION_HREF_REGEX = Regex("""sujet_\d+_(\d+)\.htm#t(\d+)""")
    }
}

internal fun sanitizeLinkHref(rawHref: String): String? =
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

internal fun sanitizeImageHref(rawSrc: String): String? =
    rawSrc.trim()
        .takeIf(String::isNotBlank)
        ?.let { src ->
            // Same whitelist as sanitizeLinkHref: only http(s) and absolute HFR paths reach the
            // renderer / Coil. data:, file:, content:, javascript:, fixture-relative paths are
            // rejected so a malicious or unexpected scheme cannot smuggle through `<img src>`.
            when {
                src.startsWith("/") -> "https://forum.hardware.fr$src"
                src.startsWith("http://", ignoreCase = true) -> src
                src.startsWith("https://", ignoreCase = true) -> src
                else -> null
            }
        }

data class ParsedPostContent(
    val quotedAuthors: List<String>,
    val ast: PostContent,
)
