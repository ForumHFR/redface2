package fr.forumhfr.redface2.core.parser.search

import fr.forumhfr.redface2.core.parser.search.TopicPageUrlParser.parseTopicPageFromUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #277 — tests for [TopicPageUrlParser].
 *
 * The reference shapes come from the live redirect proof of 2026-06-10 :
 * `GET forum2.php?config=hfr.inc&cat=23&post=35421&page=1&numreponse=2786758`
 * → `301 Location: /hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758`.
 */
class TopicPageUrlParserTest {

    @Test
    fun `relative pretty URL with fragment resolves the page`() {
        // Verbatim Location header from the live proof.
        assertEquals(
            3,
            parseTopicPageFromUrl("/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758", post = 35421),
        )
    }

    @Test
    fun `absolute pretty URL resolves the page`() {
        assertEquals(
            17,
            parseTopicPageFromUrl(
                "https://forum.hardware.fr/hfr/gsmgpspda/redface-dev-sujet_35421_17.htm#t2786758",
                post = 35421,
            ),
        )
    }

    @Test
    fun `pretty URL with a sub-category segment resolves the page`() {
        // The pretty path depth varies : `/hfr/<cat>/<subcat>/…` vs `/hfr/<cat>/…`.
        assertEquals(
            42,
            parseTopicPageFromUrl("/hfr/gsmgpspda/android/some-topic-sujet_12345_42.htm#t999", post = 12345),
        )
    }

    @Test
    fun `pretty URL without fragment resolves the page`() {
        assertEquals(5, parseTopicPageFromUrl("/hfr/hardware/foo-sujet_777_5.htm", post = 777))
    }

    @Test
    fun `multi-digit page is parsed in full`() {
        assertEquals(1234, parseTopicPageFromUrl("/hfr/hardware/foo-sujet_777_1234.htm", post = 777))
    }

    @Test
    fun `pretty URL of another topic returns null`() {
        // The post anchor must reject a Location that designates a DIFFERENT topic.
        assertNull(parseTopicPageFromUrl("/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758", post = 99999))
    }

    @Test
    fun `listing URL is not mistaken for a thread segment`() {
        // `liste_sujet_1_2.htm` : the char before `sujet` is `_`, rejected by the lookbehind.
        assertNull(parseTopicPageFromUrl("/hfr/hardware/liste_sujet_1_2.htm", post = 1))
    }

    @Test
    fun `forum2 query URL with matching post resolves the page`() {
        assertEquals(
            5,
            parseTopicPageFromUrl(
                "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35421&page=5&numreponse=123",
                post = 35421,
            ),
        )
    }

    @Test
    fun `relative forum2 query URL with matching post resolves the page`() {
        assertEquals(
            8,
            parseTopicPageFromUrl("/forum2.php?config=hfr.inc&cat=23&post=35421&page=8", post = 35421),
        )
    }

    @Test
    fun `forum2 query URL of another topic returns null`() {
        // Same anchor rule as the pretty shape : a URL of another topic must not match.
        assertNull(
            parseTopicPageFromUrl("/forum2.php?config=hfr.inc&cat=23&post=11111&page=5", post = 35421),
        )
    }

    @Test
    fun `forum2 query URL without a page returns null`() {
        assertNull(parseTopicPageFromUrl("/forum2.php?config=hfr.inc&cat=23&post=35421", post = 35421))
    }

    @Test
    fun `arbitrary string returns null`() {
        assertNull(parseTopicPageFromUrl("not a url at all", post = 35421))
        assertNull(parseTopicPageFromUrl("", post = 35421))
        assertNull(parseTopicPageFromUrl("/login.php", post = 35421))
    }
}
