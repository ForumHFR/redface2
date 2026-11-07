package fr.forumhfr.redface2.core.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** #1553: durable requests contain valid, ordered coordinates in one topic scope. */
class PendingInlineQuoteRequestTest {
    @Test
    fun `first occurrence wins and constructor detaches from the input list`() {
        val first = PendingInlineQuoteRequest.Quote(101, 2, 0, truncate = true)
        val input = mutableListOf(first, PendingInlineQuoteRequest.Quote(202, 3), first.copy(page = 4))
        val request = PendingInlineQuoteRequest(23, 35421, input)
        input.clear()
        assertEquals(listOf(first, PendingInlineQuoteRequest.Quote(202, 3)), request.quotes)
        assertEquals("reply:23:35421", request.key)
    }

    @Test
    fun `merge preserves order and deduplicates only within the matching scope`() {
        val first = PendingInlineQuoteRequest(23, 35421, listOf(PendingInlineQuoteRequest.Quote(101, 1)))
        val next = PendingInlineQuoteRequest(23, 35421,
            listOf(PendingInlineQuoteRequest.Quote(101, 3), PendingInlineQuoteRequest.Quote(202, 4)))
        assertEquals(listOf(first.quotes.single(), next.quotes.last()), first.merge(next).quotes)
        val otherCategory = PendingInlineQuoteRequest(13, 35421, first.quotes)
        val otherTopic = PendingInlineQuoteRequest(23, 35395, first.quotes)
        for (other in listOf(otherCategory, otherTopic)) {
            assertNotEquals(first, other)
            assertThrows(IllegalArgumentException::class.java) { first.merge(other) }
        }
    }

    @Test
    fun `empty requests invalid scopes and invalid coordinates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PendingInlineQuoteRequest(23, 1, emptyList()) }
        for ((cat, topic) in listOf(0 to 1, 23 to 0, -1 to 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                PendingInlineQuoteRequest(cat, topic, listOf(PendingInlineQuoteRequest.Quote(1, 1)))
            }
        }
        for ((post, page, ref) in listOf(Triple(0, 1, null), Triple(1, 0, null), Triple(1, 1, -1))) {
            assertThrows(IllegalArgumentException::class.java) { PendingInlineQuoteRequest.Quote(post, page, ref) }
        }
    }
}
