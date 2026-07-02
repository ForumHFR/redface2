package fr.forumhfr.redface2.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the legacy HFR deep link mapping. Before Phase 1C-A the
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
}
