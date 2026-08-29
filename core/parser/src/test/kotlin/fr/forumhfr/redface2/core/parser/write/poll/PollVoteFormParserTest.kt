package fr.forumhfr.redface2.core.parser.write.poll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive transformer coverage against real authenticated and logged-out HFR poll forms. */
class PollVoteFormParserTest {
    private val parser = PollVoteFormParser()

    @Test
    fun `logged-out form is preserved when hash_check is blank`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_meteo.html")))

        // Parser invariant: capability is preserved while the downstream repository owns the
        // blank-token guard. Dropping this form would also let a fresh open-poll cache row skip GET.
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
    fun `parses authenticated single-choice form with exact token fields and choices`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_authenticated_mono.html")))

        assertEquals("0".repeat(32), form.hashCheck)
        assertFalse(form.multipleChoice)
        assertEquals(1, form.maxSelections)
        assertEquals(
            listOf(
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "557",
                "numeropost" to "96127",
            ),
            form.hiddenFields.entries.map { it.key to it.value },
        )
        assertEquals(5, form.choices.size)
        assertTrue(form.choices.all { it.name == "reponse" })
        assertEquals(listOf("1", "2", "3", "4", "5"), form.choices.map { it.value })
        assertEquals(listOf("sond1", "sond2", "sond3", "sond4", "sond5"), form.choices.map { it.id })
        assertEquals(
            "J'ai tout annulé et je me bunkerise avec 100kg de bouffe",
            form.choices.first().label,
        )
    }

    @Test
    fun `parses authenticated multiple-choice form with exact token fields choices and cap`() {
        val form = requireNotNull(parser.parse(fixture("topic_poll_form_authenticated_multi.html")))

        assertEquals("0".repeat(32), form.hashCheck)
        assertTrue(form.multipleChoice)
        assertEquals(7, form.maxSelections)
        assertEquals(
            listOf(
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "426",
                "numeropost" to "181",
            ),
            form.hiddenFields.entries.map { it.key to it.value },
        )
        assertEquals(10, form.choices.size)
        assertEquals((1..10).map { "reponse$it" }, form.choices.map { it.name })
        assertTrue(form.choices.all { it.value == "1" })
        assertEquals("Une bonne dose d'humour", form.choices[6].label)
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
