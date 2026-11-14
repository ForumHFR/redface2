package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import fr.forumhfr.redface2.core.model.write.QuoteScope
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** #1553: persistence format owned by data; invalid/unknown documents fail without changing the row. */
internal object PendingInlineQuoteRequestCodec {
    private const val VERSION = 1
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(request: PendingInlineQuoteRequest, key: String): String {
        if (request.key != key) throw IOException("Inline quote scope does not match draft")
        return json.encodeToString(Document(
            version = VERSION,
            scope = when (val target = request.scope) {
                is QuoteScope.Topic -> Scope(type = "topic", cat = target.cat, post = target.topicId)
                is QuoteScope.PrivateMessage -> Scope(type = "private_message", post = target.threadId)
            },
            quotes = request.quotes.map { Quote(it.numreponse, it.page, it.ref, it.truncate) },
        ))
    }

    @Suppress("SwallowedException") // Parser exceptions can embed the payload; only a content-free error may escape.
    fun decode(raw: String, key: String): PendingInlineQuoteRequest = try {
        val document = json.decodeFromString<Document>(raw)
        require(document.version == VERSION)
        val scope = when (document.scope.type) {
            "topic" -> QuoteScope.Topic(requireNotNull(document.scope.cat), document.scope.post)
            "private_message" -> {
                require(document.scope.cat == null)
                QuoteScope.PrivateMessage(document.scope.post)
            }
            else -> throw IllegalArgumentException("Unknown inline quote scope")
        }
        PendingInlineQuoteRequest(
            scope,
            document.quotes.map { PendingInlineQuoteRequest.Quote(it.numreponse, it.page, it.ref, it.truncate) },
        ).also { require(it.key == key) }
    } catch (_: IllegalArgumentException) {
        // Never include the serialized payload in an error or diagnostic.
        throw UnreadableInlineQuoteRequestException()
    }

    @Serializable
    private data class Document(val version: Int, val scope: Scope, val quotes: List<Quote>)

    @Serializable
    private data class Scope(val type: String, val cat: Int? = null, val post: Int)

    @Serializable
    private data class Quote(val numreponse: Int, val page: Int, val ref: Int?, val truncate: Boolean)
}
