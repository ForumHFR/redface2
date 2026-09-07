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
    fun `pagesToRead counts pages after the opened page when the position is absent`() {
        assertEquals(3, baseFlag.copy(totalPages = 10, lastReadPage = 7).pagesToRead())
    }

    @Test
    fun `pagesToRead excludes page 60 when stopped at the bottom of page 59 of 60`() {
        val flag = baseFlag.copy(totalPages = 60, lastReadPage = 59, lastPosition = 2360)

        assertEquals(60, flag.pageToOpen())
        assertEquals(0, flag.pagesToRead())
        assertTrue("no badge does not mean fully read", flag.hasUnread)
        assertEquals(LagTone.LOW, flag.lagTone())
    }

    @Test
    fun `pagesToRead is one when stopped in the middle of page 59 of 60`() {
        val flag = baseFlag.copy(totalPages = 60, lastReadPage = 59, lastPosition = 2340)

        assertEquals(59, flag.pageToOpen())
        assertEquals(1, flag.pagesToRead())
        assertEquals(LagTone.LOW, flag.lagTone())
    }

    @Test
    fun `pagesToRead is zero when the topic is fully read`() {
        assertEquals(0, baseFlag.copy(totalPages = 10, lastReadPage = 10, hasUnread = false).pagesToRead())
    }

    @Test
    fun `pagesToRead can be zero while posts on the opened last page remain unread`() {
        val flag = baseFlag.copy(totalPages = 60, lastReadPage = 60, lastPosition = 2380)

        assertEquals(0, flag.pagesToRead())
        assertTrue(flag.hasUnread)
    }

    @Test
    fun `pagesToRead does not advance on an inconsistent position and page pair`() {
        val flag = baseFlag.copy(totalPages = 60, lastReadPage = 59, lastPosition = 40)

        assertEquals(59, flag.pageToOpen())
        assertEquals(1, flag.pagesToRead())
    }

    @Test
    fun `pagesToRead remains a display counter when hasUnread is false`() {
        val flag = baseFlag.copy(totalPages = 60, lastReadPage = 59, lastPosition = 2360, hasUnread = false)

        assertEquals(59, flag.pageToOpen())
        assertEquals(1, flag.pagesToRead())
    }

    @Test
    fun `pagesToRead clamps to zero when lastReadPage exceeds totalPages (stale data)`() {
        val flag = baseFlag.copy(totalPages = 5, lastReadPage = 8, lastPosition = 320)

        assertEquals(5, flag.pageToOpen())
        assertEquals(0, flag.pagesToRead())
    }

    @Test
    fun `pagesToRead on a barely-read long topic`() {
        assertEquals(40, baseFlag.copy(totalPages = 41, lastReadPage = 1).pagesToRead())
    }

    @Test
    fun `pagesToRead excludes the first page when the last-read page is unset or negative`() {
        assertEquals(6, baseFlag.copy(totalPages = 7, lastReadPage = 0).pagesToRead())
        assertEquals(6, baseFlag.copy(totalPages = 7, lastReadPage = -1).pagesToRead())
    }

    @Test
    fun `pagesToRead is zero when totalPages is non-positive`() {
        assertEquals(0, baseFlag.copy(totalPages = 0, lastReadPage = 0).pagesToRead())
        assertEquals(0, baseFlag.copy(totalPages = Int.MIN_VALUE, lastReadPage = 0).pagesToRead())
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

    @Test
    fun `Flag lagTone drops below MEDIUM when the opened page leaves only two pages`() {
        val flag = baseFlag.copy(totalPages = 62, lastReadPage = 59, lastPosition = 2360)

        assertEquals(2, flag.pagesToRead())
        assertEquals(LagTone.LOW, flag.lagTone())
    }

    @Test
    fun `Flag lagTone drops below HIGH when the opened page leaves only nine pages`() {
        val flag = baseFlag.copy(totalPages = 69, lastReadPage = 59, lastPosition = 2360)

        assertEquals(9, flag.pagesToRead())
        assertEquals(LagTone.MEDIUM, flag.lagTone())
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
