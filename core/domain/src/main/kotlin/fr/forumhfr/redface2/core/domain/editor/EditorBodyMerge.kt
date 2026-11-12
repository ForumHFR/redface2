package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.write.QuoteScope

/** Durable provenance of C. Contains private content; never put it in a Bundle or diagnostics. */
data class EditorBodyProvenance(
    val draftEnd: Int = 0,
    val quotesEnd: Int = 0,
    val deliveries: List<InlineQuoteDelivery> = emptyList(),
) {
    /** The complete last request, including its separators, is the ED-121 fingerprint. */
    fun isQuoteOnly(body: String): Boolean = deliveries.lastOrNull()?.body?.let {
        it.isNotBlank() && body.trimEnd() == it.trimEnd()
    } == true

    fun delivered(scope: QuoteScope, body: String): EditorBodyProvenance =
        if (body.isBlank()) this else copy(deliveries = deliveries + InlineQuoteDelivery(scope, body))

    /** Move the durable insertion boundaries with a live edit, including an edit before autosave. */
    fun rebase(previous: String, current: String): EditorBodyProvenance {
        if (previous == current) return this
        val prefix = previous.commonPrefixWith(current).length
        val suffix = previous.substring(prefix).commonSuffixWith(current.substring(prefix)).length
        val oldEnd = previous.length - suffix
        val newEnd = current.length - suffix
        fun move(offset: Int): Int {
            // An intact suffix at a boundary is also an anchor when several edits were coalesced
            // before an autosave (for example editing D and appending to the typed tail).
            val tail = previous.drop(offset).take(64)
            val anchor = if (tail.isNotBlank() && offset in prefix..oldEnd) current.indexOf(tail, prefix) else -1
            return when {
                offset == 0 -> 0
                offset < prefix -> offset
                anchor >= 0 -> anchor
                offset >= oldEnd -> offset + newEnd - oldEnd
                else -> prefix
            }.coerceIn(0, current.length)
        }
        return copy(draftEnd = move(draftEnd), quotesEnd = move(quotesEnd))
    }
}

data class InlineQuoteDelivery(val scope: QuoteScope, val body: String)
data class EditorBodyMerge(val body: String, val provenance: EditorBodyProvenance)

/**
 * ED-89: D | retained deliveries | live typing. This computes one replacement; the surface applies
 * it in one undoable edit, preserving selection/history. Server edit seeds keep their old rule.
 * Activation of this ordering in the full editors belongs to #1450 PR 2.
 */
// ED-89 ordered seed cases and retained/modified/deleted quote handling.
@Suppress("CyclomaticComplexMethod", "LongParameterList") // Explicit merge inputs retain dependent scope defaults.
fun mergeEditorBody(
    offered: String,
    current: String,
    seed: String = "",
    provenance: EditorBodyProvenance = EditorBodyProvenance(),
    scope: QuoteScope? = provenance.deliveries.lastOrNull()?.scope,
    currentScope: QuoteScope? = scope,
): EditorBodyMerge = when {
    offered.isBlank() -> EditorBodyMerge(current, provenance)
    current == offered -> EditorBodyMerge(
        current, provenance.copy(draftEnd = offered.length, quotesEnd = offered.length),
    )
    seed.isNotEmpty() -> {
        val suffix = current.takeIf { it.startsWith(seed) }?.substring(seed.length)
        val body = when {
            current.isBlank() || current == seed || suffix?.isBlank() == true -> offered
            suffix != null -> offered + "\n\n" + suffix.trimStart()
            else -> current.trimEnd() + "\n\n" + offered
        }
        EditorBodyMerge(body, provenance)
    }
    else -> {
        val delivered = provenance.deliveries.flatMap { delivery ->
            editorQuoteBlocks(delivery.body, outerOnly = true).map { delivery.scope to it.identity }
        }
        val present = editorQuoteBlocks(offered).map { it.identity }.toSet()
        val retained = mutableListOf<Pair<Int, String>>()
        var cursor = 0
        var followsQuote = false
        val typing = buildString {
            editorQuoteBlocks(current, outerOnly = true).forEach { block ->
                val exact = delivered.indexOfFirst { (deliveredScope, identity) ->
                    deliveredScope == currentScope && identity == block.identity
                }
                val sourceIndex = if (exact >= 0) exact else delivered.indexOfFirst { (deliveredScope, identity) ->
                    deliveredScope == currentScope && identity.first == block.identity.first
                }
                val source = delivered.getOrNull(sourceIndex)
                if (source != null) {
                    val between = current.substring(cursor, block.range.first)
                    if (between.isNotBlank()) append(if (followsQuote) removeEditorSeparator(between) else between)
                    val intact = source.second == block.identity
                    if (!intact || scope != currentScope || block.identity !in present) {
                        retained += sourceIndex to current.substring(block.range)
                    }
                    cursor = block.range.last + 1
                    followsQuote = true
                }
            }
            val tail = current.substring(cursor)
            append(if (followsQuote) removeEditorSeparator(tail) else tail)
        }.takeUnless { it.isBlank() }.orEmpty()
        val quotes = retained.sortedBy { it.first }.joinToString("\n\n") { it.second }
        val prefix = joinEditorParts(offered, quotes)
        val body = joinEditorParts(prefix, typing)
        EditorBodyMerge(body, provenance.copy(draftEnd = offered.length, quotesEnd = prefix.length))
    }
}

/** ED-132: insert a successful whole batch after restored D and older deliveries, before typing. */
fun insertEditorQuotes(body: String, quotes: String, provenance: EditorBodyProvenance): EditorBodyMerge {
    if (quotes.isBlank()) return EditorBodyMerge(body, provenance)
    val boundary = provenance.quotesEnd.coerceIn(0, body.length)
    val prefix = joinEditorParts(body.take(boundary), quotes.trimEnd())
    val tail = body.drop(boundary).let { if (boundary > 0) removeEditorSeparator(it) else it }
    return EditorBodyMerge(joinEditorParts(prefix, tail),
        provenance.copy(quotesEnd = prefix.length))
}

/** Remove only our separator, keeping whitespace that belongs to D or the user's input. */
private fun removeEditorSeparator(text: String): String = when {
    text.startsWith("\n\n") -> text.drop(2)
    text.startsWith("\n") -> text.drop(1)
    else -> text
}

private fun joinEditorParts(first: String, second: String): String = when {
    first.isEmpty() -> second
    second.isEmpty() -> first
    else -> first + "\n\n" + second
}

/** Same content comparison as inline delivery: number AND content, within a known scope. */
data class EditorQuoteBlock(val range: IntRange, val identity: Pair<Int, String>)
private val quoteTag = Regex("""\[quotemsg=\s*(\d+)\s*(?:,[^\]]*)?]|\[/quotemsg]""", RegexOption.IGNORE_CASE)

fun editorQuoteBlocks(text: String, outerOnly: Boolean = false): List<EditorQuoteBlock> = buildList {
    val stack = mutableListOf<MatchResult>()
    quoteTag.findAll(text).forEach { tag ->
        if (tag.groupValues[1].isNotEmpty()) {
            stack += tag
        } else if (stack.isNotEmpty()) {
            val opening = stack.removeAt(stack.lastIndex)
            if (!outerOnly || stack.isEmpty()) opening.groupValues[1].toIntOrNull()?.let {
                add(EditorQuoteBlock(opening.range.first..tag.range.last,
                    it to text.substring(opening.range.last + 1, tag.range.first)))
            }
        }
    }
}
