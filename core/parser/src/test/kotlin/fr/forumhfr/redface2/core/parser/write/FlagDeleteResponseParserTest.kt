package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.FlagDeleteResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FlagDeleteResponseParserTest {

    private val parser = FlagDeleteResponseParser()

    @Test
    fun `success response carries the drapeau effacé sentence`() {
        // Captured HFR réel — the `.hop` wrapper holds « Drapeau effacé avec succès ».
        val html = readFixture("flag_delete_success.html")
        assertEquals(FlagDeleteResult.Success, parser.parse(html))
    }

    @Test
    fun `already-removed favourite is classified as Failure`() {
        // Captured HFR réel — removing an already-gone favourite lands on a listing page
        // showing « Aucun favori n'est repertorié », without the success sentence.
        val html = readFixture("flag_delete_already_removed.html")
        assertEquals(FlagDeleteResult.Failure, parser.parse(html))
    }

    @Test
    fun `unrecognised page falls back to Failure`() {
        // Defensive : any page that does not carry the success sentence is a non-deletion,
        // so the caller leaves every cache untouched.
        val html = "<html><body>Page non documentée</body></html>"
        assertEquals(FlagDeleteResult.Failure, parser.parse(html))
    }

    private fun readFixture(name: String): String {
        val stream = requireNotNull(
            FlagDeleteResponseParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name"),
        ) { "Fixture not found: fixtures/$name" }
        return stream.bufferedReader().use { it.readText() }
    }
}
