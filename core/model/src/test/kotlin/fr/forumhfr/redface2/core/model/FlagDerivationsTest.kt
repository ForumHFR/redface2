package fr.forumhfr.redface2.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 100% coverage of the pure [Flag] derivations shared across layers (#603, ADR-017): [pagesToRead]
 * and [effectiveFlagColor]. Moved here from `:feature:flags` so the single source of truth is tested
 * where it lives.
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
