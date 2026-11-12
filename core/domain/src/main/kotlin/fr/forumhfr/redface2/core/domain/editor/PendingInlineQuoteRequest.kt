package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.write.QuoteScope
import java.io.IOException

/**
 * Durable coordinates for inline topic or MP quotes, without card snapshots or HFR credentials (#1553).
 * [numreponse][Quote.numreponse] is unique per category, never globally. The enclosing reply scope
 * must match the draft's reply key. Order is stable and the first occurrence of each post wins.
 */
class PendingInlineQuoteRequest(val scope: QuoteScope, quotes: List<Quote>) {
    constructor(cat: Int, topicId: Int, quotes: List<Quote>) : this(QuoteScope.Topic(cat, topicId), quotes)

    val cat: Int get() = (scope as QuoteScope.Topic).cat
    val topicId: Int get() = (scope as QuoteScope.Topic).topicId
    val isPrivate: Boolean get() = scope is QuoteScope.PrivateMessage
    val quotes: List<Quote> = quotes.distinctBy { it.numreponse }
    val key: String get() = when (val target = scope) {
        is QuoteScope.Topic -> EditorDraftKey.reply(target.cat, target.topicId)
        is QuoteScope.PrivateMessage -> EditorDraftKey.mpReply(target.threadId)
    }

    init {
        when (scope) {
            is QuoteScope.Topic -> require(scope.cat > 0 && scope.topicId > 0)
            is QuoteScope.PrivateMessage -> {
                require(scope.threadId > 0)
                require(quotes.all { it.ref != null }) { "MP quote references are mandatory" }
            }
        }
        require(quotes.isNotEmpty()) { "An inline quote request must contain coordinates" }
    }

    /** Merge only requests for the same composition, preserving selection order. */
    fun merge(other: PendingInlineQuoteRequest): PendingInlineQuoteRequest {
        require(key == other.key) { "Inline quote scopes differ" }
        return PendingInlineQuoteRequest(scope, quotes + other.quotes)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PendingInlineQuoteRequest) return false
        return scope == other.scope && quotes == other.quotes
    }

    override fun hashCode(): Int = 31 * scope.hashCode() + quotes.hashCode()

    companion object {
        fun privateMessage(threadId: Int, quotes: List<Quote>): PendingInlineQuoteRequest =
            PendingInlineQuoteRequest(QuoteScope.PrivateMessage(threadId), quotes)
    }

    /** Server coordinates only; [ref] is optional for a topic quote. */
    data class Quote(val numreponse: Int, val page: Int, val ref: Int? = null, val truncate: Boolean = false) {
        init {
            require(numreponse > 0 && page > 0) { "Invalid inline quote coordinates" }
            require(ref == null || ref >= 0) { "Invalid inline quote reference" }
        }
    }
}

/** A corrupt/unknown request seals the row; storage attaches the observed revision for explicit discard. */
class UnreadableInlineQuoteRequestException(
    val contentVersion: Long? = null,
    cause: UnreadableInlineQuoteRequestException? = null,
) : IOException("Invalid pending inline quote request", cause)
