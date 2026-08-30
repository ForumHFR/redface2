package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #465 — covers the per-topic poll-expansion cache helper that makes a manual collapse/expand
 * survive page navigation within a topic. Twin of [TopicScrollCacheTest] / [TopicTitleCacheTest]:
 * a pure function over an immutable map, unit-testable without Compose.
 */
class TopicPollExpansionTest {

    private fun key(cat: Int, post: Int) = TopicPollKey(cat, post)

    @Test
    fun `withPollExpansion inserts a new manual choice`() {
        val cache = emptyMap<TopicPollKey, Boolean>().withPollExpansion(key(1, 10), expanded = false)

        assertEquals(false, cache[key(1, 10)])
    }

    @Test
    fun `absent topic returns null so the screen follows its automatic policy`() {
        val cache = emptyMap<TopicPollKey, Boolean>().withPollExpansion(key(1, 10), expanded = true)

        // A topic the user never toggled has no entry: the lookup is null, and TopicScreen resolves
        // the #456 global default plus the independent #1170 unanswered-poll opt-in.
        assertNull(cache[key(2, 20)])
    }

    @Test
    fun `withPollExpansion keys by (cat, post) so other topics and categories do not collide`() {
        val cache = emptyMap<TopicPollKey, Boolean>()
            .withPollExpansion(key(1, 10), expanded = false)
            .withPollExpansion(key(1, 11), expanded = true)
            .withPollExpansion(key(2, 10), expanded = true)

        assertEquals(false, cache[key(1, 10)])
        assertEquals(true, cache[key(1, 11)])
        assertEquals(true, cache[key(2, 10)])
        assertEquals(3, cache.size)
    }

    @Test
    fun `withPollExpansion flips the value when the user toggles again`() {
        // The core #465 scenario: default expanded → user collapses on page N → the collapse must
        // persist (a later toggle expands it again). One entry per topic, last write wins.
        val cache = emptyMap<TopicPollKey, Boolean>()
            .withPollExpansion(key(1, 10), expanded = false)
            .withPollExpansion(key(1, 10), expanded = true)

        assertEquals(true, cache[key(1, 10)])
        assertEquals(1, cache.size)
    }

    @Test
    fun `withPollExpansion returns the same instance when unchanged (no recomposition)`() {
        val cache = emptyMap<TopicPollKey, Boolean>().withPollExpansion(key(1, 10), expanded = true)

        val again = cache.withPollExpansion(key(1, 10), expanded = true)

        assertSame("an unchanged choice must not allocate a new map", cache, again)
    }

    @Test
    fun `withPollExpansion evicts the oldest entries past the cap`() {
        var cache = emptyMap<TopicPollKey, Boolean>()
        repeat(TOPIC_POLL_EXPANSION_CACHE_MAX + 10) { i ->
            cache = cache.withPollExpansion(key(0, i), expanded = i % 2 == 0)
        }

        assertEquals(TOPIC_POLL_EXPANSION_CACHE_MAX, cache.size)
        assertTrue(
            "the 10 oldest insertions are evicted",
            (0 until 10).none { cache.containsKey(key(0, it)) },
        )
        assertTrue(
            "the newest insertion is kept",
            cache.containsKey(key(0, TOPIC_POLL_EXPANSION_CACHE_MAX + 9)),
        )
    }

    @Test
    fun `withPollExpansion refreshes the eviction rank when an existing key is updated`() {
        // Same LRU-by-write contract as withScrollAnchor: re-toggling a topic must move it to the
        // tail, so a topic toggled early but still actively read is not the first evicted.
        var cache = emptyMap<TopicPollKey, Boolean>()
            .withPollExpansion(key(9, 99), expanded = false)
        repeat(TOPIC_POLL_EXPANSION_CACHE_MAX - 1) { i ->
            cache = cache.withPollExpansion(key(0, i), expanded = true)
        }

        // Cache exactly full; updating the oldest key must re-rank it to most recent…
        cache = cache.withPollExpansion(key(9, 99), expanded = true)
        // …so the next insertion evicts the oldest filler key, not the just-updated one.
        cache = cache.withPollExpansion(key(0, 9999), expanded = false)

        assertEquals(TOPIC_POLL_EXPANSION_CACHE_MAX, cache.size)
        assertEquals(true, cache[key(9, 99)])
        assertFalse("the oldest filler entry is the one evicted", cache.containsKey(key(0, 0)))
    }
}
