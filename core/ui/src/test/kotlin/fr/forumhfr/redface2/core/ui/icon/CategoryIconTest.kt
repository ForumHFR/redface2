package fr.forumhfr.redface2.core.ui.icon

import fr.forumhfr.redface2.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure [categoryIcon] mapping (#603, spike 4). Comparisons are `R.drawable.X` vs
 * `R.drawable.X` so the test is robust to the actual generated resource ids.
 */
class CategoryIconTest {

    @Test
    fun `known categories map to their dedicated Material Symbol`() {
        assertEquals(R.drawable.ic_ms_memory, categoryIcon(1)) // Hardware
        assertEquals(R.drawable.ic_ms_sports_esports, categoryIcon(5)) // Jeux Video
        assertEquals(R.drawable.ic_ms_code, categoryIcon(10)) // Programmation
        assertEquals(R.drawable.ic_ms_terminal, categoryIcon(11)) // Linux et OS Alternatifs
        assertEquals(R.drawable.ic_ms_shopping_cart, categoryIcon(6)) // Achats & Ventes
        assertEquals(R.drawable.ic_ms_forum, categoryIcon(13)) // Discussions
    }

    @Test
    fun `an unknown category falls back to the generic forum glyph`() {
        assertEquals(R.drawable.ic_ms_forum, categoryIcon(24)) // Blabla — not in the REST catalogue
        assertEquals(R.drawable.ic_ms_forum, categoryIcon(0)) // moderation space
        assertEquals(R.drawable.ic_ms_forum, categoryIcon(9999)) // arbitrary unknown
    }

    @Test
    fun `every public category resolves to a non-zero drawable`() {
        val publicCats = listOf(1, 16, 15, 2, 30, 23, 25, 3, 14, 5, 4, 22, 21, 11, 10, 12, 6, 8, 13)
        publicCats.forEach { catId ->
            assertEquals(
                "cat $catId must resolve",
                true,
                categoryIcon(catId) != 0,
            )
        }
    }
}
