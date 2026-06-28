package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #676 v2 — pure page-target helpers of the long-press sheet's navigation actions (Ouvrir / 1er
 * non-lu / Ouvrir à la dernière page). Covers the bounds Codex asked to lock.
 */
class FlagSheetPagesTest {

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
    fun `first unread is the page after the last-read one when there is unread`() {
        assertEquals(35, flagFirstUnreadPage(lastReadPage = 34, totalPages = 35, hasUnread = true))
    }

    @Test
    fun `first unread is clamped to the last page`() {
        // lastReadPage == totalPages with unread: lastReadPage+1 must not exceed totalPages.
        assertEquals(35, flagFirstUnreadPage(lastReadPage = 35, totalPages = 35, hasUnread = true))
    }

    @Test
    fun `first unread falls back to the resume page when nothing is unread`() {
        // The action is disabled in this case, but the computed page must still be the safe resume page.
        assertEquals(20, flagFirstUnreadPage(lastReadPage = 20, totalPages = 35, hasUnread = false))
    }

    @Test
    fun `first unread stays at least 1 on an empty topic`() {
        assertEquals(1, flagFirstUnreadPage(lastReadPage = 0, totalPages = 0, hasUnread = true))
    }
}
