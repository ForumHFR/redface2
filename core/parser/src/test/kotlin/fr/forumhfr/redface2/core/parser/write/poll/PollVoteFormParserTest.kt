package fr.forumhfr.redface2.core.parser.write.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #779 (PR 1) — parse-only coverage of the poll VOTE form. Fixtures are the REAL logged-out captures
 * shipped for #697 : `topic_poll_form_meteo` (single-choice, 4 radios) and
 * `topic_poll_form_multi_bourse` (multiple-choice, 5 checkboxes, « à 2 choix »). No vote is
 * submitted anywhere ; this only proves the wire model is extracted correctly.
 */
class PollVoteFormParserTest {
    private val parser = PollVoteFormParser()

    @Test
    fun `parses the single-choice vote form (radios name=reponse)`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_meteo.html")))

        // hash_check is EXPECTED empty : every poll fixture is logged-out. Not a failure.
        assertEquals("", form.hashCheck)
        assertFalse("4 radios = single choice", form.multipleChoice)
        assertEquals(1, form.maxSelections)

        // Exact hidden fields — hash_check, the reponse inputs and sondage_submit must NOT leak in.
        assertEquals(
            mapOf(
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "432",
                "numeropost" to "44713",
            ),
            form.hiddenFields,
        )

        assertEquals(4, form.choices.size)
        assertEquals(listOf("reponse", "reponse", "reponse", "reponse"), form.choices.map { it.name })
        assertEquals(listOf("1", "2", "3", "4"), form.choices.map { it.value })
        assertEquals(listOf("sond1", "sond2", "sond3", "sond4"), form.choices.map { it.id })
        assertEquals(
            listOf("Thoulisse", "Wurst", "Tuxerman", "Autre"),
            form.choices.map { it.label },
        )
    }

    @Test
    fun `parses the multiple-choice vote form (checkboxes reponseN) with the choix cap`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_multi_bourse.html")))

        assertEquals("", form.hashCheck)
        assertTrue("checkboxes = multiple choice", form.multipleChoice)
        // « Sondage à 2 choix possibles. » — the cap is 2 even though the poll has 5 options.
        assertEquals(2, form.maxSelections)

        assertEquals(
            mapOf(
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "434",
                "numeropost" to "16022",
            ),
            form.hiddenFields,
        )

        assertEquals(5, form.choices.size)
        assertEquals(
            listOf("reponse1", "reponse2", "reponse3", "reponse4", "reponse5"),
            form.choices.map { it.name },
        )
        // Every checkbox posts value=1 ; the option identity is carried by the NAME, not the value.
        assertTrue(form.choices.all { it.value == "1" })
        assertEquals("ça ne peut que monter", form.choices.first().label)
    }

    @Test
    fun `hidden fields never carry the choice inputs, hash_check or the submit buttons`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_meteo.html")))

        assertFalse(form.hiddenFields.containsKey("hash_check"))
        assertFalse(form.hiddenFields.containsKey("reponse"))
        assertFalse(form.hiddenFields.containsKey("sondage_submit"))
    }

    @Test
    fun `returns null when the page carries no vote form`() {
        // The results-shape khakha capture has already-voted bars, no vote.php form.
        assertNull(parser.parse(fixture("topic_khakha_page_2.html")))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
