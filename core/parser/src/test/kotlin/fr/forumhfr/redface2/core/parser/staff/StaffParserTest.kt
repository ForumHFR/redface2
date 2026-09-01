package fr.forumhfr.redface2.core.parser.staff

import fr.forumhfr.redface2.core.model.AuthorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StaffParserTest {

    private val parser = StaffParser()

    // ─── Fixture réelle anonyme (annuaire staff global, 64 entrées) ────────────

    @Test
    fun `parse real staff directory - exact entry count`() {
        val staff = parser.parse(fixture("staff/staff_responsables_anonymous.html"))
        assertEquals("64 ancres staff dans la capture réelle", 64, staff.size)
    }

    @Test
    fun `parse real staff directory - exact role distribution`() {
        val counts = parser.parse(fixture("staff/staff_responsables_anonymous.html"))
            .values
            .groupingBy { it }
            .eachCount()
        // Les cinq libellés staff restent distincts jusqu'à l'UI (#221).
        assertEquals(
            mapOf(
                AuthorRole.MODERATOR to 55,
                AuthorRole.ADMIN to 2,
                AuthorRole.SUPER_ADMIN to 3,
                AuthorRole.DEVELOPER to 1,
                AuthorRole.ARCHITECT to 3,
            ),
            counts,
        )
    }

    @Test
    fun `parse real staff directory - representative pseudo to role mappings`() {
        val staff = parser.parse(fixture("staff/staff_responsables_anonymous.html"))
        assertEquals(AuthorRole.MODERATOR, staff["Ernestor"]) // (Modérateur)
        assertEquals(AuthorRole.ADMIN, staff["La Monne"]) // (Administrateur)
        assertEquals(AuthorRole.SUPER_ADMIN, staff["antp"]) // (Super Administrateur)
        assertEquals(AuthorRole.DEVELOPER, staff["The-Shadow"]) // (Développeur)
        assertEquals(AuthorRole.ARCHITECT, staff["joce"]) // (Architecte / Développeur principal)
    }

    @Test
    fun `parse real staff directory - unescapes the JS-escaped apostrophe pseudo`() {
        val staff = parser.parse(fixture("staff/staff_responsables_anonymous.html"))
        // Le onclick sérialise fillfield_private('o\'gure') ; la clé brute doit être o'gure.
        assertEquals(AuthorRole.MODERATOR, staff["o'gure"])
    }

    @Test
    fun `parse keeps raw pseudos - no canonicalization at the parser`() {
        val staff = parser.parse(fixture("staff/staff_responsables_anonymous.html"))
        // Pseudos bruts (casse + caractères spéciaux préservés) ; la canonicalisation est au repo.
        assertEquals(AuthorRole.MODERATOR, staff["Je@nb"])
        assertEquals(AuthorRole.MODERATOR, staff["Dæmon"])
        assertFalse("le pseudo doit rester en casse brute", staff.containsKey("ernestor"))
    }

    // ─── Robustesse ────────────────────────────────────────────────────────────

    @Test
    fun `parse ignores an anchor whose role label is unknown`() {
        val html = """
            <table class="main"><tr class="cat"><td>
              <a href="#1" onclick="fillfield_private('KnownMod')" class="s1Topic">KnownMod <i>(Modérateur)</i></a>
              <a href="#1" onclick="fillfield_private('MysteryGuy')" class="s1Topic">MysteryGuy <i>(Grand Manitou)</i></a>
            </td></tr></table>
        """.trimIndent()

        val staff = parser.parse(html)

        assertEquals(mapOf("KnownMod" to AuthorRole.MODERATOR), staff)
        assertNull("un libellé inconnu doit être ignoré", staff["MysteryGuy"])
    }

    @Test
    fun `parse falls back to the anchor own text when the fillfield argument is missing`() {
        // fillfield_private() sans argument → repli sur ownText (le texte propre, HORS le <i>).
        val html = """
            <table class="main"><tr class="cat"><td>
              <a href="#1" onclick="fillfield_private()" class="s1Topic">FallbackGuy <i>(Modérateur)</i></a>
            </td></tr></table>
        """.trimIndent()

        val staff = parser.parse(html)

        assertEquals(mapOf("FallbackGuy" to AuthorRole.MODERATOR), staff)
    }

    @Test
    fun `parse returns an empty map on HTML without a staff table`() {
        assertEquals(emptyMap<String, AuthorRole>(), parser.parse("<html><body></body></html>"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) {
            "Fixture not found: $name"
        }.readText()
}
