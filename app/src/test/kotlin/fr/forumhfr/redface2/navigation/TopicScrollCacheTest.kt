package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-page scroll-anchor cache helper behind the swipe scroll restoration (#307).
 * Twin of [TopicTitleCacheTest] (PR #338): a pure function over an immutable map, unit-testable
 * without Compose.
 */
class TopicScrollCacheTest {

    private fun key(cat: Int, post: Int, page: Int) = TopicScrollKey(cat, post, page)

    @Test
    fun `withScrollAnchor inserts a new entry`() {
        val cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 5, offset = 42))

        assertEquals(TopicScrollAnchor(index = 5, offset = 42), cache[key(1, 10, 2)])
    }

    @Test
    fun `withScrollAnchor keys by (cat, post, page) so other pages and categories do not collide`() {
        val cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 5, offset = 42))
            .withScrollAnchor(key(1, 10, 3), TopicScrollAnchor(index = 9, offset = 7))
            .withScrollAnchor(key(2, 10, 2), TopicScrollAnchor(index = 1, offset = 0))

        assertEquals(TopicScrollAnchor(index = 5, offset = 42), cache[key(1, 10, 2)])
        assertEquals(TopicScrollAnchor(index = 9, offset = 7), cache[key(1, 10, 3)])
        assertEquals(TopicScrollAnchor(index = 1, offset = 0), cache[key(2, 10, 2)])
        assertEquals(3, cache.size)
    }

    @Test
    fun `withScrollAnchor updates the value when the anchor changed`() {
        val cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 5, offset = 42))
            .withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 8, offset = 13))

        assertEquals(TopicScrollAnchor(index = 8, offset = 13), cache[key(1, 10, 2)])
        assertEquals(1, cache.size)
    }

    @Test
    fun `withScrollAnchor returns the same instance when the anchor is unchanged (no recomposition)`() {
        val cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 5, offset = 42))

        val again = cache.withScrollAnchor(key(1, 10, 2), TopicScrollAnchor(index = 5, offset = 42))

        assertSame("an unchanged anchor must not allocate a new map", cache, again)
    }

    @Test
    fun `withScrollAnchor keeps a header-visible anchor intact (item 0 plus offset)`() {
        // Header-aware contract: item 0 is the topic header card, so an anchor with index 0 and a
        // non-zero offset means « partway through the header » — it must round-trip raw, never be
        // re-based on the first visible post.
        val headerAnchor = TopicScrollAnchor(index = 0, offset = 137)
        val cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(1, 10, 1), headerAnchor)

        assertEquals(headerAnchor, cache[key(1, 10, 1)])
    }

    @Test
    fun `withScrollAnchor evicts the oldest entries past the cap (FIFO)`() {
        var cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
        repeat(TOPIC_SCROLL_ANCHOR_CACHE_MAX + 10) { i ->
            cache = cache.withScrollAnchor(key(0, 1, i), TopicScrollAnchor(index = i, offset = 0))
        }

        assertEquals(TOPIC_SCROLL_ANCHOR_CACHE_MAX, cache.size)
        assertTrue(
            "the 10 oldest insertions are evicted",
            (0 until 10).none { cache.containsKey(key(0, 1, it)) },
        )
        assertTrue(
            "the newest insertion is kept",
            cache.containsKey(key(0, 1, TOPIC_SCROLL_ANCHOR_CACHE_MAX + 9)),
        )
    }

    @Test
    fun `withScrollAnchor refreshes the eviction rank when an existing key is updated`() {
        // Regression (review sev. 82): `LinkedHashMap.put` keeps an updated key at its ORIGINAL
        // insertion rank, so a page visited first, then revisited and re-scrolled, was the first
        // evicted — despite holding the freshest anchor. The update must move the key to the tail.
        var cache = emptyMap<TopicScrollKey, TopicScrollAnchor>()
            .withScrollAnchor(key(9, 99, 1), TopicScrollAnchor(index = 5, offset = 42))
        repeat(TOPIC_SCROLL_ANCHOR_CACHE_MAX - 1) { i ->
            cache = cache.withScrollAnchor(key(0, 1, i), TopicScrollAnchor(index = i, offset = 0))
        }

        // The cache is exactly full; updating the oldest key must re-rank it to most recent…
        cache = cache.withScrollAnchor(key(9, 99, 1), TopicScrollAnchor(index = 50, offset = 7))
        // …so the next insertion evicts the oldest of the filler keys, not the just-updated one.
        cache = cache.withScrollAnchor(key(0, 1, 9999), TopicScrollAnchor(index = 1, offset = 0))

        assertEquals(TOPIC_SCROLL_ANCHOR_CACHE_MAX, cache.size)
        assertEquals(TopicScrollAnchor(index = 50, offset = 7), cache[key(9, 99, 1)])
        assertTrue("the oldest filler entry is the one evicted", !cache.containsKey(key(0, 1, 0)))
    }
}
