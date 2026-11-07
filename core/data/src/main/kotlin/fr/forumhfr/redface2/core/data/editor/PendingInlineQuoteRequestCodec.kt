package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** #1553: persistence format owned by data; invalid/unknown documents fail without changing the row. */
internal object PendingInlineQuoteRequestCodec {
    private const val VERSION = 1
    private val json = Json { encodeDefaults = true }

    fun encode(request: PendingInlineQuoteRequest, key: String): String {
        if (request.key != key) throw IOException("Inline quote scope does not match draft")
        return json.encodeToString(Document(
            version = VERSION,
            scope = Scope(type = "topic", cat = request.cat, post = request.topicId),
            quotes = request.quotes.map { Quote(it.numreponse, it.page, it.ref, it.truncate) },
        ))
    }

    @Suppress("SwallowedException") // Parser exceptions can embed the payload; only a content-free error may escape.
    fun decode(raw: String, key: String): PendingInlineQuoteRequest = try {
        val document = json.decodeFromString<Document>(raw)
        require(document.version == VERSION && document.scope.type == "topic")
        PendingInlineQuoteRequest(
            document.scope.cat,
            document.scope.post,
            document.quotes.map { PendingInlineQuoteRequest.Quote(it.numreponse, it.page, it.ref, it.truncate) },
        ).also { require(it.key == key) }
    } catch (_: IllegalArgumentException) {
        // Never include the serialized payload in an error or diagnostic.
        throw IOException("Invalid pending inline quote request")
    }

    @Serializable
    private data class Document(val version: Int, val scope: Scope, val quotes: List<Quote>)

    @Serializable
    private data class Scope(val type: String, val cat: Int, val post: Int)

    @Serializable
    private data class Quote(val numreponse: Int, val page: Int, val ref: Int?, val truncate: Boolean)
}
