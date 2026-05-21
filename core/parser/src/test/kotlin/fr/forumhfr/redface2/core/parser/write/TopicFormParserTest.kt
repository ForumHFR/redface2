package fr.forumhfr.redface2.core.parser.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract of Phase 2D #148 (edit first post) against the live
 * HFR fixture. The parser must surface : sujet, BBCode initial content, the
 * pre-selected subcategory + the full `<select>` choice list, the `MsgIcon`
 * radio HFR pre-checked, the three per-post options, and the poll fields —
 * while filtering out `password` and `delete`.
 */
class TopicFormParserTest {

    private val parser = TopicFormParser()

    @Test
    fun `parses the edit-first-post fixture verbatim`() {
        val form = parser.parse(readFixture("write_edit_first_post_form.html")).getOrThrow()

        assertFalse("Authenticated FP form must not flag as anonymous", form.isAnonymous)
        assertEquals("REDACTED_HASH_CHECK", form.hashCheck)
        assertEquals("[Redface2] Topic temporaire de test écriture", form.subject)
        assertTrue(
            "initialContent must include the FP body — got ${form.initialContent.take(80)}",
            form.initialContent.contains("Test technique Redface2 #81"),
        )
        // Subcategory selection : HFR rendered « Divers (388) » as `selected`.
        assertEquals(388, form.selectedSubcat)
        // The fixture's <select> ships ~25 options. We pin three specific ones to
        // prove labels and selection round-trip without over-binding the test to
        // the entire HFR taxonomy.
        val divers = form.subcategoryChoices.first { it.id == 388 }
        assertTrue("Divers must be marked selected", divers.selected)
        assertEquals("Divers", divers.label)
        val android = form.subcategoryChoices.first { it.id == 562 }
        assertFalse("Android must not be marked selected", android.selected)
        assertEquals("Android", android.label)
        // « Aucune » option : the parser maps its empty value to `id = null`,
        // never submitted.
        val none = form.subcategoryChoices.first { it.label == "Aucune" }
        assertEquals(null, none.id)

        // MsgIcon : value=1 is the only `checked` radio.
        assertEquals("1", form.msgIcon)
        assertEquals("Forwarded hiddenFields must echo MsgIcon=1", "1", form.hiddenFields["MsgIcon"])
        // Options : signature pre-checked on this account ; smiley + emaill unchecked.
        assertTrue("signature must be checked", form.options.signatureEnabled)
        assertFalse("smiley must not be checked", form.options.smileyDisabled)
        assertFalse("emaill must not be checked", form.options.emailNotificationEnabled)

        // Hard deny rules : password and delete never reach hiddenFields.
        assertFalse("password must never leave the parser", form.hiddenFields.containsKey("password"))
        assertFalse("delete must never leave the parser", form.hiddenFields.containsKey("delete"))

        // toread1..5 are FP-only opaque fields. They are present in
        // hiddenFields with their server-side value (empty string on this
        // fixture) — that's exactly what a browser would submit for a text
        // input the user did not touch. We only assert they round-trip
        // verbatim ; populating them is reserved for a future feature.
        listOf("toread1", "toread2", "toread3", "toread4", "toread5").forEach { name ->
            assertEquals(
                "$name must round-trip with its server-side (empty) value",
                "",
                form.hiddenFields[name],
            )
        }

        // Poll : have_sondage unchecked on this fixture ; nothing to forward.
        assertFalse("have_sondage must be false on this fixture", form.poll.present)
        assertEquals("No poll fields to preserve on an empty poll", 0, form.poll.fields.size)
        assertFalse(
            "Poll editing is not implemented in this version",
            form.poll.editableInThisVersion,
        )
        // Poll names must not leak into hiddenFields — [TopicPollForm.fields]
        // is the single source of truth. Without this guard, empty
        // `textreponse0..10` and date inputs would have been POSTed even
        // though no sondage is active.
        listOf(
            "have_sondage", "allowvisitor", "max_votes",
            "jour", "mois", "annee", "heure", "minute",
            "textreponse0", "textreponse1", "textreponse5", "textreponse10",
        ).forEach { name ->
            assertFalse(
                "$name must never leak through hiddenFields — owned by TopicPollForm.fields",
                form.hiddenFields.containsKey(name),
            )
        }

        // hash_check must be present, never empty (the repository submit
        // refuses an empty hash via guardAgainstInvalidSubmission).
        assertNotNull(form.hashCheck)
    }

    @Test
    fun `fails fast when the form target is not bdd_php`() {
        val html = """<html><body><form action="/forum1.php"></form></body></html>"""
        val result = parser.parse(html)
        assertTrue(result.isFailure)
    }

    @Test
    fun `fails fast when hash_check is missing`() {
        val html = """<html><body><form action="/bdd.php">
            <input name="cat" value="10" />
        </form></body></html>"""
        val result = parser.parse(html)
        assertTrue(result.isFailure)
    }

    @Test
    fun `refuses to guess subcat when no option is marked selected`() {
        // No `selected` attribute on any option → HFR-side bug or unexpected
        // markup. We refuse to silently re-categorise the topic on submit.
        val html = """<html><body><form action="/bdd.php">
            <input name="hash_check" value="HASH" />
            <input name="sujet" value="x" />
            <textarea name="content_form">x</textarea>
            <select name="subcat">
                <option value="">Aucune</option>
                <option value="388">Divers</option>
                <option value="562">Android</option>
            </select>
        </form></body></html>"""
        val result = parser.parse(html)
        assertTrue("must fail when no subcat is selected", result.isFailure)
    }

    @Test
    fun `refuses to submit when Aucune is the selected subcat`() {
        // `Aucune` has `value=""` → id is null. Treating that as a valid
        // selection would let the user submit with `subcat=0`, which HFR
        // rejects, and worse it would mask a malformed form to the user.
        val html = """<html><body><form action="/bdd.php">
            <input name="hash_check" value="HASH" />
            <input name="sujet" value="x" />
            <textarea name="content_form">x</textarea>
            <select name="subcat">
                <option value="" selected="selected">Aucune</option>
                <option value="388">Divers</option>
            </select>
        </form></body></html>"""
        val result = parser.parse(html)
        assertTrue("Aucune-selected must fail-fast at parse", result.isFailure)
    }

    @Test
    fun `forwards poll fields through TopicPollForm only when have_sondage is checked`() {
        val html = """<html><body><form action="/bdd.php">
            <input name="hash_check" value="HASH" />
            <input name="sujet" value="x" />
            <textarea name="content_form">x</textarea>
            <select name="subcat">
                <option value="388" selected="selected">Divers</option>
            </select>
            <input type="checkbox" name="have_sondage" value="1" checked="checked" />
            <input name="textreponse0" value="Yes" />
            <input name="textreponse1" value="No" />
            <input name="textreponse2" value="" />
            <input type="checkbox" name="allowvisitor" value="1" checked="checked" />
            <select name="max_votes">
                <option value="1">1</option>
                <option value="3" selected="selected">3</option>
            </select>
            <input type="text" name="jour" value="31" />
            <input type="text" name="mois" value="12" />
            <input type="text" name="annee" value="2026" />
            <input type="text" name="heure" value="" />
            <input type="text" name="minute" value="" />
        </form></body></html>"""
        val form = parser.parse(html).getOrThrow()
        assertTrue("have_sondage must be detected", form.poll.present)
        // [TopicPollForm.fields] is the single source of truth for the sondage
        // block — assert each expected key is in it.
        assertEquals("1", form.poll.fields["have_sondage"])
        assertEquals("Yes", form.poll.fields["textreponse0"])
        assertEquals("No", form.poll.fields["textreponse1"])
        assertFalse("Empty textreponse2 must not be in poll fields", form.poll.fields.containsKey("textreponse2"))
        assertEquals("1", form.poll.fields["allowvisitor"])
        assertEquals("3", form.poll.fields["max_votes"])
        assertEquals("31", form.poll.fields["jour"])
        assertEquals("12", form.poll.fields["mois"])
        assertEquals("2026", form.poll.fields["annee"])
        assertFalse("Empty heure must not be in poll fields", form.poll.fields.containsKey("heure"))
        assertFalse("Empty minute must not be in poll fields", form.poll.fields.containsKey("minute"))
        // And the same keys must NEVER appear in hiddenFields.
        listOf(
            "have_sondage", "textreponse0", "textreponse1", "textreponse2",
            "allowvisitor", "max_votes", "jour", "mois", "annee", "heure", "minute",
        ).forEach { name ->
            assertFalse(
                "$name must not double-emit through hiddenFields",
                form.hiddenFields.containsKey(name),
            )
        }
    }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Fixture not found: $name"
        }.bufferedReader().use { it.readText() }
}
