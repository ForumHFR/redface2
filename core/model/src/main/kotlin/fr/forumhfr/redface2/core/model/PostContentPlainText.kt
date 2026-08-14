package fr.forumhfr.redface2.core.model

/**
 * Locale-neutral full-text projection of a post, intended for user-initiated copy actions.
 *
 * Non-empty blocks are separated by a blank line. Inline formatting is flattened, links retain
 * their visible text and add a distinct URL, preformatted blocks keep their internal line breaks,
 * and images are omitted. Quotes use `> ` line prefixes with an optional `— author` attribution;
 * spoilers use explicit `[spoiler]` / `[/spoiler]` delimiters. Spoiler labels are ignored because
 * HFR supplies the constant `Spoiler`, which carries no additional information. These neutral
 * markers keep the output readable without making `:core:model` depend on UI-localized labels.
 *
 * Unlike [postContentExcerpt], this projection includes quoted and spoiled content and never
 * collapses whitespace or truncates the result.
 */
fun postContentPlainText(content: PostContent): String = buildString {
    content.blocks.forEach { block ->
        val blockText = block.plainText()
        if (blockText.isNotEmpty()) {
            if (isNotEmpty()) append(BLOCK_SEPARATOR)
            append(blockText)
        }
    }
}

private fun PostBlock.plainText(): String = when (this) {
    is PostBlock.Paragraph -> inlines.plainText()
    is PostBlock.Quote -> plainTextQuote()
    is PostBlock.Spoiler -> plainTextSpoiler()
    is PostBlock.Image -> ""
    is PostBlock.Fixed -> text
    is PostBlock.CodeBlock -> text
}

private fun PostBlock.Quote.plainTextQuote(): String {
    val quoteBody = buildString {
        append(postContentPlainText(content))
        if (author != null) {
            if (isNotEmpty()) append('\n')
            append(AUTHOR_ATTRIBUTION_PREFIX)
            append(author)
        }
    }
    return if (quoteBody.isEmpty()) "" else quoteBody.prefixLines(QUOTE_LINE_PREFIX)
}

private fun PostBlock.Spoiler.plainTextSpoiler(): String = buildString {
    append(SPOILER_OPEN)

    val spoilerBody = postContentPlainText(content)
    if (spoilerBody.isNotEmpty()) {
        append('\n')
        append(spoilerBody)
    }
    append('\n')
    append(SPOILER_CLOSE)
}

private fun List<PostInline>.plainText(): String = buildString {
    this@plainText.forEach { inline ->
        when (inline) {
            is PostInline.Text -> append(inline.value)
            PostInline.LineBreak -> append('\n')
            is PostInline.Strong -> append(inline.children.plainText())
            is PostInline.Emphasis -> append(inline.children.plainText())
            is PostInline.Underline -> append(inline.children.plainText())
            is PostInline.Strike -> append(inline.children.plainText())
            is PostInline.Color -> append(inline.children.plainText())
            is PostInline.Link -> append(inline.plainTextLink())
            is PostInline.InlineImage -> Unit
            is PostInline.Smiley -> append(inline.kind.plainTextCode())
        }
    }
}

private fun PostInline.Link.plainTextLink(): String {
    val visibleText = children.plainText()
    return when {
        visibleText == url -> visibleText
        visibleText.isEmpty() -> url
        url.isEmpty() -> visibleText
        else -> "$visibleText ($url)"
    }
}

private fun SmileyKind.plainTextCode(): String = when (this) {
    is SmileyKind.Builtin -> code
    is SmileyKind.Perso -> "[:$name]"
}

private fun String.prefixLines(prefix: String): String = buildString {
    append(prefix)
    this@prefixLines.forEach { character ->
        append(character)
        if (character == '\n') append(prefix)
    }
}

private const val BLOCK_SEPARATOR = "\n\n"
private const val QUOTE_LINE_PREFIX = "> "
private const val AUTHOR_ATTRIBUTION_PREFIX = "— "
private const val SPOILER_OPEN = "[spoiler]"
private const val SPOILER_CLOSE = "[/spoiler]"
