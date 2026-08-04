package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #676 v2 — pure page-target helpers of the long-press sheet's navigation actions (Ouvrir / 1er
 * non-lu / Ouvrir à la dernière page). Covers the bounds Codex asked to lock.
 *
 * #638 — « 1er non-lu » now delegates to `Flag.pageToOpen()`, so its cases take a whole [Flag]:
 * the previous `lastReadPage + 1` skipped posts when the user had stopped mid-page. The boundary
 * rule itself is covered exhaustively by `FlagPageToOpenTest` in `:core:model`; what stays here is
 * the sheet's own contract.
 */
class FlagSheetPagesTest {

    private fun flag(
        lastReadPage: Int,
        totalPages: Int,
        hasUnread: Boolean,
        lastPosition: Int? = null,
    ) = Flag(
        cat = 1,
        subcat = null,
        topicId = 1,
        title = "t",
        totalPages = totalPages,
        replyCount = 0,
        type = FlagType.CYAN,
        hasUnread = hasUnread,
        lastReadPage = lastReadPage,
        lastPostReadId = null,
        lastPosition = lastPosition,
        firstPostAuthor = "a",
        lastReplyAuthor = "b",
        lastReplyAt = "2026-08-04 00:00",
    )

    @Test
    fun `last-read page is never below 1`() {
        assertEquals(1, flagLastReadPage(0))
        assertEquals(1, flagLastReadPage(-3))
        assertEquals(7, flagLastReadPage(7))
    }

    @Test
    fun `last page is never below 1`() {
        assertEquals(1, flagLastPage(0))
        assertEquals(35, flagLastPage(35))
    }

    @Test
    fun `first unread is the page after the last-read one when it ended on a page boundary`() {
        // 1360 = 34 × 40 : the last-read post was the last of page 34, so page 35 is the first unread.
        assertEquals(
            35,
            flagFirstUnreadPage(flag(lastReadPage = 34, totalPages = 35, hasUnread = true, lastPosition = 1360)),
        )
    }

    @Test
    fun `first unread does NOT skip unread posts left on the last-read page (#638)`() {
        // 1359 is mid-page 34: post 1360 is still unread, so the old lastReadPage+1 lost it.
        assertEquals(
            34,
            flagFirstUnreadPage(flag(lastReadPage = 34, totalPages = 35, hasUnread = true, lastPosition = 1359)),
        )
    }

    @Test
    fun `first unread is clamped to the last page`() {
        // lastReadPage == totalPages with unread: it must not exceed totalPages.
        assertEquals(
            35,
            flagFirstUnreadPage(flag(lastReadPage = 35, totalPages = 35, hasUnread = true, lastPosition = 1400)),
        )
    }

    @Test
    fun `first unread falls back to the resume page when nothing is unread`() {
        // The action is disabled in this case, but the computed page must still be the safe resume page.
        assertEquals(20, flagFirstUnreadPage(flag(lastReadPage = 20, totalPages = 35, hasUnread = false)))
    }

    @Test
    fun `first unread stays at least 1 on an empty topic`() {
        assertEquals(1, flagFirstUnreadPage(flag(lastReadPage = 0, totalPages = 0, hasUnread = true)))
    }

    // #15 — « Aller à une page » input parsing.

    @Test
    fun `page input accepts a valid in-range page`() {
        assertEquals(12, parseTopicPageInput("12", totalPages = 35))
        assertEquals(1, parseTopicPageInput(" 1 ", totalPages = 35))
        assertEquals(35, parseTopicPageInput("35", totalPages = 35))
    }

    @Test
    fun `page input rejects out-of-range, zero, empty and non-numeric`() {
        assertEquals(null, parseTopicPageInput("36", totalPages = 35))
        assertEquals(null, parseTopicPageInput("0", totalPages = 35))
        assertEquals(null, parseTopicPageInput("-2", totalPages = 35))
        assertEquals(null, parseTopicPageInput("", totalPages = 35))
        assertEquals(null, parseTopicPageInput("abc", totalPages = 35))
    }

    @Test
    fun `page input treats totalPages below 1 as a single page`() {
        assertEquals(1, parseTopicPageInput("1", totalPages = 0))
        assertEquals(null, parseTopicPageInput("2", totalPages = 0))
    }
}
