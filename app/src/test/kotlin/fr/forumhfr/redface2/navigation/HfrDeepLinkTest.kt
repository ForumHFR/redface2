package fr.forumhfr.redface2.navigation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for HFR inbound links. Before Phase 1C-A the legacy
 * `forum1.php` / `forum2.php` parsing was inverted: clicking on a topic-content URL
 * (`forum2.php?cat=…&post=…`) ended up in the Category screen instead of the Topic
 * reader, and the topic-list URL (`forum1.php?cat=…`) required a non-existent `post`
 * parameter so it never matched. Tests below pin the corrected behavior.
 */
@RunWith(RobolectricTestRunner::class)
class HfrDeepLinkTest {

    @Test
    fun `forum1 php with cat preserves subcat and page in the CategoryRoute`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?cat=23&subcat=550&page=2")

        val parsed = parseHfrDeepLink(uri)

        val expectedRoute = CategoryRoute(cat = 23, subcat = 550, page = 2)
        assertEquals(expectedRoute, parsed?.route)
        assertEquals(TopLevelDestination.Forum, parsed?.destination)
    }

    @Test
    fun `forum1 php without page defaults to page 1`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?cat=23&subcat=550")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(CategoryRoute(cat = 23, subcat = 550, page = 1), parsed?.route)
    }

    @Test
    fun `forum1 php without subcat still resolves to a CategoryRoute on page 1`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?cat=13")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(CategoryRoute(cat = 13, subcat = null, page = 1), parsed?.route)
    }

    @Test
    fun `forum1 php with malformed page falls back to page 1`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?cat=23&page=abc")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(CategoryRoute(cat = 23, subcat = null, page = 1), parsed?.route)
    }

    @Test
    fun `forum1 php with negative page falls back to page 1`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?cat=23&page=-5")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(CategoryRoute(cat = 23, subcat = null, page = 1), parsed?.route)
    }

    @Test
    fun `forum2 php with cat and post lands on the Flags tab as a TopicRoute with scrollTo`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum2.php?cat=23&post=35395&page=12#t12345")

        val parsed = parseHfrDeepLink(uri)

        // page > 1 is explicit and trusted — no resolution probe on arrival.
        val expectedRoute = TopicRoute(cat = 23, post = 35395, page = 12, scrollTo = 12_345)
        assertEquals(expectedRoute, parsed?.route)
        assertEquals(TopLevelDestination.Flags, parsed?.destination)
    }

    @Test
    fun `forum2 php without page defaults to page 1 and no scrollTo`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum2.php?cat=23&post=35395")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(
            TopicRoute(cat = 23, post = 35395, page = 1, scrollTo = null),
            parsed?.route,
        )
    }

    @Test
    fun `forum2 php email link with page 1 and an anchor marks the page for resolution (#750)`() {
        // Real-world email-notification shape (issue #750): page=1 is a lie, the target
        // travels both as the `numreponse` query param and the `#t` fragment.
        val uri = Uri.parse(
            "https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&subcat=550&post=35395" +
                "&page=1&p=1&sondage=0&owntopic=0&numreponse=2789981&nojs=0#t2789981",
        )

        val parsed = parseHfrDeepLink(uri)

        assertEquals(
            TopicRoute(cat = 23, post = 35395, page = 1, scrollTo = 2_789_981, resolveScrollToPage = true),
            parsed?.route,
        )
    }

    @Test
    fun `forum2 php numreponse query param is the scrollTo fallback when the fragment is stripped (#750)`() {
        val uri = Uri.parse(
            "https://forum.hardware.fr/forum2.php?cat=23&post=35395&page=1&numreponse=2789981",
        )

        val parsed = parseHfrDeepLink(uri)

        assertEquals(
            TopicRoute(cat = 23, post = 35395, page = 1, scrollTo = 2_789_981, resolveScrollToPage = true),
            parsed?.route,
        )
    }

    @Test
    fun `forum2 php explicit page with anchor is trusted, no resolution (#750)`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum2.php?cat=23&post=35395&page=7#t999")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(
            TopicRoute(cat = 23, post = 35395, page = 7, scrollTo = 999, resolveScrollToPage = false),
            parsed?.route,
        )
    }

    @Test
    fun `forum1f php is the drapeaux entry point`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1f.php?owntopic=1")

        val parsed = parseHfrDeepLink(uri)

        assertEquals(FlagsListRoute, parsed?.route)
        assertEquals(TopLevelDestination.Flags, parsed?.destination)
    }

    @Test
    fun `unknown paths return null`() {
        val uri = Uri.parse("https://forum.hardware.fr/somewhere-else.php?cat=23")

        assertNull(parseHfrDeepLink(uri))
    }

    @Test
    fun `forum1 php with no cat returns null`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum1.php?subcat=550")

        assertNull(parseHfrDeepLink(uri))
    }

    @Test
    fun `forum2 php with no post returns null`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum2.php?cat=23")

        assertNull(parseHfrDeepLink(uri))
    }

    @Test
    fun `the 20 live category slugs resolve to their numeric category`() {
        val cases = listOf(
            "Hardware" to 1,
            "HardwarePeripheriques" to 16,
            "OrdinateursPortables" to 15,
            "OverclockingCoolingModding" to 2,
            "ElectroniqueDomotiqueDIY" to 30,
            "GSMGPSPDA" to 23,
            "Apple" to 25,
            "VideoSon" to 3,
            "Photonumerique" to 14,
            "JeuxVideo" to 5,
            "WindowsSoftware" to 4,
            "ReseauxPersoSoho" to 22,
            "SystemeReseauxPro" to 21,
            "OSAlternatifs" to 11,
            "Programmation" to 10,
            "IA" to 32,
            "Graphisme" to 12,
            "AchatsVentes" to 6,
            "EmploiEtudes" to 8,
            "Discussions" to 13,
        )

        cases.forEach { (slug, cat) ->
            val uri = Uri.parse("https://forum.hardware.fr/hfr/$slug/topic-sujet_123_2.htm")

            assertEquals(
                "Unexpected category mapping for $slug",
                HfrDeepLinkResolution.Route(
                    ParsedDeepLink(
                        destination = TopLevelDestination.Flags,
                        route = TopicRoute(cat = cat, post = 123, page = 2),
                    ),
                ),
                resolveHfrDeepLink(viewIntent(uri)),
            )
        }
    }

    @Test
    fun `pretty cat 23 URL preserves explicit page and anchor without page resolution`() {
        val uri = Uri.parse(
            "https://forum.hardware.fr/hfr/GSMGPSPDA/android/" +
                "redface-dev-sujet_35421_3.htm#t2786758",
        )

        assertEquals(
            HfrDeepLinkResolution.Route(
                ParsedDeepLink(
                    destination = TopLevelDestination.Flags,
                    route = TopicRoute(
                        cat = 23,
                        post = 35_421,
                        page = 3,
                        scrollTo = 2_786_758,
                        resolveScrollToPage = false,
                    ),
                ),
            ),
            resolveHfrDeepLink(viewIntent(uri)),
        )
    }

    @Test
    fun `pretty cat 32 page 1 URL with anchor requests page resolution`() {
        val uri = Uri.parse(
            "https://forum.hardware.fr/hfr/ia/agents/topic-name-sujet_1032_1.htm#t987654",
        )

        assertEquals(
            HfrDeepLinkResolution.Route(
                ParsedDeepLink(
                    destination = TopLevelDestination.Flags,
                    route = TopicRoute(
                        cat = 32,
                        post = 1_032,
                        page = 1,
                        scrollTo = 987_654,
                        resolveScrollToPage = true,
                    ),
                ),
            ),
            resolveHfrDeepLink(viewIntent(uri)),
        )
    }

    @Test
    fun `legacy topic URL resolves through the inbound intent gate`() {
        val uri = Uri.parse("https://forum.hardware.fr/forum2.php?cat=23&post=35395&page=7#t999")

        assertEquals(
            HfrDeepLinkResolution.Route(
                ParsedDeepLink(
                    destination = TopLevelDestination.Flags,
                    route = TopicRoute(cat = 23, post = 35_395, page = 7, scrollTo = 999),
                ),
            ),
            resolveHfrDeepLink(viewIntent(uri)),
        )
    }

    @Test
    fun `unknown pretty category falls back to the browser`() {
        val uri = Uri.parse("https://forum.hardware.fr/hfr/Unknown/topic-sujet_123_2.htm#t456")

        assertEquals(
            HfrDeepLinkResolution.BrowserFallback(uri),
            resolveHfrDeepLink(viewIntent(uri)),
        )
    }

    @Test
    fun `private message legacy URLs fall back to the browser`() {
        val urls = listOf(
            "https://forum.hardware.fr/forum1.php?cat=prive&page=2",
            "https://forum.hardware.fr/forum2.php?cat=prive&post=123&page=4",
        )

        urls.forEach { url ->
            val uri = Uri.parse(url)
            assertEquals(
                HfrDeepLinkResolution.BrowserFallback(uri),
                resolveHfrDeepLink(viewIntent(uri)),
            )
        }
    }

    @Test
    fun `non-topic HFR pretty paths fall back to the browser`() {
        val urls = listOf(
            "https://forum.hardware.fr/hfr/profil-123.htm",
            "https://forum.hardware.fr/hfr/Hardware/liste_sujet-2.htm",
            "https://forum.hardware.fr/hfr/Hardware/another-page.htm",
        )

        urls.forEach { url ->
            val uri = Uri.parse(url)
            assertEquals(
                HfrDeepLinkResolution.BrowserFallback(uri),
                resolveHfrDeepLink(viewIntent(uri)),
            )
        }
    }

    @Test
    fun `wrong action host or scheme is ignored`() {
        val intents = listOf(
            Intent(
                Intent.ACTION_SEND,
                Uri.parse("https://forum.hardware.fr/hfr/ia/topic-sujet_123_1.htm"),
            ),
            viewIntent(Uri.parse("https://example.com/hfr/ia/topic-sujet_123_1.htm")),
            viewIntent(Uri.parse("ftp://forum.hardware.fr/hfr/ia/topic-sujet_123_1.htm")),
        )

        intents.forEach { intent ->
            assertEquals(HfrDeepLinkResolution.Ignore, resolveHfrDeepLink(intent))
        }
    }

    private fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri)
}
