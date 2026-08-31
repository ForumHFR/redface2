package fr.forumhfr.redface2.core.parser.write.poll

import fr.forumhfr.redface2.core.model.write.PollCloseResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PollCloseResponseParserTest {
    private val parser = PollCloseResponseParser()

    @Test
    fun `live close success response is recognised`() {
        assertEquals(
            PollCloseResult.Success,
            parser.parse(fixture("close_sondage_success.html")),
        )
    }

    @Test
    fun `success matching ignores case accents and compacted unicode whitespace`() {
        val html = """
            <html><body>
              <div class="hop">  LE sOnDaGe a BIEN été   CLOS </div>
            </body></html>
        """.trimIndent().replace("\\u00A0", " ")

        assertEquals(PollCloseResult.Success, parser.parse(html))
    }

    @Test
    fun `success sentence is recognised without a surrounding page`() {
        val html = "<div class=\"hop\">Le sondage a bien été clos</div>"

        assertEquals(PollCloseResult.Success, parser.parse(html))
    }

    @Test
    fun `an unrecognised page folds to a generic failure`() {
        // The failure shapes (non-owner, already closed, no poll) were not captured live, so anything
        // that is NOT the proven success marker is a plain Failure — never an asserted false marker.
        assertEquals(
            PollCloseResult.Failure,
            parser.parse("<html><body><div class=\"hop\">Vous n'êtes pas autorisé.</div></body></html>"),
        )
    }

    @Test
    fun `a page without a hop message folds to a generic failure`() {
        assertEquals(
            PollCloseResult.Failure,
            parser.parse("<html><body>Page non documentée</body></html>"),
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
