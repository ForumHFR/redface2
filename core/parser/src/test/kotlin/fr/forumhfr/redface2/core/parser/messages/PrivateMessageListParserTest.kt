package fr.forumhfr.redface2.core.parser.messages

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessageListParserTest {

    private val parser = PrivateMessageListParser()

    @Test
    fun `real production fixture with 50 read MPs returns 0 unread`() {
        // The legacy Redface v1 captured this HTML from forum1.php?cat=prive in 2015 with
        // 50 MPs, all already read. Same DOM shape today — HFR has not redesigned the
        // listing layout since. Correspondent pseudos and subjects have been scrubbed to
        // placeholders (privacy); the structure, icons and dates are untouched.
        val html = readFixture("private_messages_list_all_read.html")

        assertEquals(0, parser.countUnread(html))
    }

    @Test
    fun `inline HTML with mixed read and unread icons counts only the closedbp rows`() {
        // Minimal hand-written HTML, NOT a fixture — validates the positive path of the
        // parser since the 2015 fixture only contains read MPs. The structure mirrors
        // the real HFR listing (table > tr.sujet > td.sujetCase1 > img[src]). The <table>
        // wrapper is required: Jsoup strips bare <tr> elements outside a table context.
        val html = """
            <html><body><table>
            <tr class="sujet ligne_booleen ligne_pair">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedp.gif" /></td>
            </tr>
            <tr class="sujet ligne_booleen ligne_impair">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedbp.gif" /></td>
            </tr>
            <tr class="sujet ligne_booleen ligne_pair">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedbp.gif" /></td>
            </tr>
            <tr class="sujet ligne_booleen ligne_impair">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedp.gif" /></td>
            </tr>
            </table></body></html>
        """.trimIndent()

        assertEquals(2, parser.countUnread(html))
    }

    @Test
    fun `empty inbox HTML returns 0`() {
        val html = "<html><body><p>Aucun message privé.</p></body></html>"

        assertEquals(0, parser.countUnread(html))
    }

    @Test
    fun `non-MP rows are ignored even if their icons happen to share the closedbp name`() {
        // Defensive: if a non-sujet row referenced a closedbp.gif (shouldn't happen on HFR,
        // but the parser must not over-count). Only tr.sujet rows count. <table> wrapper
        // required so Jsoup keeps the <tr> nodes.
        val html = """
            <html><body><table>
            <tr class="something_else">
              <td><img src="/themes_static/images/silk/closedbp.gif" /></td>
            </tr>
            <tr class="sujet ligne_booleen ligne_pair">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedp.gif" /></td>
            </tr>
            </table></body></html>
        """.trimIndent()

        assertEquals(0, parser.countUnread(html))
    }

    @Test
    fun `parseList reads every conversation row of the real inbox fixture`() {
        val html = readFixture("private_messages_list_all_read.html")

        val page = parser.parseList(html)

        assertEquals(50, page.items.size)
        // The fixture pager links pages 2 and 3 from page 1.
        assertEquals(1, page.page)
        assertEquals(3, page.totalPages)
        // All 50 MPs are read in this fixture — consistent with countUnread == 0.
        assertTrue(page.items.none { it.hasUnread })
        assertTrue(page.items.all { it.threadId > 0 })
    }

    @Test
    fun `parseList extracts thread id, date and scrubbed metadata of the first row`() {
        val html = readFixture("private_messages_list_all_read.html")

        val first = parser.parseList(html).items.first()

        // Thread id and date are NOT scrubbed (not private) — assert them exactly.
        assertEquals(2338087, first.threadId)
        // 22-07-2015 13:19 Europe/Paris (CEST, UTC+2) == 11:19 UTC.
        assertEquals(Instant.parse("2015-07-22T11:19:00Z"), first.date)
        assertEquals(false, first.hasUnread)
        // Correspondent + subject were scrubbed to deterministic placeholders.
        assertTrue(first.correspondent.startsWith("Correspondant"))
        assertTrue(first.subject.startsWith("Sujet de test"))
    }

    @Test
    fun `parseList flags an unread conversation and parses its thread id`() {
        // Hand-written row exercising the unread (closedbp) path with the full cell layout,
        // since the real fixture is all-read.
        val html = """
            <html><body><table>
            <tr class="sujet ligne_booleen">
              <td class="sujetCase1"><img src="/themes_static/images/silk/closedbp.gif" /></td>
              <td class="sujetCase3"><a href="/forum2.php?config=hfr.inc&cat=prive&post=987654&page=1" class="cCatTopic">Sujet de test</a></td>
              <td class="sujetCase6"><a href="/profilebdd.php?pseudo=Correspondant">Correspondant</a></td>
              <td class="sujetCase9"><a href="#bas">10-02-2026&nbsp;à&nbsp;09:05<br /><b>Correspondant</b></a></td>
            </tr>
            </table></body></html>
        """.trimIndent()

        val first = parser.parseList(html).items.single()

        assertEquals(987654, first.threadId)
        assertEquals("Correspondant", first.correspondent)
        assertEquals("Sujet de test", first.subject)
        assertTrue(first.hasUnread)
        assertEquals(Instant.parse("2026-02-10T08:05:00Z"), first.date)
    }

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
