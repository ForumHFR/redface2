package fr.forumhfr.redface2.core.parser.smiley

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmileyWikiKeywordsParserTest {
    private val parser = SmileyWikiKeywordsParser()
    private val token = "[:ticento:6]"
    private val keywords = "feu feux fire brule bruler burn chaud hot cramer cramé"

    @Test
    fun `known variant preserves Wiki order and accents`() {
        assertEquals(keywords.split(' '), parser.parse(fixture("ticento-6"), token))
    }

    @Test
    fun `simple token preserves hyphens inside a keyword`() {
        assertEquals(
            listOf("jean-marc", "momo", "morandini", "jmm", "blog", "crimes", "veed", "sexy", "buzz", "nrj12"),
            parser.parse(fixture("ipoule2"), "[:ipoule2]"),
        )
    }

    @Test
    fun `known smiley without keywords is empty`() {
        assertEquals(emptyList<String>(), parser.parse(fixture("no-keywords"), "[:t-w:6]"))
    }

    @Test
    fun `unknown Wiki smiley is empty despite HTTP 200`() {
        assertEquals(emptyList<String>(), parser.parse(fixture("unknown"), "[:nexistepas-xyz]"))
    }

    @Test
    fun `another token without the requested row is a parse failure`() {
        val html = fixture("ipoule2").replace(
            "name=\"detail\" value=\"[:ipoule2]\"", "name=\"detail\" value=\"$token\"",
        )
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, token) }
    }

    @Test
    fun `mismatched detail token is a parse failure even with the requested row`() {
        val html = fixture("ticento-6").replace(
            "name=\"detail\" value=\"$token\"", "name=\"detail\" value=\"[:ipoule2]\"",
        )
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, token) }
    }

    @Test
    fun `unknown token without the explicit empty result marker is a parse failure`() {
        val html = fixture("unknown").replace("Désolé, aucune réponse n'a été trouvée", "Unexpected result")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, "[:nexistepas-xyz]") }
    }

    @Test
    fun `selects the matching row even with another smiley before it`() {
        val otherRow = requireNotNull(Jsoup.parse(fixture("ipoule2")).selectFirst("tr.s2Topic")).outerHtml()
        val html = fixture("ticento-6").replace("<tr class=\"s2Topic\"", "$otherRow<tr class=\"s2Topic\"")
        assertEquals(keywords.split(' '), parser.parse(html, token))
    }

    @Test
    fun `HTML entities are decoded before splitting and deduplication preserves first occurrence`() {
        // Deliberate mutation of the captured field, not a fabricated HFR fixture.
        val html = fixture("ticento-6").replace(keywords, "  feu &amp; &#233;t&#233; feu jean-marc&nbsp;&amp; ")
        assertEquals(listOf("feu", "&", "été", "jean-marc"), parser.parse(html, token))
    }

    @Test
    fun `token entities are decoded too`() {
        val html = fixture("ticento-6").replace("value=\"$token\"", "value=\"&#91;:ticento:6&#93;\"")
        assertEquals(keywords.split(' '), parser.parse(html, token))
    }

    @Test
    fun `missing keywords field for the requested token is a parse failure`() {
        val html = fixture("ticento-6").replace("name=\"keywords0\"", "name=\"unexpected\"")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, token) }
    }

    @Test
    fun `missing token field is a parse failure instead of a cached empty list`() {
        val html = fixture("ticento-6").replace("name=\"smiley0\"", "name=\"unexpected\"")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, token) }
    }

    @Test
    fun `missing token value is a parse failure instead of an empty result`() {
        val html = fixture("ticento-6").replace("name=\"smiley0\" value=\"$token\"", "name=\"smiley0\"")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, token) }
    }

    @Test
    fun `absent value attribute is not confused with an empty value`() {
        val html = fixture("no-keywords").replace("name=\"keywords0\" value=\"\"", "name=\"keywords0\"")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, "[:t-w:6]") }
    }

    @Test
    fun `missing form or result rows is a parse failure`() {
        assertThrows(IllegalArgumentException::class.java) { parser.parse("", token) }
        val html = fixture("unknown").replace("class=\"s2Topic\"", "class=\"unexpected\"")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(html, "[:nexistepas-xyz]") }
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/smiley_wiki_detail-$name.html"),
    ).bufferedReader().use { it.readText() }
}
