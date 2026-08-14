package fr.forumhfr.redface2.core.parser.messages

import fr.forumhfr.redface2.core.model.postContentExcerpt
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterizes the private-thread parser against one sanitized, authenticated HFR capture.
 *
 * Positive coverage is deliberately limited to what `private_message_thread.html` contains: four
 * genuine message rows with anchors, authors, dates, avatars, profile links, quote-action `ref`
 * values, edit-link ownership and placeholder bodies, plus the thread metadata asserted below.
 *
 * The fixture contains NO `span.signature`, `quote_only=1` citation counter, `div.edited` trailer,
 * `table.citation` or `table.oldcitation`. Its two `quote_only=0` URLs are page-level filter/print
 * navigation, not citation counters. Therefore this class intentionally does not assert `null` or
 * empty values for `signature`, `citedCount`, `editedAt` or quoted content: such assertions would
 * only pin the fixture's absence and could be mistaken for positive parser support. A separate real,
 * sanitized fixture containing each construct is required to characterize those capabilities.
 */
class PrivateMessageThreadParserTest {

    private val parser = PrivateMessageThreadParser()

    // Real conversation captured live from forum2.php?cat=prive (account XaTriX), scrubbed:
    // the current user is renamed "TestUser", the other participant "Correspondant", the
    // subject and every message body are placeholders. The DOM structure, toolbars, dates,
    // cryptlink-obfuscated edit links and pager are untouched so the parser is exercised
    // against the genuine HFR layout.
    private val html: String by lazy { readFixture("private_message_thread.html") }

    @Test
    fun `parses thread metadata from the real cat=prive fixture`() {
        val thread = parser.parse(html)

        assertEquals(3195237, thread.threadId)
        assertEquals("Sujet prive de test", thread.subject)
        assertEquals(1, thread.page)
        assertEquals(1, thread.totalPages)
        assertEquals(4, thread.messages.size)
        // The bddpost reply form is present in an authenticated, non-locked conversation.
        assertTrue(thread.canReply)
    }

    @Test
    fun `derives the correspondent from the first message not authored by the current user`() {
        val thread = parser.parse(html)

        // 3 messages are the current user's (edit link present → isOwnPost), the 4th is the
        // correspondent's (no edit link). The correspondent is that first non-own author.
        assertEquals("Correspondant", thread.correspondent)
    }

    @Test
    fun `reuses the shared post extractor for the fixture's proven message fields`() {
        val messages = parser.parse(html).messages

        assertEquals(
            listOf(1980664234, 1980664235, 1980677218, 1980677227),
            messages.map { it.numreponse },
        )
        assertEquals(
            listOf("TestUser", "TestUser", "TestUser", "Correspondant"),
            messages.map { it.author },
        )
        assertEquals(
            listOf(
                Instant.parse("2026-05-24T12:29:15Z"),
                Instant.parse("2026-05-24T12:29:58Z"),
                Instant.parse("2026-06-03T16:55:48Z"),
                Instant.parse("2026-06-03T17:01:40Z"),
            ),
            messages.map { it.date },
        )
        assertEquals(
            listOf(
                "https://forum-images.hardware.fr/images/mesdiscussions-990002.png",
                "https://forum-images.hardware.fr/images/mesdiscussions-990002.png",
                "https://forum-images.hardware.fr/images/mesdiscussions-990002.png",
                "https://forum-images.hardware.fr/images/mesdiscussions-990001.jpg",
            ),
            messages.map { it.avatarUrl },
        )
        // The ids were consistently remapped while scrubbing the private capture; their presence
        // and per-author stability, rather than the fictitious values themselves, are the contract.
        assertEquals(listOf(990002, 990002, 990002, 990001), messages.map { it.profileId })
        // The clear quote-action hrefs carry the real page-local ranks even though the adjacent
        // single-quote icons are cryptlink-obfuscated.
        assertEquals(listOf(1, 2, 3, 4), messages.map { it.quoteRef })
        // Own messages expose the toolbar edit link (recovered from its cryptlink span);
        // the correspondent's does not.
        assertEquals(listOf(true, true, true, false), messages.map { it.isEditable })
        assertEquals(listOf(true, true, true, false), messages.map { it.isOwnPost })
        assertEquals(
            List(4) { "Message prive de test (contenu remplace)." },
            messages.map { postContentExcerpt(it.content) },
        )
    }

    @Test
    fun `prefers the correspondent revealed by the page over the inbox fallback`() {
        // When the thread page DOES contain a message from the other participant, that author
        // wins over whatever the inbox row carried.
        val thread = parser.parse(html, fallbackCorrespondent = "InboxValue")

        assertEquals("Correspondant", thread.correspondent)
    }

    @Test
    fun `falls back to the inbox correspondent when every message is the user's own`() {
        // Inline stub (NOT a prod fixture): a single own message (toolbar edit link present →
        // isOwnPost). The correspondent has not replied, so `firstOrNull { !isOwnPost }` is null
        // and the inbox-row value must win.
        val thread = parser.parse(ownOnlyThreadHtml(), fallbackCorrespondent = "InboxOnly")

        assertEquals("InboxOnly", thread.correspondent)
    }

    @Test
    fun `falls back to the first author when own-only and no inbox correspondent`() {
        // Deep-link directly to a thread (no inbox row) where the user is the only sender:
        // last resort is the first message author.
        val thread = parser.parse(ownOnlyThreadHtml(), fallbackCorrespondent = null)

        assertEquals("TestUser", thread.correspondent)
    }

    @Test
    fun `one-to-one thread is not flagged multi-recipient`() {
        // Real fixture: 3 own messages + 1 from the correspondent = a SINGLE distinct non-own
        // author. A 1:1 MP must never be flagged multi-recipient.
        assertFalse(parser.parse(html).isMultiRecipient)
    }

    @Test
    fun `thread with two distinct non-own authors is flagged multi-recipient`() {
        // Inline stub (NOT a prod fixture): two messages from two different other-than-current
        // users (no toolbar edit link → not own). Two distinct non-own authors prove a MultiMP.
        val thread = parser.parse(multiRecipientThreadHtml())

        assertTrue(thread.isMultiRecipient)
    }

    private fun multiRecipientThreadHtml(): String =
        """
        <html><body>
        <input type="hidden" name="cat" value="prive" />
        <input type="hidden" name="post" value="777" />
        <table class="messagetable"><tr>
          <td class="messCase1"><a name="t1"></a><b class="s2">Alpha</b></td>
          <td>
            <div class="toolbar"><div class="left">Posté le 01-02-2026 à 10:00:00</div></div>
            <div id="para1">Message un.</div>
          </td>
        </tr></table>
        <table class="messagetable"><tr>
          <td class="messCase1"><a name="t2"></a><b class="s2">Beta</b></td>
          <td>
            <div class="toolbar"><div class="left">Posté le 01-02-2026 à 10:05:00</div></div>
            <div id="para2">Message deux.</div>
          </td>
        </tr></table>
        </body></html>
        """.trimIndent()

    private fun ownOnlyThreadHtml(): String =
        """
        <html><body>
        <input type="hidden" name="cat" value="prive" />
        <input type="hidden" name="post" value="555" />
        <table class="messagetable"><tr>
          <td class="messCase1"><a name="t1"></a><b class="s2">TestUser</b></td>
          <td>
            <div class="toolbar"><div class="left">Posté le 01-02-2026 à 10:01:00
              <a href="/hfr/prive/editer-1-1-1.htm">Modifier</a></div></div>
            <div id="para1">Message privé de test.</div>
          </td>
        </tr></table>
        </body></html>
        """.trimIndent()

    private fun readFixture(name: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture: fixtures/$name")
        return resource.bufferedReader().use { it.readText() }
    }
}
