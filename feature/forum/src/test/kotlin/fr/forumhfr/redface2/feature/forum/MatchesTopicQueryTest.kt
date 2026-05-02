package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.model.TopicSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper tests for [matchesTopicQuery]. The local search field calls into this
 * helper for every visible topic on every keystroke, so we pin its semantics here.
 */
class MatchesTopicQueryTest {

    @Test
    fun `blank query matches every topic`() {
        assertTrue(matchesTopicQuery(topic("Android", "alice", "bob"), ""))
        assertTrue(matchesTopicQuery(topic("Android", "alice", "bob"), "   "))
    }

    @Test
    fun `case-insensitive title match`() {
        val t = topic(title = "Android et iOS")
        assertTrue(matchesTopicQuery(t, "ANDROID"))
        assertTrue(matchesTopicQuery(t, "android"))
        assertTrue(matchesTopicQuery(t, "AnDrOiD"))
    }

    @Test
    fun `accent-insensitive title match folds NFD then strips combining marks`() {
        val t = topic(title = "Réflexion sur la batterie")
        assertTrue(matchesTopicQuery(t, "reflexion"))
        assertTrue(matchesTopicQuery(t, "RÉFLEXION"))
        assertTrue(matchesTopicQuery(t, "Réflexion"))
    }

    @Test
    fun `matches author and lastReplyAuthor`() {
        val t = topic(title = "fixed", author = "Charlie", lastAuthor = "Frédéric")
        assertTrue(matchesTopicQuery(t, "char"))
        assertTrue(matchesTopicQuery(t, "frederic"))
    }

    @Test
    fun `no match returns false`() {
        val t = topic(title = "Android", author = "alice", lastAuthor = "bob")
        assertFalse(matchesTopicQuery(t, "windows"))
        assertFalse(matchesTopicQuery(t, "zzz"))
    }

    private fun topic(
        title: String = "ignored",
        author: String = "ignored",
        lastAuthor: String = "ignored",
    ): TopicSummary = TopicSummary(
        cat = 23,
        subcat = null,
        topicId = 1,
        title = title,
        author = author,
        lastReplyAuthor = lastAuthor,
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
