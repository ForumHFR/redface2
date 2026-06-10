package fr.forumhfr.redface2.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [formatLastReplyTimestamp] (#325). One case per real source format
 * (REST listing vs search HTML), plus the strict passthrough fallback contract.
 */
class LastReplyFormattingTest {

    @Test
    fun `rest timestamp is reordered to the web form`() {
        // Format proven by rest_cat23_participated.json / RestFlagMappersTest.
        assertEquals("01-05-2026 à 17:07", formatLastReplyTimestamp("2026-05-01 17:07"))
    }

    @Test
    fun `rest timestamp keeps zero-padded fields verbatim`() {
        assertEquals("09-02-2026 à 16:26", formatLastReplyTimestamp("2026-02-09 16:26"))
    }

    @Test
    fun `search timestamp already in web form is returned unchanged`() {
        // Format produced by SearchResultParser.parseLastReply (NBSP already stripped).
        assertEquals("24-09-2025 à 06:48", formatLastReplyTimestamp("24-09-2025 à 06:48"))
    }

    @Test
    fun `unknown formats pass through untouched`() {
        assertEquals("hier à 06:48", formatLastReplyTimestamp("hier à 06:48"))
        // Date-only and seconds-bearing variants are NOT the proven REST shape: no guessing.
        assertEquals("2026-05-01", formatLastReplyTimestamp("2026-05-01"))
        assertEquals("2026-05-01 17:07:33", formatLastReplyTimestamp("2026-05-01 17:07:33"))
        // Partial match inside a longer string must not be rewritten either.
        assertEquals("le 2026-05-01 17:07", formatLastReplyTimestamp("le 2026-05-01 17:07"))
    }

    @Test
    fun `empty string passes through`() {
        assertEquals("", formatLastReplyTimestamp(""))
    }
}
