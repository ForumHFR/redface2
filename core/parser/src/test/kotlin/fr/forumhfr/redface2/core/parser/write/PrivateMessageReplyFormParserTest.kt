package fr.forumhfr.redface2.core.parser.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #301 — proves the shared [ReplyFormParser] parses HFR's private-message reply form (the
 * `bddpost.php` form embedded in a `cat=prive` conversation page) without any topic-specific
 * assumption. Pinned against the real, scrubbed fixture `private_message_thread.html` captured for
 * #298. The two contract bits that make a private reply different from a topic reply are asserted
 * explicitly: `cat=prive` (a String, not a numeric id) and the server-prefilled `numrep` (the last
 * post of the page — NOT a quote reference) must be carried verbatim in the hidden fields.
 */
class PrivateMessageReplyFormParserTest {

    private val parser = ReplyFormParser()

    @Test
    fun `parses the private-message reply form embedded in the conversation page`() {
        val form = parser.parse(fixture("private_message_thread.html")).getOrThrow()

        assertFalse("authenticated MP form is never anonymous", form.isAnonymous)
        assertEquals("0", form.hashCheck)
        // A fresh reply has an empty composer textarea (no quote prefill).
        assertEquals("", form.initialContent)

        // Private-message routing lives entirely in the hidden fields, forwarded verbatim on POST.
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("3195237", form.hiddenFields["post"])
        // numrep is the conversation's last-post id HFR prefilled — must be preserved as-is.
        assertEquals("1980677227", form.hiddenFields["numrep"])
        assertEquals("0", form.hiddenFields["subcat"])
        assertEquals("1", form.hiddenFields["page"])
        assertEquals("hfr.inc", form.hiddenFields["config"])
        assertEquals("1100", form.hiddenFields["verifrequet"])
        // Authenticated form echoes the user's pseudo; never a password.
        assertEquals("TestUser", form.hiddenFields["pseudo"])
        assertFalse("password must never be collected", form.hiddenFields.containsKey("password"))
        // sujet is exposed both via the typed field and the hidden map.
        assertEquals("Sujet prive de test", form.sujet)
        // The quick-reply form carries `signature=1` as a hidden input (not a checkbox), so it lands
        // in the hidden fields rather than ReplyForm.options — the ViewModel hydrates from both.
        assertEquals("1", form.hiddenFields["signature"])
        assertTrue("hidden fields should not be empty", form.hiddenFields.isNotEmpty())
    }

    @Test
    fun `a one-to-one MP form is not an owner-managed DT (no newdest)`() {
        val form = parser.parse(fixture("private_message_thread.html")).getOrThrow()

        // #606 — a regular conversation has no `newdest` field, so the user can't manage members.
        assertFalse("a one-to-one MP exposes no member editor", form.canManageRecipients)
        assertEquals(null, form.manageableRecipients)
    }

    @Test
    fun `an owner DT form exposes the prefilled member CSV via newdest`() {
        val form = parser.parse(fixture("private_message_dt_owner_reply_form.html")).getOrThrow()

        assertFalse("authenticated owner form is never anonymous", form.isAnonymous)
        // #606 — HFR serves `newdest` only to the owner ; the parser already collects it verbatim.
        assertTrue("owner DT form must expose the member editor", form.canManageRecipients)
        assertEquals(
            "alice, bob, Bébé Yoda, stitch+, Administration",
            form.manageableRecipients,
        )
        // The owner-only DT carries owntopic=1 and cat=prive, forwarded verbatim on POST.
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("1", form.hiddenFields["owntopic"])
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            PrivateMessageReplyFormParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
