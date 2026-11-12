package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.domain.editor.EditorBodyProvenance
import fr.forumhfr.redface2.core.domain.editor.InlineQuoteDelivery
import fr.forumhfr.redface2.core.model.write.QuoteScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Content-bearing persistence only. Decode errors must never include a private serialized payload. */
internal object EditorBodyProvenanceCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(state: EditorBodyProvenance): String = json.encodeToString(Document(
        draftEnd = state.draftEnd, quotesEnd = state.quotesEnd,
        deliveries = state.deliveries.map { delivery ->
            when (val scope = delivery.scope) {
                is QuoteScope.Topic -> Delivery("topic", scope.cat, scope.topicId, delivery.body)
                is QuoteScope.PrivateMessage -> Delivery("private_message", null, scope.threadId, delivery.body)
            }
        },
    ))

    @Suppress("SwallowedException") // Serialization errors can contain private bodies.
    fun decode(raw: String): EditorBodyProvenance? = try {
        val value = json.decodeFromString<Document>(raw)
        require(value.version == 1 && value.draftEnd >= 0 && value.quotesEnd >= value.draftEnd)
        EditorBodyProvenance(value.draftEnd, value.quotesEnd, value.deliveries.map {
            require(it.post > 0)
            val scope = when (it.type) {
                "topic" -> QuoteScope.Topic(requireNotNull(it.cat).also { cat -> require(cat > 0) }, it.post)
                "private_message" -> {
                    require(it.cat == null)
                    QuoteScope.PrivateMessage(it.post)
                }
                else -> throw IllegalArgumentException("Unknown scope")
            }
            InlineQuoteDelivery(scope, it.body)
        })
    } catch (_: IllegalArgumentException) {
        null // Provenance is optional evidence; corrupt metadata must never seal the live body.
    }

    @Serializable
    private data class Document(
        val version: Int = 1, val draftEnd: Int, val quotesEnd: Int, val deliveries: List<Delivery>,
    )
    @Serializable
    private data class Delivery(val type: String, val cat: Int?, val post: Int, val body: String)
}
