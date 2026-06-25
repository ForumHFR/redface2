package fr.forumhfr.redface2.core.ui.theme

import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the bucket → color mapping of [FlagPalette.colorFor] (#603). The marker refonte resolves the
 * favori-wins rule to a [FlagType] in `:core:model` and then calls `colorFor(effectiveFlagColor(...))`,
 * so `colorFor(FAVORITE) == Favorite` MUST hold for the favorited row to keep its yellow marker
 * (parity with the former direct `FlagPalette.Favorite` access).
 */
class FlagPaletteTest {

    @Test
    fun `colorFor maps each bucket to its palette color`() {
        assertEquals(FlagPalette.Cyan, FlagPalette.colorFor(FlagType.CYAN))
        assertEquals(FlagPalette.Red, FlagPalette.colorFor(FlagType.RED))
        assertEquals(FlagPalette.Favorite, FlagPalette.colorFor(FlagType.FAVORITE))
    }
}
