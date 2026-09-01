package fr.forumhfr.redface2.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 100% coverage of the pure [Flag] derivations shared across layers (#603, ADR-017): [pagesToRead],
 * [effectiveFlagColor] and the #814 [lagTone] severity tiers. Moved here from `:feature:flags` so the
 * single source of truth is tested where it lives.
 */
class FlagDerivationsTest {

    @Test
    fun `pagesToRead is the number of pages after the last read one`() {
        assertEquals(3, baseFlag.copy(totalPages = 10, lastReadPage = 7).pagesToRead())
    }

    @Test
    fun `pagesToRead is zero when the topic is fully read`() {
        assertEquals(0, baseFlag.copy(totalPages = 10, lastReadPage = 10).pagesToRead())
    }

    @Test
    fun `pagesToRead clamps to zero when lastReadPage exceeds totalPages (stale data)`() {
        assertEquals(0, baseFlag.copy(totalPages = 5, lastReadPage = 8).pagesToRead())
    }

    @Test
    fun `pagesToRead on a barely-read long topic`() {
        assertEquals(40, baseFlag.copy(totalPages = 41, lastReadPage = 1).pagesToRead())
    }

    @Test
    fun `pagesToRead with an unset last-read page (0) returns the full page count`() {
        assertEquals(7, baseFlag.copy(totalPages = 7, lastReadPage = 0).pagesToRead())
    }

    @Test
    fun `effective color is the bucket type when the topic is not a favorite`() {
        assertEquals(FlagType.CYAN, baseFlag.copy(type = FlagType.CYAN, isFavorite = false).effectiveFlagColor())
        assertEquals(FlagType.RED, baseFlag.copy(type = FlagType.RED, isFavorite = false).effectiveFlagColor())
    }

    @Test
    fun `effective color is FAVORITE when the topic is favorited regardless of bucket`() {
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.CYAN, isFavorite = true).effectiveFlagColor())
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.RED, isFavorite = true).effectiveFlagColor())
    }

    @Test
    fun `effective color of a FAVORITE bucket flag is FAVORITE`() {
        assertEquals(FlagType.FAVORITE, baseFlag.copy(type = FlagType.FAVORITE).effectiveFlagColor())
    }

    @Test
    fun `the type-isFavorite overload mirrors the Flag extension`() {
        assertEquals(FlagType.FAVORITE, effectiveFlagColor(FlagType.CYAN, isFavorite = true))
        assertEquals(FlagType.RED, effectiveFlagColor(FlagType.RED, isFavorite = false))
    }

    // #814 — lag tone tiers (1-2 / 3-9 / >= 10), inclusive lower bounds.

    @Test
    fun `lagTone is LOW for one or two pages behind`() {
        assertEquals(LagTone.LOW, lagTone(1))
        assertEquals(LagTone.LOW, lagTone(2))
    }

    @Test
    fun `lagTone is LOW for zero and negative values (never rendered, but total)`() {
        assertEquals(LagTone.LOW, lagTone(0))
        assertEquals(LagTone.LOW, lagTone(-1))
        assertEquals(LagTone.LOW, lagTone(Int.MIN_VALUE))
    }

    @Test
    fun `lagTone switches to MEDIUM at exactly three pages`() {
        assertEquals(LagTone.MEDIUM, lagTone(3))
        assertEquals(3, LAG_TONE_MEDIUM_MIN_PAGES)
    }

    @Test
    fun `lagTone stays MEDIUM up to nine pages`() {
        assertEquals(LagTone.MEDIUM, lagTone(5))
        assertEquals(LagTone.MEDIUM, lagTone(9))
    }

    @Test
    fun `lagTone switches to HIGH at exactly ten pages`() {
        assertEquals(LagTone.HIGH, lagTone(10))
        assertEquals(10, LAG_TONE_HIGH_MIN_PAGES)
    }

    @Test
    fun `lagTone stays HIGH for any larger backlog`() {
        assertEquals(LagTone.HIGH, lagTone(26))
        assertEquals(LagTone.HIGH, lagTone(1_700))
        assertEquals(LagTone.HIGH, lagTone(Int.MAX_VALUE))
    }

    @Test
    fun `lagTone is monotonic in the number of pages`() {
        var previous = lagTone(0)
        for (pages in 1..40) {
            val current = lagTone(pages)
            assertTrue("tone must never decrease ($previous → $current at $pages)", current >= previous)
            previous = current
        }
    }

    @Test
    fun `Flag lagTone is derived from pagesToRead, not from the flag type`() {
        // Same backlog, different buckets / favori decoration → same tone (the whole point of #814).
        val cyan = baseFlag.copy(type = FlagType.CYAN, totalPages = 20, lastReadPage = 8)
        val red = baseFlag.copy(type = FlagType.RED, totalPages = 20, lastReadPage = 8)
        val favorite = baseFlag.copy(type = FlagType.CYAN, isFavorite = true, totalPages = 20, lastReadPage = 8)
        assertEquals(LagTone.HIGH, cyan.lagTone())
        assertEquals(LagTone.HIGH, red.lagTone())
        assertEquals(LagTone.HIGH, favorite.lagTone())
    }

    @Test
    fun `Flag lagTone follows the pagesToRead clamp on stale data`() {
        // lastReadPage past totalPages clamps pagesToRead to 0 → LOW, never a negative-driven tier.
        assertEquals(LagTone.LOW, baseFlag.copy(totalPages = 5, lastReadPage = 8).lagTone())
        assertEquals(LagTone.MEDIUM, baseFlag.copy(totalPages = 10, lastReadPage = 7).lagTone())
    }

    private val baseFlag = Flag(
        cat = 1,
        subcat = null,
        topicId = 1,
        title = "Topic",
        totalPages = 1,
        replyCount = 0,
        type = FlagType.CYAN,
        isFavorite = false,
        hasUnread = true,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "op",
        lastReplyAuthor = "last",
        lastReplyAt = "2026-06-24 12:00",
    )
}
