package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.model.TopicSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for [matchesHighlightedTitle] — the #206 « Exact post-création »
 * workaround. HFR redirects a successful create to the category listing and never returns
 * the new topic id (#214), so the listing highlights the freshly-created row by exact-title
 * match. These tests pin the contract : one-shot, exact (trimmed, case-insensitive), zero
 * false positive, and degrades to "no highlight" when no title is carried.
 */
class MatchesHighlightedTitleTest {

    @Test
    fun `exact title matches`() {
        assertTrue(matchesHighlightedTitle(topic("Mon nouveau sujet"), "Mon nouveau sujet"))
    }

    @Test
    fun `match is case-insensitive`() {
        val t = topic("Débat Kotlin vs Java")
        assertTrue(matchesHighlightedTitle(t, "débat kotlin vs java"))
        assertTrue(matchesHighlightedTitle(t, "DÉBAT KOTLIN VS JAVA"))
    }

    @Test
    fun `both sides are trimmed before comparison`() {
        // HFR strips surrounding whitespace on the stored title ; the posted subject may
        // still carry trailing spaces from the form field. Trim both so they still match.
        assertTrue(matchesHighlightedTitle(topic("  Sujet espacé  "), "Sujet espacé"))
        assertTrue(matchesHighlightedTitle(topic("Sujet espacé"), "   Sujet espacé   "))
    }

    @Test
    fun `null or blank highlight title never matches`() {
        val t = topic("Mon nouveau sujet")
        assertFalse(matchesHighlightedTitle(t, null))
        assertFalse(matchesHighlightedTitle(t, ""))
        assertFalse(matchesHighlightedTitle(t, "    "))
    }

    @Test
    fun `substring is not a match (zero false positive)`() {
        // A `contains` match would wrongly highlight an older topic whose title is a prefix
        // of the new one. Exact match is what guarantees the one-shot precision #206 wants.
        assertFalse(matchesHighlightedTitle(topic("Test"), "Test de la nouvelle feature"))
        assertFalse(matchesHighlightedTitle(topic("Test de la nouvelle feature"), "Test"))
    }

    @Test
    fun `different title does not match`() {
        assertFalse(matchesHighlightedTitle(topic("Autre sujet"), "Mon nouveau sujet"))
    }

    private fun topic(title: String): TopicSummary = TopicSummary(
        cat = 23,
        subcat = null,
        topicId = 1,
        title = title,
        author = "ignored",
        lastReplyAuthor = "ignored",
        lastReplyAt = "",
        replyCount = 0,
        totalPages = 1,
        isSticky = false,
        isLocked = false,
        hasUnread = null,
        lastReadPage = null,
        lastPostReadId = null,
        flagType = null,
    )
}
