package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicPageParserTest {
    private val parser = TopicPageParser()

    @Test
    fun `parse multipage topic details`() {
        val topic = parser.parse(fixture("topic_page_multipage.html"))

        assertEquals(23, topic.cat)
        assertEquals(21748, topic.post)
        assertEquals("[Projet] HFR4droid 0.8.6 - 10k downloads, merci à tous", topic.title)
        assertEquals(1, topic.page)
        assertEquals(419, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(520051, topic.posts.first().numreponse)
        assertEquals("ToYonos", topic.posts.first().author)
    }

    @Test
    fun `parse single page topic details`() {
        val topic = parser.parse(fixture("topic_page_single.html"))

        assertEquals(1, topic.cat)
        assertEquals(999395, topic.post)
        assertEquals("S'allume difficilement et garde les actions du bouton de démarrage!", topic.title)
        assertEquals(1, topic.page)
        assertEquals(1, topic.totalPages)
        assertTrue(topic.posts.isNotEmpty())
    }

    @Test
    fun `parse forty posts from topic page`() {
        val topic = parser.parse(fixture("topic_posts_page.html"))

        assertEquals(23, topic.cat)
        assertEquals(29169, topic.post)
        assertEquals(1, topic.page)
        assertEquals(12, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(1885523, topic.posts.first().numreponse)
        assertTrue(topic.posts.first().author.isNotBlank())
    }

    @Test
    fun `extract quoted authors from nested citations`() {
        val topic = parser.parse(fixture("topic_page_multipage.html"))

        assertTrue(topic.posts.any { post -> "Origan" in post.quotedAuthors })
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
