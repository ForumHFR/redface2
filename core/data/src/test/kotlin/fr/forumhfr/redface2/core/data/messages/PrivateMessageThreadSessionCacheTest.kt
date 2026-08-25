package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageThreadSessionCacheTest {

    @Test
    fun `keys normalize the account and isolate thread and page`() {
        val cache = PrivateMessageThreadSessionCache()
        val alice = cache.capture("  Alice  ")
        val stored = thread(threadId = 42, page = 2)

        cache.write(alice, threadId = 42, page = 2, thread = stored)

        assertTrue(cache.contains(cache.capture("alice"), threadId = 42, page = 2))
        assertEquals(stored, cache.read(cache.capture("alice"), threadId = 42, page = 2))
        assertFalse(cache.contains(cache.capture("bob"), threadId = 42, page = 2))
        assertNull(cache.read(cache.capture("alice"), threadId = 43, page = 2))
        assertNull(cache.read(cache.capture("alice"), threadId = 42, page = 1))
        assertNull(cache.read(cache.capture("bob"), threadId = 42, page = 2))
    }

    @Test
    fun `LRU is global and bounded to five pages`() {
        val cache = PrivateMessageThreadSessionCache()
        val stamp = cache.capture("alice")
        (1..5).forEach { page ->
            cache.write(stamp, threadId = 42, page = page, thread = thread(page = page))
        }
        // A read promotes page 1. The sixth global entry must evict page 2, not page 1.
        assertEquals(1, cache.read(stamp, threadId = 42, page = 1)?.page)
        val otherAccount = cache.capture("bob")

        cache.write(otherAccount, threadId = 99, page = 1, thread = thread(threadId = 99, page = 1))

        assertEquals(1, cache.read(stamp, threadId = 42, page = 1)?.page)
        assertNull(cache.read(stamp, threadId = 42, page = 2))
        assertEquals(1, cache.read(otherAccount, threadId = 99, page = 1)?.page)
    }

    @Test
    fun `clear advances the generation and rejects every stale stamp`() {
        val cache = PrivateMessageThreadSessionCache()
        val stale = cache.capture("alice")
        cache.write(stale, threadId = 42, page = 1, thread = thread())

        cache.clearAndAdvanceGeneration()

        assertFalse(cache.isCurrent(stale))
        assertFalse(cache.contains(stale, threadId = 42, page = 1))
        assertNull(cache.read(stale, threadId = 42, page = 1))
        cache.write(stale, threadId = 42, page = 1, thread = thread(subject = "late"))
        val current = cache.capture("alice")
        assertTrue(cache.isCurrent(current))
        assertNull(cache.read(current, threadId = 42, page = 1))
    }

    private fun thread(
        threadId: Int = 42,
        subject: String = "Sujet",
        page: Int = 1,
    ) = PrivateMessageThread(
        threadId = threadId,
        subject = subject,
        correspondent = "Correspondant",
        messages = emptyList(),
        page = page,
        totalPages = page,
    )
}
