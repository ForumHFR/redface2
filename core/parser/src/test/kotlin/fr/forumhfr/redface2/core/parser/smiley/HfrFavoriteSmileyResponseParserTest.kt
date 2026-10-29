package fr.forumhfr.redface2.core.parser.smiley

import org.junit.Assert.assertEquals
import org.junit.Test

class HfrFavoriteSmileyResponseParserTest {
    private val parser = HfrFavoriteSmileyResponseParser()

    @Test
    fun `captured responses are classified by hop text including success without refresh`() {
        mapOf(
            "addperso_success" to HfrFavoriteSmileyResponse.Added,
            "addperso_success_empty_list" to HfrFavoriteSmileyResponse.Added,
            "addperso_duplicate" to HfrFavoriteSmileyResponse.AlreadyPresent,
            "addperso_full" to HfrFavoriteSmileyResponse.Full(10),
            "addperso_not_found" to HfrFavoriteSmileyResponse.NotFound,
            "supprperso_validation_success" to HfrFavoriteSmileyResponse.Removed,
        ).forEach { (name, expected) ->
            val html = requireNotNull(
                javaClass.classLoader?.getResourceAsStream("fixtures/hfr_favorite_smiley_$name.html"),
            ).bufferedReader().use { it.readText() }
            assertEquals(name, expected, parser.parse(html))
        }
    }

    @Test
    fun `synthetic normalization ignores accents case nonbreaking spaces and apostrophe typography`() {
        assertEquals(
            HfrFavoriteSmileyResponse.Added,
            parser.parse("<div class=hop> AJOUT EFFECTUE&nbsp; AVEC  SUCCES ! </div>"),
        )
        assertEquals(
            HfrFavoriteSmileyResponse.NotFound,
            parser.parse("<div class=hop>Le smiley correspondant à ce code n’existe pas</div>"),
        )
    }

    @Test
    fun `synthetic full response reports the announced limit and falls back when unreadable`() {
        mapOf(
            "Désolé, vous ne pouvez rajouter plus de 25 smilies" to 25,
            "VOUS NE POUVEZ RAJOUTER PLUS DE&nbsp;12 smilies" to 12,
            "Vous ne pouvez rajouter plus de dix smilies" to 10,
            "Vous ne pouvez rajouter plus de 999999999999999999 smilies" to 10,
        ).forEach { (message, limit) ->
            assertEquals(HfrFavoriteSmileyResponse.Full(limit), parser.parse("<div class=hop>$message</div>"))
        }
    }

    @Test
    fun `synthetic unknown text or missing hop cannot prove a mutation`() {
        listOf("", "<div class=hop>Autre texte</div>", "Ajout effectué avec succès !").forEach { html ->
            assertEquals(HfrFavoriteSmileyResponse.UnknownResponse, parser.parse(html))
        }
    }

    @Test
    fun `synthetic login form takes precedence over any success text`() {
        val html = """
            <form action='/login_validation.php?config=hfr.inc'>
              <input name='pseudo'><input name='password' type='password'>
            </form><div class=hop>Ajout effectué avec succès !</div>
        """.trimIndent()
        assertEquals(HfrFavoriteSmileyResponse.NotAuthenticated, parser.parse(html))
    }
}
