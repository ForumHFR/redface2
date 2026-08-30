package fr.forumhfr.redface2.core.parser.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HfrTopicUrlParserTest {

    @Test
    fun `relative URL preserves category topic page and fragment`() {
        assertEquals(
            HfrTopicUrl(
                categorySlug = "GSMGPSPDA",
                post = 35_421,
                page = 3,
                scrollTo = 2_786_758,
            ),
            HfrTopicUrlParser.parse(
                "/hfr/GSMGPSPDA/redface-dev-sujet_35421_3.htm#t2786758",
            ),
        )
    }

    @Test
    fun `absolute URL with multiple subcategory segments is parsed`() {
        assertEquals(
            HfrTopicUrl(
                categorySlug = "Discussions",
                post = 148_749,
                page = 17,
                scrollTo = null,
            ),
            HfrTopicUrlParser.parse(
                "https://forum.hardware.fr/hfr/Discussions/Actualite/Europe/" +
                    "topic-name-sujet_148749_17.htm",
            ),
        )
    }

    @Test
    fun `URL without subcategory keeps its explicit page`() {
        assertEquals(
            HfrTopicUrl(
                categorySlug = "ia",
                post = 99,
                page = 12,
                scrollTo = null,
            ),
            HfrTopicUrlParser.parse("/hfr/ia/topic-sujet_99_12.htm"),
        )
    }

    @Test
    fun `listing URL is rejected`() {
        assertNull(HfrTopicUrlParser.parse("/hfr/Hardware/carte-mere/liste_sujet-2.htm"))
    }

    @Test
    fun `non HFR path is rejected`() {
        assertNull(HfrTopicUrlParser.parse("https://forum.hardware.fr/forum2.php?post=99&page=3"))
    }
}
