package fr.forumhfr.redface2.core.parser

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins HFR's `md_*cryptlink` decode (#227). The algorithm is verified against the public
 * `md_forum_decryptlink` JS (`common.js`) and against real captured fixtures where the
 * per-post quote/edit/profile links are served obfuscated (cat=13 / cat=23 logged-out
 * captures carry zero clear `numrep=` anchors — only `md_noclass_cryptlink` spans).
 */
class CryptlinkDecoderTest {

    @Test
    fun `decode reproduces the JS base16 — forum logo crib`() {
        // The forum-logo header link from a real page. md_forum_decryptlink decodes it to
        // the HFR home URL; this is the canonical crib that proves the alphabet mapping.
        val decoded = CryptlinkDecoder.decode(LOGO_HEX)
        assertTrue(
            "forum-logo crib must decode to the hardware.fr home URL, was $decoded",
            decoded != null && decoded.startsWith("https://www.hardware.fr"),
        )
    }

    @Test
    fun `decode rejects malformed input without throwing`() {
        assertNull("odd length is not a byte stream", CryptlinkDecoder.decode("45C"))
        assertNull("characters outside the alphabet decode to null", CryptlinkDecoder.decode("ZZ"))
        assertNull("empty string has no bytes", CryptlinkDecoder.decode(""))
    }

    @Test
    fun `decodeClass strips the prefix and ignores non-cryptlink classes`() {
        val url = CryptlinkDecoder.decodeClass("md_noclass_cryptlink$LOGO_HEX")
        assertTrue("md_noclass_cryptlink suffix decodes", url != null && url.startsWith("https://"))
        assertNull("a plain class is not a cryptlink", CryptlinkDecoder.decodeClass("cHeader"))
    }

    @Test
    fun `materialize exposes the obfuscated quote link on a real cat=13 fixture`() {
        // topic_loisirs_chutes_p4344.html is a real capture whose per-post toolbar links are
        // ALL md_noclass_cryptlink — so the parser's clear-link selectors find nothing before
        // decoding. materialize() must turn them into real anchors carrying numrep/ref.
        val document = Jsoup.parse(fixture("topic_loisirs_chutes_p4344.html"))

        // Precondition: no clear quote anchor exists on the obfuscated page.
        assertEquals(
            "obfuscated fixture must ship zero clear numrep anchors before decoding",
            0,
            document.select("a[href*=numrep=]").size,
        )

        CryptlinkDecoder.materialize(document)

        // The first post's quote link decodes to
        // /message.php?...&numrep=74594002&ref=0&... — pin that exact numrep so a future
        // alphabet/regression slip is caught.
        val quoteAnchors = document.select("a[href*=numrep=]")
        assertTrue("materialize must surface clear numrep anchors", quoteAnchors.isNotEmpty())
        assertTrue(
            "first post quote link must carry numrep=74594002",
            quoteAnchors.any { it.attr("href").contains("numrep=74594002") },
        )
    }

    @Test
    fun `materialize is idempotent`() {
        val document = Jsoup.parse(fixture("topic_loisirs_chutes_p4344.html"))
        CryptlinkDecoder.materialize(document)
        val afterFirst = document.select("a[href*=numrep=]").size
        CryptlinkDecoder.materialize(document)
        assertEquals(
            "a second materialize must not duplicate anchors",
            afterFirst,
            document.select("a[href*=numrep=]").size,
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()

    private companion object {
        // Real forum-logo header cryptlink suffix → decodes to "https://www.hardware.fr".
        const val LOGO_HEX = "45CBCBC0C22D1F1FCCCCCC19454AC14BCC4AC1431944C1"
    }
}
