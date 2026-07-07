package fr.forumhfr.redface2.core.ui.post

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #831 — pins the eligibility boundary of the image contextual menu: the long-press handler is
 * installed ONLY on fetchable http(s) post images. `data:` / `blob:` payloads, empty URLs and
 * exotic schemes must stay inert (no menu offering « open in browser » / « save » on something
 * that cannot be fetched), per the Codex framing (arbitrage #5).
 */
class PostImageUrlEligibilityTest {

    @Test
    fun `plain http and https URLs are eligible`() {
        assertTrue(isEligiblePostImageUrl("https://rehost.diberie.com/Picture/Get/f/123.png"))
        assertTrue(isEligiblePostImageUrl("http://forum-images.hardware.fr/images/perso/o.gif"))
    }

    @Test
    fun `scheme matching is case-insensitive and tolerates surrounding whitespace`() {
        assertTrue(isEligiblePostImageUrl("HTTPS://example.com/a.png"))
        assertTrue(isEligiblePostImageUrl("  https://example.com/a.png  "))
    }

    @Test
    fun `empty and blank URLs are refused`() {
        assertFalse(isEligiblePostImageUrl(""))
        assertFalse(isEligiblePostImageUrl("   "))
    }

    @Test
    fun `data and blob payloads are refused`() {
        assertFalse(isEligiblePostImageUrl("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(isEligiblePostImageUrl("blob:https://example.com/9115d58c"))
    }

    @Test
    fun `non-http schemes and relative paths are refused`() {
        assertFalse(isEligiblePostImageUrl("ftp://example.com/a.png"))
        assertFalse(isEligiblePostImageUrl("file:///sdcard/a.png"))
        assertFalse(isEligiblePostImageUrl("images/perso/o.gif"))
        assertFalse(isEligiblePostImageUrl("//example.com/protocol-relative.png"))
    }
}
