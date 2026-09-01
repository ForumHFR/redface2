package fr.forumhfr.redface2.core.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * Pins the semantics of the shared search folding (#739): every client-side search of the app
 * (Drapeaux, DT, Forum, Réglages) relies on it, so a change here is a change everywhere.
 */
class SearchFoldingTest {

    // --- foldForSearch -----------------------------------------------------------------------

    @Test
    fun `strips French accents and lowercases`() {
        assertEquals("cafe", "Café".foldForSearch())
        assertEquals("reflexion sur l'ete a noel, ca va", "Réflexion sur l'Été à Noël, ça va".foldForSearch())
    }

    @Test
    fun `an unaccented ascii string is only lowercased`() {
        assertEquals("kotlin multiplatform", "Kotlin Multiplatform".foldForSearch())
    }

    @Test
    fun `the empty string folds to the empty string`() {
        assertEquals("", "".foldForSearch())
    }

    @Test
    fun `NFC and NFD spellings fold to the same key`() {
        val nfc = "Café"
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        // Pre-condition: the two forms differ byte-wise before folding.
        assertNotEquals(nfc, nfd)
        assertEquals("cafe", nfd.foldForSearch())
        assertEquals(nfc.foldForSearch(), nfd.foldForSearch())
    }

    @Test
    fun `spells out the French ligatures oe and ae, upper and lower case`() {
        assertEquals("coeur", "cœur".foldForSearch())
        assertEquals("oeuvre", "ŒUVRE".foldForSearch())
        assertEquals("ex aequo", "ex æquo".foldForSearch())
        assertEquals("aeon", "Æon".foldForSearch())
    }

    @Test
    fun `non-latin scripts without diacritics are left unchanged`() {
        assertEquals("日本語", "日本語".foldForSearch())
        assertEquals("привет", "привет".foldForSearch())
    }

    @Test
    fun `digits, punctuation and whitespace are left unchanged`() {
        assertEquals("rdna4 — amd (2026)  ", "RDNA4 — AMD (2026)  ".foldForSearch())
    }

    // --- containsFolded ----------------------------------------------------------------------

    @Test
    fun `cafe finds café and café finds cafe`() {
        assertTrue("Le topic du café".containsFolded("cafe"))
        assertTrue("Le topic du cafe".containsFolded("café"))
    }

    @Test
    fun `matching is case-insensitive on both sides`() {
        assertTrue("CAFÉ".containsFolded("café"))
        assertTrue("café".containsFolded("CAFÉ"))
    }

    @Test
    fun `a ligature title is found by its two-letter spelling and vice versa`() {
        assertTrue("Le cœur du problème".containsFolded("coeur"))
        assertTrue("Le coeur du probleme".containsFolded("cœur"))
    }

    @Test
    fun `an empty query matches anything`() {
        assertTrue("Café".containsFolded(""))
        assertTrue("".containsFolded(""))
    }

    @Test
    fun `an unrelated query does not match`() {
        assertFalse("Café".containsFolded("thé"))
        assertFalse("".containsFolded("a"))
    }
}
