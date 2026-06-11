package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #415 — symmetry between [BUILTIN_HFR_SMILEYS] and HFR's own help page `smilies.php`
 * (fixture `smilies_help_page.html`, the canonical list of typed codes the server
 * interprets). The composer toolbar only shows a popular subset, which is how `:sweat:`
 * went missing from the picker. This test re-derives the (code → image URL) pairs from the
 * fixture and fails on any drift : missing code, extra code, or wrong URL.
 */
class BuiltinSmileysSymmetryTest {

    /**
     * The help page lists one row per smiley : `<tr><th>:code:</th><th><img src=...></th></tr>`
     * (code cell `cBackTab2`, image cell `cBackTab1`).
     */
    private fun fixturePairs(): Map<String, String> {
        val stream = requireNotNull(
            BuiltinSmileysSymmetryTest::class.java.classLoader
                ?.getResourceAsStream("fixtures/smilies_help_page.html"),
        ) { "Fixture not found: fixtures/smilies_help_page.html" }
        val html = stream.bufferedReader().use { it.readText() }
        val document = Jsoup.parse(html)
        val pairs = mutableMapOf<String, String>()
        document.select("tr").forEach { row ->
            val code = row.selectFirst("th.cBackTab2")?.text()?.trim()?.takeIf {
                it.startsWith(":") || it.startsWith(";")
            } ?: return@forEach
            val src = row.selectFirst("th.cBackTab1 img[src*=forum-images.hardware.fr/icones/]")
                ?.attr("src")
                ?.takeIf { it.endsWith(".gif") }
                ?: return@forEach
            pairs[code] = src
        }
        return pairs
    }

    @Test
    fun `the constant matches smilies-php exactly — every code, every URL`() {
        val expected = fixturePairs()
        assertTrue("fixture should yield a substantial list, got ${expected.size}", expected.size >= 50)

        val actual = BUILTIN_HFR_SMILEYS.associate { it.token to it.imageUrl }

        assertEquals(
            "codes present in smilies.php but missing from BUILTIN_HFR_SMILEYS",
            emptySet<String>(),
            expected.keys - actual.keys,
        )
        assertEquals(
            "codes present in BUILTIN_HFR_SMILEYS but absent from smilies.php",
            emptySet<String>(),
            actual.keys - expected.keys,
        )
        expected.forEach { (code, url) ->
            assertEquals("image URL drift for $code", url, actual[code])
        }
    }

    @Test
    fun `the beta-reported missing smiley is back`() {
        // DjullClint, topic principal t2787127 — the report that triggered #415.
        assertTrue(BUILTIN_HFR_SMILEYS.any { it.token == ":sweat:" })
    }
}
