package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrFavoriteSmileyFormParserTest {
    private val parser = HfrFavoriteSmileyFormParser()

    @Test
    fun `captured form preserves document order images spaces variants and synthetic secrets`() {
        val form = requireNotNull(parser.parse(fixture()))

        assertEquals(listOf("[:fictif c:10]", "[:fictif_a]", "[:fictif b:2]"), form.favorites.map { it.token })
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/10/fictif c.gif",
            form.favorites.first().imageUrl,
        )
        assertTrue(form.favorites.all { it.source == EditorSmileySource.HFR_FAVORITE })
        assertEquals(HASH_CHECK, form.hashCheck)
        assertEquals(CODEHEX, form.codehex)
        assertFalse(form.toString().contains(CODEHEX))
        assertFalse(form.toString().contains(HASH_CHECK))
    }

    @Test
    fun `synthetic empty form is valid but a missing form or hash is not`() {
        val empty = fixture().replace(Regex("""(?s)<tr class="s2Topic">.*?</tr>"""), "")
        assertEquals(emptyList<Any>(), requireNotNull(parser.parse(empty)).favorites)
        assertNull(parser.parse(""))
        assertNull(parser.parse(fixture().replace("name=\"hash_check\"", "name=\"missing\"")))
    }

    @Test
    fun `synthetic missing duplicated reordered and extra indices stop parsing`() {
        listOf(
            fixture().replace("name=\"smiley1\"", "name=\"smiley4\""),
            fixture().replace("name=\"smiley1\"", "name=\"smiley0\""),
            fixture().replace("name=\"smiley0\"", "name=\"swapped\"")
                .replace("name=\"smiley1\"", "name=\"smiley0\"")
                .replace("name=\"swapped\"", "name=\"smiley1\""),
            fixture().replace("name=\"smiley0\"", "name=\"smiley01\""),
            fixture().replace("name=\"smiley1\"", "name=\"other\""),
            fixture().replace("</form>", "<input type=hidden name=smiley3 value=x></form>"),
            fixture().replace("name=\"delete1\"", "name=\"delete4\""),
        ).forEach { html -> assertNull(parser.parse(html)) }
    }

    @Test
    fun `synthetic rows moved to another form or outside forms reject a partial or empty list`() {
        val html = fixture()
        val rows = Regex("""(?s)<tr class="s2Topic">.*?</tr>""").findAll(html).map { it.value }.toList()
        listOf(1, rows.size).forEach { count ->
            val moved = rows.takeLast(count)
            val partial = moved.fold(html) { document, row -> document.replace(row, "") }
            val table = "<table>${moved.joinToString("\n")}</table>"
            outsideFormVariants(table).forEach { outside ->
                assertNull(parser.parse(partial.replace("</body>", "$outside</body>")))
            }
        }
    }

    @Test
    fun `synthetic stray fields of any type and standalone rows reject an otherwise valid form`() {
        listOf(
            "<input name=smiley99 value=aaa>",
            "<input type=hidden name=smiley99 value=aaa>",
            "<input type=checkbox name=delete99>",
            "<input name=delete99>",
            "<table><tr class=s2Topic><td>orphan</td></tr></table>",
        ).forEach { extra ->
            outsideFormVariants(extra).forEach { outside ->
                assertNull(parser.parse(fixture().replace("</body>", "$outside</body>")))
            }
        }
    }

    @Test
    fun `synthetic raw orphan row and nested or unclosed forms cannot be repaired into a valid list`() {
        val html = fixture()
        listOf(
            html.replace("</body>", "<tr class=s2Topic><td>forgotten favorite</td></tr></body>"),
            html.replace("<table class=", "<form name=test><table class=")
                .replace("</table>", "</table></form>"),
            html.replace("<table class=", "<form name=other><table class=")
                .replace("</table>", "</table></form>"),
            html.replace("</form>", ""),
            html.replace("</form>", "</form"),
            html.replace("</form>", "</form></form>"),
        ).forEach { malformed -> assertNull(parser.parse(malformed)) }
    }

    @Test
    fun `synthetic favorite fields must have their expected type even outside rows`() {
        listOf("type=\"text\"", "").forEach { type ->
            val html = fixture().replace("type=\"hidden\" name=\"smiley1\"", "$type name=\"smiley1\"")
            assertNull(parser.parse(html))
        }
        listOf("type=\"hidden\"", "").forEach { type ->
            val html = fixture().replace("type=\"checkbox\" name=\"delete1\"", "$type name=\"delete1\"")
            assertNull(parser.parse(html))
        }
        // These fields used to disappear from the type-filtered selection, leaving all rows apparently valid.
        listOf(
            "<input type=text name=smiley3 value=aaa>",
            "<input name=smiley3 value=aaa>",
            "<input type=hidden name=delete3>",
            "<input name=delete3>",
            "<textarea name=smiley3>aaa</textarea>",
            "<select name=delete3></select>",
        ).forEach { field ->
            assertNull(parser.parse(fixture().replace("</form>", "$field</form>")))
        }
    }

    @Test
    fun `raw guard accepts captured attributes with case spacing and quote variants`() {
        listOf("\"", "'", "").forEach { quote ->
            val html = fixture().replace("<form ", "<FORM ").replace("</form>", "</FORM>")
                .replace("name=\"test\"", "NAME \n= ${quote}test$quote")
                .replace("name=\"smiley1\"", "NAME \t= ${quote}smiley1$quote")
                .replace("name=\"delete1\"", "NAME = ${quote}delete1$quote")
                .replace("class=\"s2Topic\"", "CLASS \n= ${quote}s2Topic$quote")
            assertEquals(3, requireNotNull(parser.parse(html)).favorites.size)
        }
        val classes = fixture().replace("class=\"s2Topic\"", "class=\"extra s2Topic other\"")
        assertEquals(3, requireNotNull(parser.parse(classes)).favorites.size)
    }

    @Test
    fun `commented fields and words inside text alt or title are not raw attributes`() {
        val noise = """
            <!-- <input name=smiley99><input name=delete99><tr class=s2Topic><form name=test> -->
            <img alt="name='smiley99' name='delete99' class='s2Topic' <form name='test'>">
            <span title='name="smiley99" name="delete99" class="s2Topic" <form name="test">'>
                name=smiley99 name=delete99 class=s2Topic &lt;form name=test&gt;
            </span>
        """.trimIndent()
        val expected = requireNotNull(parser.parse(fixture())).favorites
        assertEquals(expected, requireNotNull(parser.parse(fixture().replace("</form>", "$noise</form>"))).favorites)
    }

    @Test
    fun `synthetic broken favorite keeps its raw value even without a usable image`() {
        val html = fixture().replace("[:fictif_a]", "aaa")
            .replace("src=\"https://forum-images.hardware.fr/images/perso/fictif_a.gif\"", "aaa")
        val form = requireNotNull(parser.parse(html))

        assertEquals("aaa", form.favorites[1].token)
        assertEquals("", form.favorites[1].imageUrl)
        assertEquals(3, form.favorites.size)
    }

    @Test
    fun `synthetic hostile actions never produce a form or leak a secret`() {
        listOf(
            "other.php?config=hfr.inc&codehex=$CODEHEX",
            "https://evil.example/user/supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX",
            "http://forum.hardware.fr/user/supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX",
            "https://user@forum.hardware.fr/user/supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX",
            "supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX&extra=1",
            "supprperso_validation.php?config=hfr.inc&config=hfr.inc&codehex=$CODEHEX",
            "supprperso_validation.php?config=other&codehex=$CODEHEX",
            "supprperso_validation.php?config=hfr.inc&codehex=not-hex",
            "supprperso_validation.php?config=hfr.inc&codehex=$CODEHEX#fragment",
            "supprperso_validation.php?config=hfr.inc&codehex=%ZZ$CODEHEX",
        ).forEach { action ->
            val html = fixture().replace(Regex("action=\"[^\"]*\""), "action=\"$action\"")
            assertNull(parser.parse(html))
        }
    }

    private fun outsideFormVariants(html: String): List<String> = listOf(
        "<form name=other>$html</form>",
        "<form>$html</form>",
        html,
    )

    private fun fixture(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/hfr_favorite_smiley_supprperso_form_3_rows.html"),
    ).bufferedReader().use { it.readText() }

    private companion object {
        const val HASH_CHECK = "0123456789abcdef0123456789abcdef"
        const val CODEHEX = "fedcba9876543210fedcba9876543210"
    }
}
