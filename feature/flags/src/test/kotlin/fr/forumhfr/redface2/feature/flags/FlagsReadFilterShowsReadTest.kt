package fr.forumhfr.redface2.feature.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #661 — the picker dropdown's contextual « +lus » entry exists ONLY for the tabs that carry a read
 * filter (Cyan / DT) and its label follows their current state; the other tabs (Red / Favori / Super)
 * get no entry (null). Pure mapping → unit-tested.
 */
class FlagsReadFilterShowsReadTest {

    @Test
    fun `cyan reflects its read-shortcut state`() {
        assertEquals(true, flagsReadFilterShowsRead(FlagTab.Cyan, cyanShowsRead = true, dtShowsRead = false))
        assertEquals(false, flagsReadFilterShowsRead(FlagTab.Cyan, cyanShowsRead = false, dtShowsRead = true))
    }

    @Test
    fun `dt reflects its own read state`() {
        assertEquals(true, flagsReadFilterShowsRead(FlagTab.Dt, cyanShowsRead = false, dtShowsRead = true))
        assertEquals(false, flagsReadFilterShowsRead(FlagTab.Dt, cyanShowsRead = true, dtShowsRead = false))
    }

    @Test
    fun `tabs without a read filter have no entry`() {
        assertNull(flagsReadFilterShowsRead(FlagTab.Red, cyanShowsRead = true, dtShowsRead = true))
        assertNull(flagsReadFilterShowsRead(FlagTab.Favorite, cyanShowsRead = true, dtShowsRead = true))
        assertNull(flagsReadFilterShowsRead(FlagTab.Super, cyanShowsRead = true, dtShowsRead = true))
    }
}
