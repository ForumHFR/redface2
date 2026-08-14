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
 * explicitly: `cat=prive` (a String, not a numeric id) and the server-prefilled `numrep` must be
 * carried verbatim in the hidden fields.
 *
 * #1041 — `numrep` has THREE distinct meanings depending on which form HFR serves, each pinned here
 * on a live capture (see each fixture's `.source.txt`):
 *
 * - `forum2.php` embedded quick-reply form → the page's LAST post id (a prefill, not a quote ref);
 * - `message.php` plain reply form (`private_message_reply_form.html`) → EMPTY;
 * - `message.php` quote form (`private_message_quote_form.html`) → the CITED message id, with the
 *   `[quotemsg=…]` prefill in the textarea.
 *
 * The two `message.php` fixtures come from the same conversation in the same session, so their delta
 * IS the quote contract: `numrep` plus the prefilled textarea, nothing else.
 *
 * Both are **reduced** captures — the `form[name=hop]` subtree only, with the page chrome, toolbar,
 * cryptlinks and message recaps stripped at capture time. #1041 — the reduction keeps the subtree
 * selected by [ReplyFormParser] plus the in-form `find_smilies_timer(…)` marker:
 * [SmileyUserIdExtractor] still receives the whole fixture and takes its first marker. This keeps a
 * real private conversation from riding along in the repository (see their `.source.txt`).
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
        // On THIS form — the quick-reply embedded in forum2.php — numrep is the page's last-post id
        // HFR prefilled. Must be preserved as-is. The message.php forms below prove it is not the
        // meaning of numrep everywhere.
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

    @Test
    fun `the message_php quote form carries the CITED message in numrep and a quotemsg prefill`() {
        // #1041 spike (lot 0 of #1040) — THE unknown of the file: is a private-message quote a real
        // per-message reference, or just the reply prefill? Measured live on 2026-08-12: HFR fills
        // numrep with the CITED message (the 4th of a 5-message page, so provably not the last one)
        // and prefills the textarea with the same [quotemsg=…] block as a topic quote.
        val form = parser.parse(fixture("private_message_quote_form.html")).getOrThrow()

        assertFalse("authenticated quote form is never anonymous", form.isAnonymous)
        assertEquals("1980000004", form.hiddenFields["numrep"])
        // The middle parameter is the server-served rank (ref=4 in the followed href) and the third is
        // the cited author's userId — both server-controlled, never recomputed client-side. The
        // trailing newline is HFR's own; wholeText() forwards the BBCode verbatim.
        assertEquals(
            "[quotemsg=1980000004,4,990001]Message prive de test (contenu remplace).[/quotemsg]\n",
            form.initialContent,
        )
        // Sanitisation lock, NOT a server contract: the scrubbed ids must stay consistent with
        // private_message_thread.html (#298) — current user 990002, correspondent (here the cited
        // author) 990001 — so the two fixtures never describe the same person with two identities.
        assertEquals(990002, form.userId)
        // `ref` travels in the GET href and inside the BBCode tag, but HFR serves NO hidden ref field
        // on this form — a quote POST must not invent one.
        assertFalse("no hidden ref field is served", form.hiddenFields.containsKey("ref"))
        // numreponse stays the EDIT target: quoting never repurposes it.
        assertEquals("", form.hiddenFields["numreponse"])
        // Private routing, forwarded verbatim on POST.
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("3000001", form.hiddenFields["post"])
        assertEquals("0", form.hiddenFields["subcat"])
        assertEquals("1", form.hiddenFields["page"])
        assertEquals("TESTHASH", form.hashCheck)
        assertEquals("Sujet prive de test", form.sujet)
        assertEquals("TestUser", form.hiddenFields["pseudo"])
        assertFalse("password must never be collected", form.hiddenFields.containsKey("password"))
        // message.php renders the options as real checkboxes (unlike the quick-reply's hidden inputs).
        assertTrue("signature is the checked default", form.options.signatureEnabled)
        assertEquals("1", form.msgIcon)
        // A one-to-one MP labels the row « Destinataire » (singular) and serves no newdest: no roster,
        // no member editor — the DT-only contract of #612/#618 is untouched by quoting.
        assertEquals(null, form.recipientsRoster)
        assertFalse("a one-to-one MP exposes no member editor", form.canManageRecipients)
    }

    @Test
    fun `the message_php reply form of a one-to-one MP carries an empty numrep`() {
        // #1041 — the control capture, same conversation and session as the quote form above. This is
        // the form production actually parses for a 1:1 MP (PrivateMessageReplyLinkParser follows
        // form#repondre_form). Its numrep is EMPTY: the « last post of the page » prefill belongs to
        // the forum2.php quick-reply form, not to message.php.
        val form = parser.parse(fixture("private_message_reply_form.html")).getOrThrow()

        assertEquals("", form.hiddenFields["numrep"])
        assertEquals("", form.hiddenFields["numreponse"])
        assertEquals("", form.initialContent)
        assertEquals("prive", form.hiddenFields["cat"])
        assertEquals("3000001", form.hiddenFields["post"])
        assertEquals("TESTHASH", form.hashCheck)
        assertEquals(null, form.recipientsRoster)
        assertFalse("a one-to-one MP exposes no member editor", form.canManageRecipients)
    }

    @Test
    fun `quoting changes exactly two fields of the message_php form`() {
        // #1041 — both fixtures come from the same conversation, same page, same session, minutes
        // apart. The claim is scoped to what the POST forwards (the parsed hidden fields of
        // `form[name=hop]`, which is all these reduced fixtures contain): identical except numrep,
        // plus the quote's textarea prefill. That is the whole citation contract — no new field, no new
        // parser (lot 4 of #1040). It says nothing about the surrounding page, which is not captured.
        val reply = parser.parse(fixture("private_message_reply_form.html")).getOrThrow()
        val quote = parser.parse(fixture("private_message_quote_form.html")).getOrThrow()

        // Compare the WHOLE parsed form, not just the hidden map: hashCheck, sujet, isAnonymous,
        // options, msgIcon, userId and the roster fields must be identical too. Only `numrep` and the
        // prefill are normalised away — anything else that differed would fail here.
        assertEquals(
            reply.copy(hiddenFields = reply.hiddenFields - "numrep", initialContent = ""),
            quote.copy(hiddenFields = quote.hiddenFields - "numrep", initialContent = ""),
        )
        assertEquals("", reply.hiddenFields["numrep"])
        assertEquals("1980000004", quote.hiddenFields["numrep"])
        assertEquals("", reply.initialContent)
        assertTrue("only the quote form is prefilled", quote.initialContent.startsWith("[quotemsg="))
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(
            PrivateMessageReplyFormParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
