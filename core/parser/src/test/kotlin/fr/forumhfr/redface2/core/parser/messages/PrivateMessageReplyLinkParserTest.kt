package fr.forumhfr.redface2.core.parser.messages

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #612 — the link parser extracts the real « Ajouter une réponse » `message.php` href off a
 * private-message conversation page so the write repository can follow it to the dedicated form
 * (the only one carrying the owner-only `newdest`). The href is forwarded VERBATIM — its
 * server-filled `numrep` / `ref` / `page` are never invented.
 */
class PrivateMessageReplyLinkParserTest {

    private val parser = PrivateMessageReplyLinkParser()

    @Test
    fun `extracts the message_php reply link from the repondre_form action`() {
        val link = parser.parse(fixture("private_message_dt_owner_thread.html"))

        assertTrue("a message.php reply link must be found", link != null)
        val resolved = requireNotNull(link)
        assertTrue("must target message.php", resolved.contains("message.php"))
        assertTrue("must carry cat=prive", resolved.contains("cat=prive"))
        // The server-controlled params are forwarded verbatim — NOT invented.
        assertTrue("must forward the thread id verbatim", resolved.contains("post=4242424"))
        assertTrue("must forward the prefilled numrep verbatim", resolved.contains("numrep=1990000111"))
        assertTrue("must forward ref verbatim", resolved.contains("ref=0"))
        assertTrue("must forward the page verbatim", resolved.contains("page=3"))
    }

    @Test
    fun `extracts the link from the repondre_gif anchor when the form action is absent`() {
        // A conversation page reduced to just the anchor (no repondre_form) still yields the link.
        val html = """
            <html><body>
            <a rel="nofollow"
               href="/message.php?config=hfr.inc&amp;cat=prive&amp;post=99&amp;numrep=12&amp;ref=0&amp;page=1&amp;p=1&amp;subcat=0&amp;sondage=0&amp;owntopic=0&amp;new=0"
               onclick="choper_reponse_rapide(0,0); return false;">
              <img src="https://forum-images.hardware.fr/themes_static/images_forum/1/repondre.gif" alt="Ajouter une réponse" />
            </a>
            </body></html>
        """.trimIndent()

        val link = parser.parse(html)

        assertTrue(link != null)
        assertTrue(requireNotNull(link).contains("post=99"))
        assertTrue(requireNotNull(link).contains("cat=prive"))
    }

    @Test
    fun `returns null when the conversation page exposes no reply link`() {
        // A read-only conversation (e.g. session lost the writable form) has no message.php link.
        val html = """
            <html><body>
            <table class="messagetable"><tr class="message"><td><div id="para1">Bonjour.</div></td></tr></table>
            <a href="/search.php?config=hfr.inc&amp;cat=prive&amp;subcat=0">Rechercher</a>
            </body></html>
        """.trimIndent()

        assertNull(parser.parse(html))
    }

    @Test
    fun `does not mistake the quick-reply bddpost form for a message_php link`() {
        // The forum2.php page also embeds a bddpost.php quick-reply form — that is NOT the link we
        // want (it carries no newdest). The parser keys on message.php only.
        val html = """
            <html><body>
            <form name="hop" action="/bddpost.php" method="post">
              <input type="hidden" name="cat" value="prive" />
              <input type="hidden" name="post" value="77" />
              <textarea name="content_form"></textarea>
            </form>
            </body></html>
        """.trimIndent()

        assertNull(parser.parse(html))
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            PrivateMessageReplyLinkParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
