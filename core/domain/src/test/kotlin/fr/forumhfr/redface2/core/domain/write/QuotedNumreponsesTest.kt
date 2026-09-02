package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotedNumreponsesTest {

    @Test
    fun `extracts every quotemsg tag in appearance order (#974)`() {
        val bbcode = "[quotemsg=2523833,1,1214571]a[/quotemsg]\n\n" +
            "[quotemsg=101]b[/quotemsg]\n\n" +
            "[QuoteMsg=303,3,9]c[/QuoteMsg]\n\nma réponse"

        assertEquals(listOf(2_523_833, 101, 303), QuotedNumreponses.fromBbcode(bbcode))
    }

    @Test
    fun `ignores malformed tags and plain quotes`() {
        val bbcode = "[quotemsg]x[/quotemsg] [quotemsg=]y[/quotemsg] [quotemsg=abc,1,2]z[/quotemsg] " +
            "[quote]w[/quote] [quotemsg=99999999999999999999]overflow[/quotemsg] [quotemsg=42,7,8]ok[/quotemsg]"

        assertEquals(listOf(42), QuotedNumreponses.fromBbcode(bbcode))
    }

    @Test
    fun `deduplicates a post cited twice and returns empty without any tag`() {
        assertEquals(
            listOf(101, 202),
            QuotedNumreponses.fromBbcode(
                "[quotemsg=101,1,9]a[/quotemsg][quotemsg=202]b[/quotemsg][quotemsg=101]c[/quotemsg]",
            ),
        )
        assertEquals(emptyList<Int>(), QuotedNumreponses.fromBbcode(""))
        assertEquals(emptyList<Int>(), QuotedNumreponses.fromBbcode("réponse sans citation :jap:"))
    }

    @Test
    fun `unions the inline tags with the armed cards, cards after, deduplicated`() {
        val cards = listOf(card(303), card(101), card(404))

        assertEquals(
            listOf(101, 202, 303, 404),
            QuotedNumreponses.of("[quotemsg=101]a[/quotemsg]\n\n[quotemsg=202]b[/quotemsg]\n\ntexte", cards),
        )
        assertEquals(listOf(303, 101, 404), QuotedNumreponses.of("texte seul", cards))
        assertEquals(emptyList<Int>(), QuotedNumreponses.of("texte seul", emptyList()))
    }

    private fun card(numreponse: Int): QuoteSelection = QuoteSelection(
        locator = QuoteLocator(page = 3, numreponse = numreponse, ref = 1),
        author = "tester",
        excerpt = "extrait",
    )
}
