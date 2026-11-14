package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** #1553: strict versioned coordinates, with no transient card data in the persistence format. */
class PendingInlineQuoteRequestCodecTest {
    private val key = "reply:23:35421"
    private val document = """{"version":1,"scope":{"type":"topic","cat":23,"post":35421},""" +
        """"quotes":[{"numreponse":101,"page":2,"ref":null,"truncate":false}]}"""

    @Test
    fun `version one has the agreed wire format and round trips every coordinate`() {
        val request = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 2)))
        assertEquals(document, PendingInlineQuoteRequestCodec.encode(request, key))
        assertEquals(request, PendingInlineQuoteRequestCodec.decode(document, key))
        val extended = PendingInlineQuoteRequest(23, 35421,
            request.quotes + PendingInlineQuoteRequest.Quote(202, 4, 0, truncate = true))
        val encoded = PendingInlineQuoteRequestCodec.encode(extended, key)
        assertEquals(extended, PendingInlineQuoteRequestCodec.decode(encoded, key))
        for (forbidden in listOf("author", "excerpt", "hash_check", "body")) assertFalse(encoded.contains(forbidden))
    }

    @Test
    fun `MP scope round trips with required page and reference and rejects public keys`() {
        val request = PendingInlineQuoteRequest.privateMessage(42,
            listOf(PendingInlineQuoteRequest.Quote(101, 2, 0), PendingInlineQuoteRequest.Quote(202, 4, 7, true)))
        val raw = PendingInlineQuoteRequestCodec.encode(request, request.key)
        assertEquals(request, PendingInlineQuoteRequestCodec.decode(raw, request.key))
        assertEquals("mpreply:42", request.key)
        for (bad in listOf(raw.replace("\"page\":2", "\"page\":0"),
            raw.replace("\"ref\":0", "\"ref\":null"), raw.replace("\"page\":2,", ""))) {
            assertThrows(UnreadableInlineQuoteRequestException::class.java) {
                PendingInlineQuoteRequestCodec.decode(bad, request.key)
            }
        }
        assertThrows(UnreadableInlineQuoteRequestException::class.java) {
            PendingInlineQuoteRequestCodec.decode(raw, key)
        }
        assertThrows(UnreadableInlineQuoteRequestException::class.java) {
            PendingInlineQuoteRequestCodec.decode(document, request.key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingInlineQuoteRequest.privateMessage(42, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        }
    }

    @Test
    fun `decoder keeps first occurrence and selection order`() {
        val raw = document.replace(
            "\"quotes\":[", "\"quotes\":[{\"numreponse\":101,\"page\":3,\"ref\":4,\"truncate\":true},",
        )
        val request = PendingInlineQuoteRequestCodec.decode(raw, key)
        assertEquals(listOf(PendingInlineQuoteRequest.Quote(101, 3, 4, true)), request.quotes)
    }

    @Test
    fun `M7 additive unknown fields are ignored at every depth while version remains strict`() {
        val extended = document.replace("\"version\":1", "\"extra\":true,\"version\":1")
            .replace("\"cat\":23", "\"futureScope\":{},\"cat\":23")
            .replace("\"page\":2", "\"futureQuote\":[1],\"page\":2")
        assertEquals(PendingInlineQuoteRequestCodec.decode(document, key),
            PendingInlineQuoteRequestCodec.decode(extended, key))
        assertThrows(UnreadableInlineQuoteRequestException::class.java) {
            PendingInlineQuoteRequestCodec.decode(extended.replace("\"version\":1", "\"version\":2"), key)
        }
    }

    @Test
    fun `malformed unknown or mismatched documents fail with a content free error`() {
        val invalid = listOf(
            "not json", "null", "{}", document.replace("\"version\":1", "\"version\":2"),
            document.replace("\"version\":1,", ""), document.replace("topic", "private"),
            document.replace("\"cat\":23", "\"cat\":0"), document.replace("\"post\":35421", "\"post\":0"),
            document.replace("\"numreponse\":101", "\"numreponse\":0"),
            document.replace("\"page\":2", "\"page\":0"), document.replace("\"ref\":null", "\"ref\":-1"),
            document.replace("{\"numreponse\":101,\"page\":2,\"ref\":null,\"truncate\":false}", ""),
        )
        for (raw in invalid) {
            val error = assertThrows(UnreadableInlineQuoteRequestException::class.java) {
                PendingInlineQuoteRequestCodec.decode(raw, key)
            }
            assertEquals("Invalid pending inline quote request", error.message)
        }
        for (otherKey in listOf("reply:13:35421", "reply:23:35395", "mpnew")) {
            assertThrows(UnreadableInlineQuoteRequestException::class.java) {
                PendingInlineQuoteRequestCodec.decode(document, otherKey)
            }
            val request = PendingInlineQuoteRequestCodec.decode(document, key)
            assertThrows(IOException::class.java) { PendingInlineQuoteRequestCodec.encode(request, otherKey) }
        }
    }
}
