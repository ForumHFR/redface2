package fr.forumhfr.redface2.core.parser.write.poll

import fr.forumhfr.redface2.core.model.write.PollVoteFailureReason
import fr.forumhfr.redface2.core.model.write.PollVoteResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PollVoteResponseParserTest {
    private val parser = PollVoteResponseParser()

    @Test
    fun `accepted live response is recognised`() {
        assertEquals(
            PollVoteResult.Accepted,
            parser.parse(fixture("poll_vote_response_accepted.html")),
        )
    }

    @Test
    fun `already-voted live response is recognised`() {
        assertEquals(
            PollVoteResult.AlreadyVoted,
            parser.parse(fixture("poll_vote_response_already_voted.html")),
        )
    }

    @Test
    fun `message matching ignores case accents and compacted unicode whitespace`() {
        val html = """
            <html><body>
              <div class="hop">  VOTRE\u00A0vOtE a BIEN été   PRIS en COMPTE ! </div>
            </body></html>
        """.trimIndent().replace("\\u00A0", "\u00A0")

        assertEquals(PollVoteResult.Accepted, parser.parse(html))
    }

    @Test
    fun `accepted sentence succeeds without meta refresh`() {
        val html = "<div class=\"hop\">Votre vote a bien été pris en compte !</div>"

        assertEquals(PollVoteResult.Accepted, parser.parse(html))
    }

    @Test
    fun `unknown meta refresh redirect is rejected`() {
        val html = """
            <html><head>
              <meta http-equiv="refresh" content="1; URL=/hfr/unexpected.php">
            </head><body><div class="hop">Réponse inhabituelle.</div></body></html>
        """.trimIndent()

        assertEquals(unexpectedResponse(), parser.parse(html))
    }

    @Test
    fun `already-voted marker is recognised with meta refresh`() {
        val html = """
            <html><head>
              <meta http-equiv="Refresh" content="1; url=/hfr/topic.htm">
            </head><body><div class="hop">Désolé, vous avez déjà voté !</div></body></html>
        """.trimIndent()

        assertEquals(PollVoteResult.AlreadyVoted, parser.parse(html))
    }

    @Test
    fun `non-refresh meta is not accepted`() {
        val html = """
            <html><head><meta http-equiv="Cache-Control" content="url=/"></head>
            <body><div class="hop">Réponse inhabituelle.</div></body></html>
        """.trimIndent()

        assertEquals(unexpectedResponse(), parser.parse(html))
    }

    @Test
    fun `unknown page is rejected`() {
        assertEquals(
            unexpectedResponse(),
            parser.parse("<html><body>Page non documentée</body></html>"),
        )
    }

    private fun unexpectedResponse(): PollVoteResult =
        PollVoteResult.Failed(PollVoteFailureReason.UnexpectedResponse)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
