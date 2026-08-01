package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #661 → #751 — the picker dropdown's contextual « +lus » entry exists for every tab that carries a
 * read filter (all real tabs since #751 — Cyan / Red / Favori / DT) and its label follows their
 * current state; only the Super placeholder gets no entry (null). Pure mapping → unit-tested.
 */
class FlagsReadFilterShowsReadTest {

    private fun showsRead(
        tab: FlagTab,
        cyan: Boolean = false,
        dt: Boolean = false,
        red: Boolean = false,
        favorite: Boolean = false,
    ): Boolean? = flagsReadFilterShowsRead(
        tab = tab,
        shortcuts = FlagsReadShortcuts(cyan = cyan, dt = dt, red = red, favorite = favorite),
    )

    @Test
    fun `cyan reflects its read-shortcut state`() {
        assertEquals(true, showsRead(FlagTab.Cyan, cyan = true))
        assertEquals(false, showsRead(FlagTab.Cyan, dt = true, red = true, favorite = true))
    }

    @Test
    fun `dt reflects its own read state`() {
        assertEquals(true, showsRead(FlagTab.Dt, dt = true))
        assertEquals(false, showsRead(FlagTab.Dt, cyan = true, red = true, favorite = true))
    }

    @Test
    fun `red and favorite reflect their own read state`() {
        // #751 (thibw) — the shortcut used to skip these tabs entirely (null).
        assertEquals(true, showsRead(FlagTab.Red, red = true))
        assertEquals(false, showsRead(FlagTab.Red, cyan = true, dt = true, favorite = true))
        assertEquals(true, showsRead(FlagTab.Favorite, favorite = true))
        assertEquals(false, showsRead(FlagTab.Favorite, cyan = true, dt = true, red = true))
    }

    @Test
    fun `the Super placeholder has no entry`() {
        assertNull(showsRead(FlagTab.Super, cyan = true, dt = true, red = true, favorite = true))
    }
}
