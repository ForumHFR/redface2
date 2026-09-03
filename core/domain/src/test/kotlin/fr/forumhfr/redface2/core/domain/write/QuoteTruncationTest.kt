package fr.forumhfr.redface2.core.domain.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteTruncationTest {

    @Test
    fun `long quote truncates with the default limit and keeps the quote shell`() {
        val content = (1..120).joinToString(separator = " ") { "mot" }
        val bbcode = "[quotemsg=101,4,9]$content[/quotemsg]"

        val truncated = truncateQuote(bbcode)

        assertTrue(truncated.startsWith("[quotemsg=101,4,9]"))
        assertTrue(truncated.endsWith(" [...][/quotemsg]"))
        assertTrue(truncated.length < bbcode.length)
        assertEquals(listOf(101), QuotedNumreponses.fromBbcode(truncated))
    }

    @Test
    fun `short quote is returned unchanged`() {
        val bbcode = "[quotemsg=101]texte court[/quotemsg]"

        assertEquals(bbcode, truncateQuote(bbcode, limit = 300))
    }

    @Test
    fun `truncation cuts on a word boundary`() {
        val bbcode = "[quotemsg=101]un deux trois quatre[/quotemsg]"

        assertEquals(
            "[quotemsg=101]un deux trois [...][/quotemsg]",
            truncateQuote(bbcode, limit = 14),
        )
    }

    @Test
    fun `truncation closes a formatting tag opened at the cut`() {
        val bbcode = "[quotemsg=101][b]un deux trois quatre[/b] fin[/quotemsg]"

        assertEquals(
            "[quotemsg=101][b]un deux[/b] [...][/quotemsg]",
            truncateQuote(bbcode, limit = 8),
        )
    }

    @Test
    fun `truncation excludes a nested quotemsg fragment opened at the cut`() {
        val bbcode = "[quotemsg=101]avant [quotemsg=202]un deux trois quatre[/quotemsg] après[/quotemsg]"

        assertEquals(
            "[quotemsg=101]avant [...][/quotemsg]",
            truncateQuote(bbcode, limit = 12),
        )
    }

    @Test
    fun `malformed quote is returned unchanged`() {
        val bbcode = "[quotemsg=101]sans fermeture"

        assertEquals(bbcode, truncateQuote(bbcode, limit = 5))
    }

    @Test
    fun `truncation is idempotent`() {
        val bbcode = "[quotemsg=101]un deux trois quatre[/quotemsg]"
        val once = truncateQuote(bbcode, limit = 14)

        assertEquals(once, truncateQuote(once, limit = 14))
    }
}
