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
    fun `the forum2 quick-reply form embedded in the conversation page carries no newdest`() {
        // #612 contract lock : the conversation page (forum2.php) embeds a bddpost.php QUICK-REPLY
        // form that NEVER carries `newdest` — even for an owner. This is exactly why the repository
        // must follow the page's message.php link instead of parsing this embedded form. Parsing it
        // would always yield canManageRecipients=false and break the #606 editor / #612 roster.
        val form = parser.parse(fixture("private_message_thread.html")).getOrThrow()

        assertFalse("the embedded quick-reply form exposes no member editor", form.canManageRecipients)
        assertEquals(null, form.manageableRecipients)
    }

    @Test
    fun `an owner DT message_php form exposes the prefilled member CSV via newdest`() {
        // #612 — sourced from the dedicated message.php reply form (the one the repository follows),
        // NOT from the forum2.php quick-reply above.
        val form = parser.parse(fixture("private_message_dt_owner_reply_form.html")).getOrThrow()

        assertFalse("authenticated owner form is never anonymous", form.isAnonymous)
        // #606 — HFR serves `newdest` only to the owner ; the parser already collects it verbatim.
        assertTrue("owner DT form must expose the member editor", form.canManageRecipients)
        assertEquals(
            "alice, bob, Bébé Yoda, stitch+, Administration",
            form.manageableRecipients,
        )
        // The owner-only DT carries owntopic=1 and cat=prive, forwarded verbatim on POST. The
        // message.php form also carries the server-filled numrep / ref / page verbatim.
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("1", form.hiddenFields["owntopic"])
        assertEquals("1990000111", form.hiddenFields["numrep"])
        assertEquals("0", form.hiddenFields["ref"])
        assertEquals("3", form.hiddenFields["page"])
        // Fresh hash_check from message.php + never a password.
        assertEquals("TESTHASH", form.hashCheck)
        assertFalse("password must never be collected", form.hiddenFields.containsKey("password"))
        // The message.php form renders options as real checkboxes — signature is the checked default.
        assertTrue("signature is the checked default on the message.php form", form.options.signatureEnabled)
        assertEquals("1", form.msgIcon)
        // #618 — the owner roster mirrors the editable newdest value verbatim.
        assertEquals("alice, bob, Bébé Yoda, stitch+, Administration", form.recipientsRoster)
    }

    @Test
    fun `a non-owner DT message_php form exposes the read-only roster but no member editor`() {
        // #618 — a simple participant gets the « Destinataires » row as READ-ONLY text (a <span>),
        // with NO <input name="newdest">. The roster is still readable for the « Participants » sheet,
        // but the member editor stays owner-only.
        val form = parser.parse(fixture("private_message_dt_participant_reply_form.html")).getOrThrow()

        assertFalse("a non-owner has no editable newdest input", form.canManageRecipients)
        assertEquals(null, form.manageableRecipients)
        // The full roster (minus self) is parsed from the read-only span, trimmed.
        assertEquals("TestOwner, alice, bob, Bébé Yoda, stitch+, Administration", form.recipientsRoster)
    }

    @Test
    fun `a reply form without a Destinataires row exposes no roster`() {
        // #618 — a one-to-one MP / topic reply has no « Destinataires » row at all → null roster.
        val form = parser.parse(fixture("private_message_thread.html")).getOrThrow()

        assertEquals(null, form.recipientsRoster)
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            PrivateMessageReplyFormParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
