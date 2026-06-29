package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure visited-tab history for #667 (back at a secondary tab's root returns to the previously-visited
 * tab, not the system → no more app-exit). The history is an MRU stack of top-level tabs, the
 * most-recently-left LAST, excluding the current tab. Switching pushes the left tab; back pops it.
 * Codex flagged the oscillation risk — these tests pin the no-ping-pong contract.
 */
class TabBackStackTest {

    private val flags = TopLevelDestination.Flags
    private val forum = TopLevelDestination.Forum
    private val settings = TopLevelDestination.Settings

    @Test
    fun `switching pushes the left tab as the most-recent previous`() {
        assertEquals(listOf(flags), tabHistoryOnSwitch(emptyList(), current = flags, target = settings))
    }

    @Test
    fun `reselecting the current tab leaves the history unchanged`() {
        assertEquals(
            listOf(flags),
            tabHistoryOnSwitch(listOf(flags), current = settings, target = settings),
        )
    }

    @Test
    fun `switching dedups so a revisited tab is not duplicated`() {
        // On Forum with history [Flags, Settings], going (back forward) to Settings must not duplicate it.
        assertEquals(
            listOf(flags, forum),
            tabHistoryOnSwitch(listOf(flags, settings), current = forum, target = settings),
        )
    }

    @Test
    fun `back at a secondary root pops the most-recent visited tab`() {
        val result = tabBackTarget(listOf(flags, forum), fallback = flags)
        assertEquals(forum, result.target)
        assertEquals(listOf(flags), result.history)
    }

    @Test
    fun `back with an empty history falls back to Flags`() {
        val result = tabBackTarget(emptyList(), fallback = flags)
        assertEquals(flags, result.target)
        assertEquals(emptyList<TopLevelDestination>(), result.history)
    }

    @Test
    fun `successive backs walk the history without oscillating`() {
        var history = emptyList<TopLevelDestination>()
        history = tabHistoryOnSwitch(history, current = flags, target = forum) // [Flags]
        history = tabHistoryOnSwitch(history, current = forum, target = settings) // [Flags, Forum]
        val back1 = tabBackTarget(history, fallback = flags) // → Forum, [Flags]
        val back2 = tabBackTarget(back1.history, fallback = flags) // → Flags, []
        assertEquals(forum, back1.target)
        assertEquals(flags, back2.target)
        assertEquals(emptyList<TopLevelDestination>(), back2.history)
    }
}
