package fr.forumhfr.redface2.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersoSmileyUrlTest {
    @Test fun `derivation preserves accents spaces case and the literal extension`() {
        // Synthetic unit inputs, not a captured HTML fixture or claims that these sprites exist.
        val base = "https://forum-images.hardware.fr/images/perso/"
        assertEquals("${base}Été Test.gif", persoSmileyUrl("[:Été Test]"))
        assertEquals("${base}3/Été Test.gif", persoSmileyUrl("[:Été Test:3]"))
        assertEquals("${base}name.gif.gif", persoSmileyUrl("[:name.gif]"))
        assertEquals("${base}2/fictif:interne.gif", persoSmileyUrl("[:fictif:interne:2]"))
    }

    @Test fun `invalid paths and non perso tokens stay unresolved`() {
        listOf("", ":jap:", "[: ]", "[:a/b]", "[:a\\b]", "[:a?b]", "[:a#b]", "[:a\nb]", "[:a]x")
            .forEach { assertNull(it, persoSmileyUrl(it)) }
    }
}
