package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection

/** SavedStateHandle-safe pending request: coordinates only, never quote bodies or auth data. */
fun List<QuoteSelection>.encodeInlineQuoteRequest(): IntArray = flatMap {
    listOf(it.numreponse, it.locator.page, it.locator.ref ?: -1, if (it.truncate) 1 else 0)
}.toIntArray()

fun IntArray.decodeInlineQuoteRequest(): List<QuoteSelection> = toList().chunked(4).map { values ->
    require(values.size == 4)
    QuoteSelection(QuoteLocator(values[1], values[0], values[2].takeIf { it >= 0 }), "", "", values[3] == 1)
}

/** Order and truncation are part of delivery identity; numreponse is unique within this category. */
fun List<QuoteSelection>.inlineQuoteDeliveryKey(): String = joinToString(",") { "${it.numreponse}:${it.truncate}" }
