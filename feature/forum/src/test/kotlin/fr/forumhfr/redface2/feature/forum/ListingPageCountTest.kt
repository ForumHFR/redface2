package fr.forumhfr.redface2.feature.forum

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [listingPageCount] — the pure helper that drives whether the
 * "Suivant" pager button stays enabled. Bug #4 in the PR review was that the
 * pager always allowed "Suivant" because no max was computed; this test pins the
 * arithmetic so the regression cannot come back.
 */
class ListingPageCountTest {

    @Test
    fun `exact division yields the quotient`() {
        assertEquals(2, listingPageCount(totalTopics = 100, resultsPerPage = 50))
    }

    @Test
    fun `inexact division rounds up`() {
        // 130 / 50 = 2 remainder 30 → 3 pages so the last 30 topics still get a slot.
        assertEquals(3, listingPageCount(totalTopics = 130, resultsPerPage = 50))
    }

    @Test
    fun `single topic still yields one page`() {
        assertEquals(1, listingPageCount(totalTopics = 1, resultsPerPage = 50))
    }

    @Test
    fun `empty listing collapses to one page`() {
        assertEquals(1, listingPageCount(totalTopics = 0, resultsPerPage = 50))
    }

    @Test
    fun `non-positive resultsPerPage falls back to one page`() {
        // Defensive — REST has never sent 0 here, but we'd rather render Page 1 / 1
        // than divide-by-zero or negative-page weirdness.
        assertEquals(1, listingPageCount(totalTopics = 130, resultsPerPage = 0))
    }
}
