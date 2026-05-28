package fr.forumhfr.redface2.core.parser.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParserTest {

    private val parser = ProfileParser()

    // ─── XaTriX fixture (authenticated capture, userId=54596) ────────────────

    @Test
    fun `parse XaTriX profile - userId passed through`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        assertEquals(54596, profile.userId)
    }

    @Test
    fun `parse XaTriX profile - pseudo extracted`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        assertEquals("XaTriX", profile.pseudo)
    }

    @Test
    fun `parse XaTriX profile - postCount extracted`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        assertEquals(213400, profile.postCount)
    }

    @Test
    fun `parse XaTriX profile - registeredAt extracted`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        assertEquals("12/06/2002", profile.registeredAt)
    }

    @Test
    fun `parse XaTriX profile - location extracted`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        assertEquals("Katowice (PL)", profile.location)
    }

    @Test
    fun `parse XaTriX profile - avatarUrl reconstructed from mesdiscussions pattern`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        // The fixture was saved from a browser and has a local relative path.
        // The parser should reconstruct the canonical CDN URL from the userId in the filename.
        assertNotNull("avatarUrl should not be null", profile.avatarUrl)
        assertTrue(
            "avatarUrl should contain mesdiscussions-54596",
            profile.avatarUrl!!.contains("54596"),
        )
    }

    @Test
    fun `parse XaTriX profile - signature extracted when not blank`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        // XaTriX has a signature "Proxytaf ? non rien"
        assertNotNull("Signature should be present for XaTriX", profile.signatureHtml)
        assertTrue(
            "Signature should contain some text",
            profile.signatureHtml!!.isNotBlank(),
        )
    }

    // ─── ezzz fixture (anonymous capture, userId=15867) ──────────────────────

    @Test
    fun `parse ezzz profile - userId passed through`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        assertEquals(15867, profile.userId)
    }

    @Test
    fun `parse ezzz profile - pseudo extracted`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        assertEquals("ezzz", profile.pseudo)
    }

    @Test
    fun `parse ezzz profile - postCount extracted`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        assertEquals(44362, profile.postCount)
    }

    @Test
    fun `parse ezzz profile - registeredAt extracted`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        assertEquals("02/02/2000", profile.registeredAt)
    }

    @Test
    fun `parse ezzz profile - location is null when empty`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        // ezzz has no location in the fixture
        assertNull("ezzz location should be null (empty in HTML)", profile.location)
    }

    @Test
    fun `parse ezzz profile - avatarUrl reconstructed from mesdiscussions pattern`() {
        val profile = parser.parse(fixture("profile/profile_ezzz_anonymous.html"), 15867)
        assertNotNull("avatarUrl should not be null for ezzz", profile.avatarUrl)
        assertTrue(
            "avatarUrl should contain mesdiscussions-15867",
            profile.avatarUrl!!.contains("15867"),
        )
    }

    // ─── Robustness: missing page does not crash ──────────────────────────────

    @Test
    fun `parse empty HTML does not throw`() {
        // An empty or minimal page must not throw — returns a profile with the
        // provided userId and fallback pseudo "?".
        val profile = parser.parse("<html><body></body></html>", 99999)
        assertEquals(99999, profile.userId)
        assertNull(profile.postCount)
        assertNull(profile.registeredAt)
        assertNull(profile.location)
        assertNull(profile.avatarUrl)
        assertNull(profile.signatureHtml)
    }

    @Test
    fun `parse page with only title falls back to page title for pseudo`() {
        val html = """
            <html>
            <head><title>TestUser - FORUM HardWare.fr</title></head>
            <body></body>
            </html>
        """.trimIndent()
        val profile = parser.parse(html, 12345)
        assertEquals("TestUser", profile.pseudo)
    }

    @Test
    fun `rawFields preserves fields not promoted to typed properties`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        // Profession / Loisirs / Citation personnelle are expected in rawFields
        // because they are not promoted to typed fields in Phase 2 finish.
        assertTrue(
            "rawFields should not be empty for XaTriX",
            profile.rawFields.isNotEmpty(),
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
}
