package fr.forumhfr.redface2.core.domain.editor

/**
 * Durable coordinates for inline topic quotes, without card snapshots or HFR credentials (#1553).
 * [numreponse][Quote.numreponse] is unique per category, never globally. The enclosing topic scope
 * must match the draft's reply key. Order is stable and the first occurrence of each post wins.
 */
class PendingInlineQuoteRequest(val cat: Int, val topicId: Int, quotes: List<Quote>) {
    val quotes: List<Quote> = quotes.distinctBy { it.numreponse }
    val key: String get() = EditorDraftKey.reply(cat, topicId)

    init {
        require(cat > 0 && topicId > 0) { "Invalid inline quote scope" }
        require(quotes.isNotEmpty()) { "An inline quote request must contain coordinates" }
    }

    /** Merge only requests for the same composition, preserving selection order. */
    fun merge(other: PendingInlineQuoteRequest): PendingInlineQuoteRequest {
        require(key == other.key) { "Inline quote scopes differ" }
        return PendingInlineQuoteRequest(cat, topicId, quotes + other.quotes)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PendingInlineQuoteRequest) return false
        return cat == other.cat && topicId == other.topicId && quotes == other.quotes
    }

    override fun hashCode(): Int = 31 * (31 * cat + topicId) + quotes.hashCode()

    /** Server coordinates only; [ref] is optional for a topic quote. */
    data class Quote(val numreponse: Int, val page: Int, val ref: Int? = null, val truncate: Boolean = false) {
        init {
            require(numreponse > 0 && page > 0) { "Invalid inline quote coordinates" }
            require(ref == null || ref >= 0) { "Invalid inline quote reference" }
        }
    }
}
