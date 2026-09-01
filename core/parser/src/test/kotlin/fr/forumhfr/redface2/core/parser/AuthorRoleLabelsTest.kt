package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.AuthorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapping partagé `libellé HFR → AuthorRole` (#1112, #221), utilisé par `StaffParser` et
 * `ProfileParser.parseAuthorRole` — testé une fois ici pour garantir la cohérence des deux sources.
 */
class AuthorRoleLabelsTest {

    @Test
    fun `maps every known HFR label to its role`() {
        assertEquals(AuthorRole.MEMBER, authorRoleFromLabel("Membre"))
        assertEquals(AuthorRole.MODERATOR, authorRoleFromLabel("Modérateur"))
        assertEquals(AuthorRole.ADMIN, authorRoleFromLabel("Administrateur"))
        assertEquals(AuthorRole.SUPER_ADMIN, authorRoleFromLabel("Super Administrateur"))
        assertEquals(AuthorRole.DEVELOPER, authorRoleFromLabel("Développeur"))
        assertEquals(AuthorRole.ARCHITECT, authorRoleFromLabel("Architecte / Développeur principal"))
    }

    @Test
    fun `trims surrounding whitespace before matching`() {
        assertEquals(AuthorRole.MODERATOR, authorRoleFromLabel("  Modérateur  "))
    }

    @Test
    fun `returns null for an unknown or empty label`() {
        assertNull(authorRoleFromLabel("Grand Manitou"))
        assertNull(authorRoleFromLabel(""))
        assertNull(authorRoleFromLabel("moderateur")) // mapping exact : casse/accents comptent
    }
}
