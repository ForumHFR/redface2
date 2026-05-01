package fr.forumhfr.redface2.core.parser.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicListParserTest {

    private val parser = TopicListParser()

    @Test
    fun `page1 fixture surfaces cat=13 with subcat null and at least 5 sticky topics`() {
        val page = parser.parse(readFixture("topic_list_cat13_subcat0_page1.html"))

        assertEquals(13, page.cat)
        assertNull("subcat=0 in the request URL must surface as null in the model", page.subcat)
        assertEquals(1, page.currentPage)
        assertTrue(
            "Discussions has hundreds of pages — totalPages must reflect the highest pagination link",
            page.totalPages >= 100,
        )
        val stickyCount = page.topics.count { it.isSticky }
        assertTrue("expected at least 5 sticky topics on page 1, got $stickyCount", stickyCount >= 5)
    }

    @Test
    fun `Marc nous a quittes sticky topic is parsed with post=121557 and isSticky=true`() {
        val page = parser.parse(readFixture("topic_list_cat13_subcat0_page1.html"))
        val marc = page.topics.firstOrNull { it.post == 121557 }

        assertNotNull("Sticky topic post=121557 must be present in page 1 fixture", marc)
        assertTrue("post=121557 must be sticky", marc!!.isSticky)
        assertTrue("title must mention Marc", marc.title.contains("Marc"))
        // We don't pin the exact replyCount/views to keep the fixture replaceable —
        // capturing the page later would update those counts. Just assert non-zero.
        assertTrue(marc.replyCount > 0)
        assertTrue(marc.views > 0)
        assertTrue("totalPages must be at least 1", marc.totalPages >= 1)
    }

    @Test
    fun `page20 fixture surfaces currentPage=20 with no sticky in the topics list`() {
        val page = parser.parse(readFixture("topic_list_cat13_subcat0_page20.html"))

        assertEquals(13, page.cat)
        assertEquals(20, page.currentPage)
        assertTrue("totalPages on page 20 must remain >= 100", page.totalPages >= 100)
        assertFalse(
            "Page 20 must not surface any sticky topic (sticky only appear on page 1)",
            page.topics.any { it.isSticky },
        )
    }

    @Test
    fun `every parsed topic has a non-blank title and non-negative counters`() {
        val pages = listOf(
            "topic_list_cat13_subcat0_page1.html",
            "topic_list_cat13_subcat0_page20.html",
        ).map { parser.parse(readFixture(it)) }

        pages.flatMap { it.topics }.forEach { topic ->
            assertTrue("blank title in $topic", topic.title.isNotBlank())
            assertTrue("post must be > 0 in $topic", topic.post > 0)
            assertTrue("replyCount must be >= 0 in $topic", topic.replyCount >= 0)
            assertTrue("views must be >= 0 in $topic", topic.views >= 0)
            assertTrue("totalPages must be >= 1 in $topic", topic.totalPages >= 1)
            assertEquals("cat must be 13 (mirrors the fixture URL)", 13, topic.cat)
        }
    }

    @Test
    fun `anonymous capture surfaces every row as hasUnread=true via closedb_new gif`() {
        // The captured fixtures are anonymous fetches: HFR serves `closedb_new.gif`
        // ("Nouveaux sujets") for every row regardless of read state, since there's no
        // session to attribute the read state to. This test pins the contract — the
        // boolean is correctly extracted, even if it's not meaningful in this capture.
        val page = parser.parse(readFixture("topic_list_cat13_subcat0_page1.html"))
        assertTrue("expected every row to be marked unread for an anonymous capture", page.topics.all { it.hasUnread })
    }

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
