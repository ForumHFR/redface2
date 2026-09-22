package fr.forumhfr.redface2.core.parser.smiley

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalSmileyNameExtractorTest {

    @Test
    fun `extracts exact naked names across supported HFR shapes`() {
        mapOf(
            "[:simple]" to "simple",
            "[:two words]" to "two words",
            "[:été à vélo]" to "été à vélo",
            "[:e\u0301 decompose\u0301]" to "e\u0301 decompose\u0301",
            "[:l'apostrophe]" to "l'apostrophe",
            "[:variant:7]" to "variant:7",
            "[:CaSsE]" to "CaSsE",
        ).forEach { (token, expected) ->
            assertEquals(expected, PersonalSmileyNameExtractor.extract(token))
        }
    }

    @Test
    fun `alt perso name wins over title and title remains the fallback`() {
        assertEquals(
            "Alt Exact",
            PersonalSmileyNameExtractor.extract(
                alt = "[:Alt Exact]",
                title = "[:different title]",
            ),
        )
        assertEquals(
            "title fallback",
            PersonalSmileyNameExtractor.extract(
                alt = "not a perso token",
                title = "[:title fallback]",
            ),
        )
    }

    @Test
    fun `rejects non perso and empty names`() {
        assertNull(PersonalSmileyNameExtractor.extract(":jap:"))
        assertNull(PersonalSmileyNameExtractor.extract("[: ]suffix"))
        assertNull(PersonalSmileyNameExtractor.extract("[:]"))
    }
}
