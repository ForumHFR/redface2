package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-topic title cache helper that fixes the « Sujet » flash on a page change (#338).
 * The helper is a pure function over an immutable map, so it is unit-testable without Compose.
 */
class TopicTitleCacheTest {

    private fun key(cat: Int, post: Int) = TopicTitleKey(cat, post)

    @Test
    fun `withTitle inserts a new entry`() {
        val cache = emptyMap<TopicTitleKey, String>().withTitle(key(1, 10), "Sujet A")

        assertEquals("Sujet A", cache[key(1, 10)])
    }

    @Test
    fun `withTitle keys by (cat, post) so the same topic id in another category does not collide`() {
        val cache = emptyMap<TopicTitleKey, String>()
            .withTitle(key(1, 10), "A in cat 1")
            .withTitle(key(2, 10), "A in cat 2")

        assertEquals("A in cat 1", cache[key(1, 10)])
        assertEquals("A in cat 2", cache[key(2, 10)])
        assertEquals(2, cache.size)
    }

    @Test
    fun `withTitle updates the value when the title changed`() {
        val cache = emptyMap<TopicTitleKey, String>()
            .withTitle(key(1, 10), "old")
            .withTitle(key(1, 10), "new")

        assertEquals("new", cache[key(1, 10)])
        assertEquals(1, cache.size)
    }

    @Test
    fun `withTitle returns the same instance when the title is unchanged (no recomposition)`() {
        val cache = emptyMap<TopicTitleKey, String>().withTitle(key(1, 10), "Sujet A")

        val again = cache.withTitle(key(1, 10), "Sujet A")

        assertSame("an unchanged title must not allocate a new map", cache, again)
    }

    @Test
    fun `withTitle evicts the oldest entries past the cap (FIFO)`() {
        var cache = emptyMap<TopicTitleKey, String>()
        repeat(TOPIC_TITLE_CACHE_MAX + 10) { i -> cache = cache.withTitle(key(0, i), "title $i") }

        assertEquals(TOPIC_TITLE_CACHE_MAX, cache.size)
        assertTrue(
            "the 10 oldest insertions are evicted",
            (0 until 10).none { cache.containsKey(key(0, it)) },
        )
        assertTrue(
            "the newest insertion is kept",
            cache.containsKey(key(0, TOPIC_TITLE_CACHE_MAX + 9)),
        )
    }
}
