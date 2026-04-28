package fr.forumhfr.redface2.core.parser.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateMessageListParserTest {

    private val parser = PrivateMessageListParser()

    @Test
    fun `real production fixture with 50 read MPs returns 0 unread`() {
        // The legacy Redface v1 captured this HTML from forum1.php?cat=prive in 2015 with
        // 50 MPs, all already read. Same DOM shape today — HFR has not redesigned the
        // listing layout since.
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

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
