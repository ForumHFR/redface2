package fr.forumhfr.redface2.core.parser.profile

import fr.forumhfr.redface2.core.model.AuthorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // Review feedback M8: assert the exact reconstructed URL, not a substring — the
        // substring match would still pass if the parser regressed to a malformed URL
        // that happened to contain "54596" somewhere in the path.
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/54596/mesdiscussions-54596.png",
            profile.avatarUrl,
        )
    }

    @Test
    fun `parse XaTriX profile - signature extracted as plain text without HTML tags`() {
        val profile = parser.parse(fixture("profile/profile_xatrix_authenticated.html"), 54596)
        // Review feedback C1: the signature in the fixture is HTML
        // (`Proxytaf ? non rien<br><div style="clear: both;"> </div>&nbsp;`) and the
        // legacy `signatureHtml` was rendered verbatim in the UI, showing the raw
        // `<br>` / `<div>` tags as literal characters. The parser now flattens to text
        // (`Jsoup.text()`) so the UI can Text(...) it directly. Asserting the exact
        // plain-text value guards against a regression that would leak HTML tags back
        // into the field.
        assertEquals("Proxytaf ? non rien", profile.signatureText)
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
        // Review feedback M8: exact URL, not a substring match.
        assertEquals(
            "https://forum-images.hardware.fr/images/perso/15867/mesdiscussions-15867.png",
            profile.avatarUrl,
        )
    }

    // ─── Author role (#1112, #221) — « Statut » → AuthorRole ─────────────────

    @Test
    fun `parseAuthorRole maps Moderateur to MODERATOR from the anonymous fixture`() {
        // Fixture réelle capturée en anonyme (Ernestor, userId=15461, Statut=Modérateur).
        val role = parser.parseAuthorRole(fixture("profile/profile_moderator_anonymous.html"))
        assertEquals(AuthorRole.MODERATOR, role)
    }

    @Test
    fun `parseAuthorRole maps Membre to MEMBER from the anonymous fixture`() {
        // La fixture membre anonyme existante (ezzz, userId=15867) porte « Statut : Membre ».
        val role = parser.parseAuthorRole(fixture("profile/profile_ezzz_anonymous.html"))
        assertEquals(AuthorRole.MEMBER, role)
    }

    @Test
    fun `parseAuthorRole returns null when the Statut row is absent`() {
        // Pas de fabrication d'un faux profil : le cas « statut absent/inconnu » est prouvé
        // par la page dégénérée (aucune tr.profil « Statut ») → branche else → null.
        val role = parser.parseAuthorRole("<html><body></body></html>")
        assertNull(role)
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
        assertNull(profile.signatureText)
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
        // In the XaTriX fixture, the fields that survive into rawFields are those
        // with non-empty key AND non-empty value (the parser filters via
        // `if (key.isNotEmpty() && value.isNotEmpty())`). Fields like "Profession",
        // "Loisirs", and "Citation personnelle" are empty cells (&#160; / &nbsp;) in
        // this fixture, so they are filtered out. The fields actually present are
        // "Sexe" ("homme") and "Configuration matérielle".
        assertTrue(
            "rawFields should not be empty for XaTriX",
            profile.rawFields.isNotEmpty(),
        )
        assertTrue(
            "rawFields should contain 'Sexe' for XaTriX",
            "Sexe" in profile.rawFields,
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
}
