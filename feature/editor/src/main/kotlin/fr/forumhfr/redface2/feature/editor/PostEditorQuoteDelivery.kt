package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.domain.editor.editorQuoteBlocks
import fr.forumhfr.redface2.core.model.write.QuoteSelection

/** ED-52: inline delivery comparison. Restore also uses the shared block parser (ED-89). */
internal data class PostEditorQuoteDelivery(val bbcode: String, val duplicates: Int)

internal fun filterInlineQuoteDelivery(
    category: Int,
    bodyCategory: Int,
    delivered: String,
    body: String,
): PostEditorQuoteDelivery {
    require(category > 0 && bodyCategory > 0)
    if (category != bodyCategory) return PostEditorQuoteDelivery(delivered, 0)
    val present = editorQuoteBlocks(body).map { it.identity }.toMutableSet()
    var duplicates = 0
    var cursor = 0
    val filtered = buildString {
        editorQuoteBlocks(delivered, outerOnly = true).forEach { block ->
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
