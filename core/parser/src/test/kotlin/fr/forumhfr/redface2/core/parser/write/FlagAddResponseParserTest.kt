package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.FlagAddResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FlagAddResponseParserTest {

    private val parser = FlagAddResponseParser()

    @Test
    fun `success response carries the favori positionné sentence`() {
        // No committed addflag.php fixture yet: this minimal body mirrors the `.hop`
        // wrapper shape of `flag_delete_success.html` and uses the live-verified #986
        // success marker.
        val html = """<html><body><div class="hop">Favori positionné</div></body></html>"""
        assertEquals(FlagAddResult.Success, parser.parse(html))
    }

    @Test
    fun `success response also accepts the longer HFR wording`() {
        // The specs historically recorded « Favori positionné avec succès » ; matching
        // the shorter marker keeps that variant in the success branch without inventing
        // a second contract.
        val html = """<html><body><div class="hop">Favori positionné avec succès</div></body></html>"""
        assertEquals(FlagAddResult.Success, parser.parse(html))
    }

    @Test
    fun `unrecognised page falls back to Failure`() {
        // Defensive : any page that does not carry the success sentence is a non-add,
        // so the caller leaves every cache row it cannot prove untouched.
        val html = "<html><body>Page non documentée</body></html>"
        assertEquals(FlagAddResult.Failure, parser.parse(html))
    }
}
