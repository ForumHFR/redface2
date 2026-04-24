package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `parse khakha opening page`() {
        val topic = parser.parse(fixture("topic_khakha_page_1.html"))

        assertEquals(13, topic.cat)
        assertEquals(84540, topic.post)
        assertEquals("[Topic Unique] Déféquer en toute sérénité, topic du kaka", topic.title)
        assertEquals(1, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(40, topic.posts.size)
        assertEquals(16625217, topic.posts.first().numreponse)
        assertEquals("Mora1651", topic.posts.first().author)
        assertTrue(topic.posts.any { post -> post.content.contains(":o") || post.content.contains("[:aloy]") })
    }

    @Test
    fun `parse khakha page with poll`() {
        val topic = parser.parse(fixture("topic_khakha_page_2.html"))

        assertEquals(2, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(41, topic.posts.size)
        assertEquals(16628071, topic.posts.first().numreponse)
        requireNotNull(topic.poll).also { poll ->
            assertEquals("Aimez-vous l'odeur de vos excréments?", poll.question)
            assertEquals(9, poll.options.size)
            assertTrue(poll.multipleChoice)
            assertEquals(176, poll.totalVotes)
            assertEquals("1. Non, c'est dégueu!", poll.options.first().text)
            assertEquals(34, poll.options.first().votes)
        }
    }

    @Test
    fun `parse khakha late page with nested quotes`() {
        val topic = parser.parse(fixture("topic_khakha_page_146.html"))

        assertEquals(146, topic.page)
        assertEquals(152, topic.totalPages)
        assertEquals(41, topic.posts.size)
        assertEquals(18085006, topic.posts.first().numreponse)
        assertTrue(topic.posts.any { post -> "justhynbrydhou" in post.quotedAuthors })
    }

    @Test
    fun `leave global post index unresolved when parsing an isolated topic page`() {
        val topic = parser.parse(fixture("topic_khakha_page_2.html"))

        assertNull(topic.posts.first().postIndex)
    }

    private fun fixture(name: String): String {
        return requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
    }
}
