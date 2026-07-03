package fr.forumhfr.redface2.core.model

/**
 * Vague 4 (#604) lot 2 — one-line plain-text excerpt of a post, for the compact quote cards
 * (author + excerpt captured AT SELECTION TIME, cadrage Codex : the heavy parsing serves the
 * exact materialisation, never this 1-line rendering).
 *
 * Conservative strip: the excerpt is the author's OWN prose — nested [PostBlock.Quote]s and
 * [PostBlock.Spoiler]s are excluded, images are dropped, formatting is flattened to its text,
 * smileys keep their textual code (`:jap:` / `[:name]`), whitespace is collapsed. Truncation
 * prefers a word boundary and appends a single `…`. A post made only of excluded blocks yields
 * `""` — the caller decides on a placeholder.
 */
fun postContentExcerpt(content: PostContent, maxChars: Int = DEFAULT_EXCERPT_MAX_CHARS): String {
    val flat = buildString {
        content.blocks.forEach { block ->
            val text = when (block) {
                is PostBlock.Paragraph -> flattenInlines(block.inlines)
                is PostBlock.Fixed -> block.text
                is PostBlock.CodeBlock -> block.text
                // The author's own words only — quoted material and hidden spoilers stay out.
                is PostBlock.Quote, is PostBlock.Spoiler, is PostBlock.Image -> ""
            }
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(text)
            }
        }
    }
    val collapsed = flat.replace(WHITESPACE_RUN, " ").trim()
    if (collapsed.length <= maxChars) return collapsed
    val cut = collapsed.take(maxChars)
    val wordBoundary = cut.lastIndexOf(' ')
    val kept = if (wordBoundary > 0) cut.take(wordBoundary) else cut
    return kept.trimEnd() + "…"
}

private fun flattenInlines(inlines: List<PostInline>): String = buildString {
    inlines.forEach { inline ->
        when (inline) {
            is PostInline.Text -> append(inline.value)
            PostInline.LineBreak -> append(' ')
            is PostInline.Strong -> append(flattenInlines(inline.children))
            is PostInline.Emphasis -> append(flattenInlines(inline.children))
            is PostInline.Underline -> append(flattenInlines(inline.children))
            is PostInline.Strike -> append(flattenInlines(inline.children))
            is PostInline.Color -> append(flattenInlines(inline.children))
            is PostInline.Link -> append(flattenInlines(inline.children))
            is PostInline.InlineImage -> Unit
            is PostInline.Smiley -> {
                if (isNotEmpty() && !endsWith(' ')) append(' ')
                append(inline.kind.textualCode())
            }
        }
    }
}

private fun SmileyKind.textualCode(): String = when (this) {
    is SmileyKind.Builtin -> code
    is SmileyKind.Perso -> "[:$name]"
}

private val WHITESPACE_RUN = Regex("\\s+")

private const val DEFAULT_EXCERPT_MAX_CHARS = 80
