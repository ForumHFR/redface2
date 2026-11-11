package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.model.write.QuoteSelection

/** ED-52: compare deliveries, never a restored draft. Both scopes must be known. */
internal data class PostEditorQuoteDelivery(val bbcode: String, val duplicates: Int)

internal fun filterInlineQuoteDelivery(
    category: Int,
    bodyCategory: Int,
    delivered: String,
    body: String,
): PostEditorQuoteDelivery {
    require(category > 0 && bodyCategory > 0)
    if (category != bodyCategory) return PostEditorQuoteDelivery(delivered, 0)
    val present = quoteBlocks(body).map { it.identity }.toMutableSet()
    var duplicates = 0
    var cursor = 0
    val filtered = buildString {
        quoteBlocks(delivered, outerOnly = true).forEach { block ->
            append(delivered.substring(cursor, block.range.first))
            if (present.add(block.identity)) append(delivered.substring(block.range)) else duplicates++
            cursor = block.range.last + 1
        }
        append(delivered.substring(cursor))
    }
    return PostEditorQuoteDelivery(if (duplicates == 0) delivered else filtered.trim(), duplicates)
}

/** Card content is the captured excerpt, not the locator's page/ref or the author's spelling. */
internal fun distinctQuoteCards(category: Int, quotes: List<QuoteSelection>): List<QuoteSelection> {
    require(category > 0)
    return quotes.distinctBy { Triple(it.numreponse, it.excerpt, it.truncate) }
}

private data class QuoteBlock(val range: IntRange, val identity: Pair<Int, String>)
private val quoteTag = Regex("""\[quotemsg=\s*(\d+)\s*(?:,[^\]]*)?]|\[/quotemsg]""", RegexOption.IGNORE_CASE)

/** Only remove whole outer deliveries, but an identical block can already be nested in the body. */
private fun quoteBlocks(text: String, outerOnly: Boolean = false): List<QuoteBlock> = buildList {
    val stack = mutableListOf<MatchResult>()
    quoteTag.findAll(text).forEach { tag ->
        if (tag.groupValues[1].isNotEmpty()) {
            stack += tag
        } else if (stack.isNotEmpty()) {
            val opening = stack.removeAt(stack.lastIndex)
            if (!outerOnly || stack.isEmpty()) opening.groupValues[1].toIntOrNull()?.let {
                add(QuoteBlock(opening.range.first..tag.range.last,
                    it to text.substring(opening.range.last + 1, tag.range.first)))
            }
        }
    }
}
