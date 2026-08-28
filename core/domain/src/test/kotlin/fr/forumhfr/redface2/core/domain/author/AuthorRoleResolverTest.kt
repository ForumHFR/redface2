package fr.forumhfr.redface2.core.domain.author

import fr.forumhfr.redface2.core.model.AuthorRole
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorRoleResolverTest {

    @Test
    fun `resolves case NFC NBSP and format-character variants through canonicalization`() {
        val nfd = Normalizer.normalize("Crème Brûlée", Normalizer.Form.NFD)
        val nbsp = Char(0x00A0)
        val zeroWidthSpace = Char(0x200B)
        val staff = mapOf("crème brûlée" to AuthorRole.SUPER_ADMIN)

        assertEquals(AuthorRole.SUPER_ADMIN, resolve("CRÈME BRÛLÉE", staff))
        assertEquals(AuthorRole.SUPER_ADMIN, resolve(nfd, staff))
        assertEquals(AuthorRole.SUPER_ADMIN, resolve("Crème${nbsp}Brûlée", staff))
        assertEquals(AuthorRole.SUPER_ADMIN, resolve("Crème${zeroWidthSpace} Brûlée", staff))
    }

    @Test
    fun `returns null for an unknown author`() {
        assertNull(resolve("Inconnu", mapOf("modo" to AuthorRole.MODERATOR)))
    }

    @Test
    fun `filters member entries`() {
        assertNull(resolve("Membre", mapOf("membre" to AuthorRole.MEMBER)))
    }

    @Test
    fun `filters staff role on a moderation-system post`() {
        assertNull(
            resolveAuthorRolePill(
                author = "Modo",
                isModerationPost = true,
                staffByPseudo = mapOf("modo" to AuthorRole.MODERATOR),
            ),
        )
    }

    private fun resolve(author: String, staff: Map<String, AuthorRole>): AuthorRole? =
        resolveAuthorRolePill(
            author = author,
            isModerationPost = false,
            staffByPseudo = staff,
        )
}
