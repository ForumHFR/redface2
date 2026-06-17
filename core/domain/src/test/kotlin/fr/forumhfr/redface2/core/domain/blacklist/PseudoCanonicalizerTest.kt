package fr.forumhfr.redface2.core.domain.blacklist

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PseudoCanonicalizerTest {

    // Special characters built from code points so the source carries no invisible glyphs.
    private val nbsp = Char(0x00A0).toString()
    private val zwsp = Char(0x200B).toString()
    private val zwnj = Char(0x200C).toString()
    private val zwj = Char(0x200D).toString()
    private val bom = Char(0xFEFF).toString()
    private val wordJoiner = Char(0x2060).toString()

    @Test
    fun `lowercases ascii`() {
        assertEquals("foobar", canonicalizePseudo("FooBar"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("foo", canonicalizePseudo("  Foo  "))
    }

    @Test
    fun `collapses internal whitespace runs to a single space`() {
        assertEquals("le foo", canonicalizePseudo("Le   Foo"))
    }

    @Test
    fun `treats a non-breaking space like a regular space`() {
        // U+00A0 around the edges (trimmed) and in the middle (collapsed to one ASCII space).
        assertEquals("le foo", canonicalizePseudo("${nbsp}Le$nbsp${nbsp}Foo$nbsp"))
    }

    @Test
    fun `keeps accents - accented and unaccented pseudos stay distinct`() {
        assertEquals("crème", canonicalizePseudo("Crème"))
        assertNotEquals(canonicalizePseudo("Crème"), canonicalizePseudo("Creme"))
    }

    @Test
    fun `normalises NFD to NFC so the two spellings of an accented pseudo match`() {
        val nfc = "Crème"
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        // Pre-condition: the two forms differ byte-wise before canonicalisation.
        assertNotEquals(nfc, nfd)
        assertEquals(canonicalizePseudo(nfc), canonicalizePseudo(nfd))
    }

    @Test
    fun `strips zero-width and other invisible format characters`() {
        assertEquals("foo", canonicalizePseudo("$bom${zwsp}Fo${zwnj}o$zwj$wordJoiner"))
    }

    @Test
    fun `blank input canonicalises to empty`() {
        assertEquals("", canonicalizePseudo(""))
        assertEquals("", canonicalizePseudo("   "))
        assertEquals("", canonicalizePseudo("$nbsp$zwsp"))
    }
}
