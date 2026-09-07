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

    @Test
    fun `truncation never splits a URL`() {
        val bbcode = "[quotemsg=1]voir https://example.com/page ensuite[/quotemsg]"

        assertEquals(
            "[quotemsg=1]voir [...][/quotemsg]",
            truncateQuote(bbcode, limit = 12),
        )
    }

    @Test
    fun `keeps a perso smiley atomic`() {
        val bbcode = "[quotemsg=1]bravo [:lol] la suite[/quotemsg]"

        assertEquals(
            "[quotemsg=1]bravo [...][/quotemsg]",
            truncateQuote(bbcode, limit = 8),
        )
    }

    @Test
    fun `keeps a builtin smiley atomic`() {
        val bbcode = "[quotemsg=1]bravo :bounce: la suite[/quotemsg]"

        assertEquals(
            "[quotemsg=1]bravo [...][/quotemsg]",
            truncateQuote(bbcode, limit = 8),
        )
    }

    @Test
    fun `does not treat a clock-format colon pair as a smiley`() {
        // `:30:` inside `12:30:00` must not be swallowed as a builtin smiley atom, which would
        // push the cut back before the digits.
        val bbcode = "[quotemsg=1]12:30:00 ce soir[/quotemsg]"

        assertEquals(
            "[quotemsg=1]12:30 [...][/quotemsg]",
            truncateQuote(bbcode, limit = 5),
        )
    }

    @Test
    fun `drops a url tag opened at the cut`() {
        val bbcode = "[quotemsg=1]avant [url]https://x.org/a/b[/url] après[/quotemsg]"

        assertEquals(
            "[quotemsg=1]avant [...][/quotemsg]",
            truncateQuote(bbcode, limit = 12),
        )
    }

    @Test
    fun `never splits a surrogate pair`() {
        val bbcode = "[quotemsg=1]abc😀def[/quotemsg]"

        assertEquals(
            "[quotemsg=1]abc😀 [...][/quotemsg]",
            truncateQuote(bbcode, limit = 4),
        )
    }

    @Test
    fun `a long quote ending with the marker is still truncated`() {
        val bbcode = "[quotemsg=1]un deux trois quatre [...][/quotemsg]"

        assertEquals(
            "[quotemsg=1]un deux [...][/quotemsg]",
            truncateQuote(bbcode, limit = 8),
        )
    }
}
