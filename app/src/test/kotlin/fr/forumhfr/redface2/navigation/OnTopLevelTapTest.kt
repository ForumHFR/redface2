package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #603 PR6 / #679 — bottom-bar tap routing. A re-tap of the already-selected Drapeaux tab arms the
 * quick-config sheet ONLY at the tab root; from a sub-screen it pops to root instead (#679: arming from a
 * sub-screen made the sheet pop open on return to the list). A tap on another tab always switches; a re-tap
 * of any non-Flags tab is a plain switch (no special-case). Pure routing → unit-tested.
 */
class OnTopLevelTapTest {

    private val flags = TopLevelDestination.Flags
    private val forum = TopLevelDestination.Forum

    @Test
    fun `re-tap Flags at root arms the quick-config sheet`() {
        assertEquals(
            TopLevelTapAction.ReselectFlags,
            topLevelTapAction(tapped = flags, current = flags, flagsAtRoot = true),
        )
    }

    @Test
    fun `re-tap Flags from a sub-screen pops to root instead of arming the sheet`() {
        assertEquals(
            TopLevelTapAction.PopFlagsToRoot,
            topLevelTapAction(tapped = flags, current = flags, flagsAtRoot = false),
        )
    }

    @Test
    fun `tapping Flags from another tab switches (never pops or arms)`() {
        assertEquals(
            TopLevelTapAction.Switch,
            topLevelTapAction(tapped = flags, current = forum, flagsAtRoot = true),
        )
        assertEquals(
            TopLevelTapAction.Switch,
            topLevelTapAction(tapped = flags, current = forum, flagsAtRoot = false),
        )
    }

    @Test
    fun `re-tap of a non-Flags tab is a plain switch (no reselect special-case)`() {
        assertEquals(
            TopLevelTapAction.Switch,
            topLevelTapAction(tapped = forum, current = forum, flagsAtRoot = true),
        )
    }

    @Test
    fun `tapping a non-Flags tab from Flags switches`() {
        assertEquals(
            TopLevelTapAction.Switch,
            topLevelTapAction(tapped = forum, current = flags, flagsAtRoot = true),
        )
    }
}
