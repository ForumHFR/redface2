package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class SmileyImageUrlTest {

    @Test
    fun `favorite and wiki image URLs with literal spaces have the same loadable OkHttp path`() {
        // synthétique #1154 : same raw src as a form favorite or wiki result, no network.
        val image = """<img src="https://forum-images.hardware.fr/images/perso/10/fictif espace.gif"
            alt="[:fictif espace:10]" onclick="putSmiley(this.alt,this.src)">"""
        val html = """<form action="/bddpost.php"><input name="hash_check" value="SYNTHETIC">
            <div id="dynamic_smilies">$image</div></form>"""
        val favorite = ReplyFormParser().parse(html).getOrThrow().favoriteSmileys.single()
        val wiki = SmileySearchParser().parse(image).single()

        assertEquals(wiki.imageUrl, favorite.imageUrl)
        assertEquals("[:fictif espace:10]", favorite.token)
        listOf(wiki, favorite).forEach { smiley ->
            val request = Request.Builder().url(smiley.imageUrl).build()
            assertEquals("/images/perso/10/fictif%20espace.gif", request.url.encodedPath)
        }
    }
}
