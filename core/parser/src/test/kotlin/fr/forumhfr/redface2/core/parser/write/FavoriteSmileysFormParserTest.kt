package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSmileysFormParserTest {

    private val replyParser = ReplyFormParser()
    private val topicParser = TopicFormParser()

    private val scrubbedTokens = listOf(
        "[:fictif_c]", "[:fictif_a:3]", "[:fictif_b]", "[:fictif_d:5]",
        "[:fictif_e:1]", "[:fictif_f]", "[:fictif_f:1]", "[:fictif_f:2]",
    )

    @Test
    fun `reply and MP compose forms preserve all eight favorites in server order`() {
        listOf("write_ia_reply_form.html", "mp_compose_form.html").forEach { name ->
            val favorites = replyParser.parse(fixture(name)).getOrThrow().favoriteSmileys
            assertEquals(name, scrubbedTokens, favorites.map { it.token })
            assertTrue(name, favorites.all { it.source == EditorSmileySource.HFR_FAVORITE })
        }
    }

    @Test
    fun `new topic form preserves all eight favorites in server order`() {
        val favorites = topicParser.parseNewTopic(fixture("write_ia_create_form.html"))
            .getOrThrow().favoriteSmileys
        assertEquals(scrubbedTokens, favorites.map { it.token })
        assertTrue(favorites.all { it.source == EditorSmileySource.HFR_FAVORITE })
    }

    @Test
    fun `MP reply and quote forms preserve their three scrubbed favorites in order`() {
        listOf("private_message_reply_form.html", "private_message_quote_form.html").forEach { name ->
            val favorites = replyParser.parse(fixture(name)).getOrThrow().favoriteSmileys
            assertEquals(name, listOf("[:testsmiley]", "[:testsmiley2]", "[:testsmiley3]"), favorites.map { it.token })
            assertTrue(name, favorites.all { it.source == EditorSmileySource.HFR_FAVORITE })
        }
    }

    @Test
    fun `anonymous reply and new topic forms have no favorites`() {
        val html = fixture("write_reply_anonymous_form.html")
        assertEquals(emptyList<EditorSmiley>(), replyParser.parse(html).getOrThrow().favoriteSmileys)
        assertEquals(emptyList<EditorSmiley>(), topicParser.parseNewTopic(html).getOrThrow().favoriteSmileys)
    }

    @Test
    fun `only direct favorite images are parsed with title fallback and first duplicate kept`() {
        val html = fixture("smiley_favorites_synthetic.html")
        val expected = listOf(
            favorite("[:fictif espace:10]", "10/fictif espace.gif"),
            favorite("[:fictif_variante:3]", "3/fictif_variante.gif"),
            favorite("[:fictif_titre]", "fictif_titre.gif"),
        )
        assertEquals(expected, replyParser.parse(html).getOrThrow().favoriteSmileys)
        assertEquals(expected, topicParser.parseNewTopic(html).getOrThrow().favoriteSmileys)
        assertEquals(
            expected,
            topicParser.parseEditFirstPost(html.replace("bddpost.php", "bdd.php")).getOrThrow().favoriteSmileys,
        )
    }

    @Test
    fun `missing favorites block yields an empty list`() {
        val html = fixture("smiley_favorites_synthetic.html").replace("dynamic_smilies", "unrelated")
        assertEquals(emptyList<EditorSmiley>(), replyParser.parse(html).getOrThrow().favoriteSmileys)
        assertEquals(emptyList<EditorSmiley>(), topicParser.parseNewTopic(html).getOrThrow().favoriteSmileys)
    }

    @Test
    fun `favorites are not capped at the unverified ten item limit`() {
        // Derived from the marked synthetic fixture, never represented as a live capture.
        val extraImages = (1..12).joinToString("") { index ->
            """<img src="https://forum-images.hardware.fr/images/perso/fictif_extra_$index.gif"
                alt="[:fictif_extra_$index]" onclick="putSmiley(this.alt,this.src)">"""
        }
        val html = fixture("smiley_favorites_synthetic.html").replace("</div>", "$extraImages</div>")
        assertEquals(15, replyParser.parse(html).getOrThrow().favoriteSmileys.size)
        assertEquals(15, topicParser.parseNewTopic(html).getOrThrow().favoriteSmileys.size)
    }

    private fun favorite(token: String, path: String) = EditorSmiley(
        token = token,
        imageUrl = "https://forum-images.hardware.fr/images/perso/$path",
        source = EditorSmileySource.HFR_FAVORITE,
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Fixture not found: $name"
        }.bufferedReader().use { it.readText() }
}
