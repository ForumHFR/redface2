package fr.forumhfr.redface2.core.parser.flags

import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagsListParserTest {

    private val parser = FlagsListParser()

    @Test
    fun `owntopic-1 fixture parses red flags with the expected unread breakdown`() {
        val flags = parser.parse(readFixture("flags_page_owntopic-1.html"), FlagType.RED)

        // Truth from the captured fixture (verified row-by-row):
        // - 39 tr.sujet rows total
        // - 37 rows with sujetCase5 = flag1.gif → RED + hasUnread
        // - 2 rows with sujetCase5 = favoris.gif → FAVORITE + hasUnread (legacy classification,
        //   icon wins over the listing's defaultType)
        // - 0 read rows in this view (all listed have unread posts)
        assertEquals(39, flags.size)

        val byType = flags.groupingBy { it.type }.eachCount()
        assertEquals(37, byType[FlagType.RED] ?: 0)
        assertEquals(2, byType[FlagType.FAVORITE] ?: 0)
        assertEquals(0, byType[FlagType.CYAN] ?: 0)
        assertTrue("expected every row of this view to be unread", flags.all { it.hasUnread })
    }

    @Test
    fun `owntopic-2 fixture parses cyan flags only`() {
        val flags = parser.parse(readFixture("flags_page_owntopic-2.html"), FlagType.CYAN)

        // 127 tr.sujet rows, all sujetCase5 = flag0.gif → CYAN + hasUnread.
        assertEquals(127, flags.size)
        assertTrue("expected only cyan flags", flags.all { it.type == FlagType.CYAN })
        assertTrue("expected every row to be unread", flags.all { it.hasUnread })
    }

    @Test
    fun `owntopic-3 fixture surfaces both unread favorites and read entries`() {
        val flags = parser.parse(readFixture("flags_page_owntopic-3.html"), FlagType.FAVORITE)

        // 6 rows total. sujetCase5 carries an icon only on the 2 unread favorites; the 4 read
        // rows have an empty sujetCase5 (`&nbsp;`) and rely on sujetCase1's closed.gif marker
        // for the "no new posts" signal. Type defaults to FAVORITE (the listing's filter).
        assertEquals(6, flags.size)
        assertTrue("expected only favorite flags", flags.all { it.type == FlagType.FAVORITE })
        assertEquals(2, flags.count { it.hasUnread })
        assertEquals(4, flags.count { !it.hasUnread })
    }

    @Test
    fun `each parsed flag has a non-blank title and a positive topicId`() {
        val all = listOf(
            "flags_page_owntopic-1.html" to FlagType.RED,
            "flags_page_owntopic-2.html" to FlagType.CYAN,
            "flags_page_owntopic-3.html" to FlagType.FAVORITE,
        ).flatMap { (name, type) -> parser.parse(readFixture(name), type) }

        assertTrue("expected at least one flag across all fixtures", all.isNotEmpty())
        all.forEach { flag ->
            assertTrue("blank title in flag $flag", flag.title.isNotBlank())
            assertTrue("non-positive topicId in flag $flag", flag.topicId > 0)
            assertTrue("non-positive cat in flag $flag", flag.cat > 0)
            assertTrue("non-positive lastReadPage in flag $flag", flag.lastReadPage >= 1)
        }
    }

    @Test
    fun `domotique row in owntopic-1 fixture is parsed with all fields`() {
        // Anchored sample test against the real fixture — a refactor that breaks field
        // extraction is caught at the value level, not just at the count level.
        val flags = parser.parse(readFixture("flags_page_owntopic-1.html"), FlagType.RED)
        val domotique = flags.first { it.topicId == 5 }

        assertEquals("[Topic unique] La domotique, maison connectée et intelligente", domotique.title)
        assertEquals(30, domotique.cat)
        assertEquals(573, domotique.subcat)
        assertEquals(1726, domotique.totalPages)
        assertEquals(69_017, domotique.replyCount)
        assertEquals(7_840_061, domotique.views)
        assertEquals(FlagType.RED, domotique.type)
        assertTrue("expected unread", domotique.hasUnread)
        assertEquals(1725, domotique.lastReadPage)
        assertEquals(489_112L, domotique.firstUnreadPostId)
        assertEquals("lazer127", domotique.firstPostAuthor)
        assertEquals("Shaad", domotique.lastReplyAuthor)
    }

    @Test
    fun `read favorite row in owntopic-3 has hasUnread false and no firstUnreadPostId`() {
        val flags = parser.parse(readFixture("flags_page_owntopic-3.html"), FlagType.FAVORITE)
        val readFavorite = flags.first { !it.hasUnread }

        assertEquals(FlagType.FAVORITE, readFavorite.type)
        assertEquals(0L, readFavorite.firstUnreadPostId)
    }

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
