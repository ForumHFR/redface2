package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #256 — exhaustive pinning of the `hfr-cc-image=true` marker matching ([isCcImageUrl]) and of the
 * measurement-probe exclusion ([collectMeasurableImageUrls]). The matcher is deliberately strict:
 * exact case-sensitive name/value after percent-decoding, query component only, and the conservative
 * duplicate rule (any non-`true` occurrence disqualifies — see the KDoc in CcImageMarker.kt).
 */
class CcImageMarkerTest {

    // --- nominal shapes -------------------------------------------------------------------------

    @Test
    fun `nominal cc-image URL matches`() {
        // The real-world shape from the issue: …/emojis-micro/<codepoint>.png?hfr-cc-image=true&raw=true
        assertTrue(isCcImageUrl("https://cdn.example.org/emojis-micro/1f600.png?hfr-cc-image=true&raw=true"))
    }

    @Test
    fun `reordered query parameters still match`() {
        assertTrue(isCcImageUrl("https://cdn.example.org/emojis-micro/1f600.png?raw=true&hfr-cc-image=true"))
    }

    @Test
    fun `marker as the only parameter matches`() {
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=true"))
    }

    @Test
    fun `marker followed by a fragment matches (fragment excluded, query kept)`() {
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=true#section"))
    }

    @Test
    fun `scheme-less URL with the marker matches (parser guarantees http-s in production)`() {
        // Sample shape used by InlineImagePromotionTest — the matcher is scheme-agnostic on purpose.
        assertTrue(isCcImageUrl("emoji?hfr-cc-image=true"))
    }

    // --- percent-encoding -----------------------------------------------------------------------

    @Test
    fun `percent-encoded name still matches after decoding`() {
        // %68 = 'h' → the decoded name is exactly hfr-cc-image.
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?%68fr-cc-image=true"))
    }

    @Test
    fun `percent-encoded value still matches after decoding`() {
        // %74 = 't' → the decoded value is exactly true.
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=%74rue"))
    }

    @Test
    fun `malformed percent-encoding in the marker value disqualifies`() {
        // The name matches but the value cannot be decoded → treated as a non-true occurrence.
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=%zz"))
    }

    @Test
    fun `malformed percent-encoding in an unrelated parameter is ignored`() {
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?a=%zz&hfr-cc-image=true"))
    }

    @Test
    fun `plus decodes to a space and disqualifies the value`() {
        // application/x-www-form-urlencoded: "+true" decodes to " true" ≠ "true".
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=+true"))
    }

    // --- value rule -----------------------------------------------------------------------------

    @Test
    fun `explicit false value does not match`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=false"))
    }

    @Test
    fun `valueless or empty-valued marker does not match`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image="))
    }

    @Test
    fun `matching is case-sensitive on both name and value`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?HFR-CC-IMAGE=true"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=TRUE"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=True"))
    }

    // --- duplicate rule (conservative: any non-true occurrence disqualifies) ---------------------

    @Test
    fun `duplicated true markers match`() {
        assertTrue(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=true&hfr-cc-image=true"))
    }

    @Test
    fun `duplicate true plus false disqualifies regardless of order`() {
        // The decided rule: "false wins" — ambiguity resolves to NOT fast-pathing (worst case is one
        // async probe and normal sizing; a wrong match would pin a real photo to a 16sp square).
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=true&hfr-cc-image=false"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image=false&hfr-cc-image=true"))
    }

    // --- component and name strictness ----------------------------------------------------------

    @Test
    fun `marker in the fragment only does not match`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png#hfr-cc-image=true"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?raw=true#hfr-cc-image=true"))
    }

    @Test
    fun `question mark inside the fragment is not a query`() {
        // The whole `#frag?hfr-cc-image=true` is a fragment: no query component exists at all.
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png#frag?hfr-cc-image=true"))
    }

    @Test
    fun `marker in the path only does not match`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/hfr-cc-image=true/e.png?raw=true"))
    }

    @Test
    fun `name matching is exact, not substring`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?xhfr-cc-image=true"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?hfr-cc-image2=true"))
    }

    @Test
    fun `no query, empty query or unparseable input does not match`() {
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png"))
        assertFalse(isCcImageUrl("https://cdn.example.org/e.png?"))
        assertFalse(isCcImageUrl(""))
        assertFalse(isCcImageUrl("not a url at all"))
        assertFalse(isCcImageUrl("http://[malformed"))
    }

    // --- measurement-probe exclusion (#256 companion change) -------------------------------------

    private fun img(url: String) = PostInline.InlineImage(url = url, description = null)

    @Test
    fun `cc-image URLs are excluded from the measurable set, plain URLs are kept`() {
        val urls = collectMeasurableImageUrls(
            listOf(
                img("https://i.example.org/photo.jpg"),
                img("https://cdn.example.org/e.png?hfr-cc-image=true&raw=true"),
            ),
        )
        assertEquals(setOf("https://i.example.org/photo.jpg"), urls)
    }

    @Test
    fun `exclusion also applies to nested cc-image URLs (Link Strong shape)`() {
        // The real cc-image nesting from the issue: Link → Strong → InlineImage.
        val nested = listOf(
            PostInline.Link(
                url = "https://youtu.be/x",
                children = listOf(
                    PostInline.Strong(
                        children = listOf(
                            PostInline.Text("Doublé à Most "),
                            img("https://cdn.example.org/e.png?hfr-cc-image=true"),
                        ),
                    ),
                ),
            ),
            img("https://i.example.org/photo.jpg"),
        )
        assertEquals(setOf("https://i.example.org/photo.jpg"), collectMeasurableImageUrls(nested))
    }
}
