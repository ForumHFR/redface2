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

    fun delivered(scope: QuoteScope, body: String, currentBody: String = body): EditorBodyProvenance =
        if (body.isBlank()) this else InlineQuoteDelivery(scope, body).let { latest ->
            copy(deliveries = deliveries.filterNot { it == latest }.distinct() + latest).retainFor(currentBody)
        }

    /** Deleted blocks can be reinserted after autosave/death; retain their scoped proof too (ED-89). */
    fun retainFor(body: String): EditorBodyProvenance = copy(
        deliveries = deliveries.distinct(),
        draftEnd = draftEnd.coerceAtMost(body.length),
        quotesEnd = quotesEnd.coerceAtMost(body.length),
    )

    /** Move the durable insertion boundaries with a live edit, including an edit before autosave. */
    fun rebase(previous: String, current: String): EditorBodyProvenance {
        if (previous == current) return retainFor(current)
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
                // ED-89, one rule whatever follows: text inserted exactly at a boundary is new typing, so
                // the boundary stays and a later quote lands before that text.
                offset == prefix && oldEnd == prefix -> offset
                anchor >= 0 -> anchor
                offset >= oldEnd -> offset + newEnd - oldEnd
                else -> prefix
            }.coerceIn(0, current.length)
        }
        val movedDraftEnd = move(draftEnd)
        return retainFor(current).copy(
            draftEnd = movedDraftEnd, quotesEnd = move(quotesEnd).coerceAtLeast(movedDraftEnd),
        )
    }
}

data class InlineQuoteDelivery(val scope: QuoteScope, val body: String)
data class EditorBodyMerge(val body: String, val provenance: EditorBodyProvenance)

/**
 * ED-89: D | retained deliveries | live typing. This computes one replacement; the surface applies
 * it in one undoable edit, preserving selection/history. Server edit seeds keep their old rule.
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
        val retained = mutableListOf<String>()
        var cursor = 0
        var followsQuote = false
        val typing = buildString {
            editorQuoteBlocks(current, outerOnly = true).forEach { block ->
                val exact = delivered.indexOfFirst { (deliveredScope, identity) ->
                    deliveredScope == currentScope && identity == block.identity
                }
                val source = delivered.getOrNull(exact) ?: delivered.firstOrNull { (deliveredScope, identity) ->
                    deliveredScope == currentScope && identity.first == block.identity.first
                }
                if (source != null) {
                    val between = current.substring(cursor, block.range.first)
                    if (between.isNotBlank()) append(if (followsQuote) removeEditorSeparator(between) else between)
                    val intact = source.second == block.identity
                    if (!intact || scope != currentScope || block.identity !in present) {
                        retained += current.substring(block.range)
                    }
                    cursor = block.range.last + 1
                    followsQuote = true
                }
            }
            val tail = current.substring(cursor)
            append(if (followsQuote) removeEditorSeparator(tail) else tail)
        }.takeUnless { it.isBlank() }.orEmpty()
        // ED-89: the field already holds the deliveries in request order. Older evidence of the same
        // post (kept for reinsertion) must not reorder them.
        val quotes = retained.joinToString("\n\n")
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

/** ED-52: compare intact blocks by number and content inside an already validated owning scope. */
data class FilteredEditorQuotes(val body: String, val duplicates: Int)

fun filterEditorQuotes(delivered: String, current: String): FilteredEditorQuotes {
    val present = editorQuoteBlocks(current).map { it.identity }.toMutableSet()
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
    return FilteredEditorQuotes(if (duplicates == 0) delivered else filtered.trim(), duplicates)
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

/**
 * ED-121: the offers still worth a decision over [liveBody]. An offer identical to the field has
 * nothing to restore: it is hidden from this projection only, never deleted, and a later refresh
 * shows it again once the field differs. Other offers stay reachable.
 */
fun List<EditorDraftStore.DraftOffer>.visibleOver(liveBody: String): List<EditorDraftStore.DraftOffer> =
    filterNot { it.draft.body == liveBody }

/**
 * Narrow an already shown offer state at form hydration, without ever showing an offer again:
 * a null [shown] stays null (a decision is in flight). Returns the visible list and its first body.
 */
fun narrowDraftOffers(
    offers: List<EditorDraftStore.DraftOffer>, shown: String?, liveBody: String,
): Pair<List<EditorDraftStore.DraftOffer>, String?> {
    val visible = offers.visibleOver(liveBody)
    val first = when {
        shown == null -> null
        offers.isEmpty() -> shown.takeIf { it != liveBody }
        else -> visible.firstOrNull()?.draft?.body
    }
    return visible to first
}
