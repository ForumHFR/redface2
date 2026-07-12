package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure drill-down predicate that drives the forward NavDisplay transition. The historical
 * topic-scene marker tests left with the marker itself (#895 étape 5) : page changes no longer
 * traverse the navigation, so there is no instant `TopicRoute → TopicRoute` special case to pin.
 */
class NavTransitionTest {

    // --- isForwardDrillDown (#494 transitions) ---------------------------------------------------
    // A forward navigation is a drill-down (shared-axis X) iff the target stack is exactly the source
    // stack + one pushed top. We compare WHOLE stacks (the target minus its top vs the full source),
    // not just the last key: two tabs can share a route value (CategoryRoute(cat=23) lives in both
    // Drapeaux and Forums), and a last-key-only check would slide a tab switch. Using entries.first()
    // as a "root" also fails — nav3's SinglePaneScene exposes only the visible top in entries.

    @Test
    fun `target parent stack equal to source stack is a drill-down`() {
        // Forums [Forum, Category] -> [Forum, Category, Topic] : target-minus-top == source.
        assertTrue(
            isForwardDrillDown(
                sourceStack = listOf("Forum", "Category"),
                targetParentStack = listOf("Forum", "Category"),
            ),
        )
    }

    @Test
    fun `depth-1 drill-down is a drill-down`() {
        assertTrue(isForwardDrillDown(sourceStack = listOf("Flags"), targetParentStack = listOf("Flags")))
    }

    @Test
    fun `empty target parent stack (tab root) is not a drill-down`() {
        // Switching to a tab sitting at its root: target-minus-top is empty -> tab switch.
        assertFalse(isForwardDrillDown(sourceStack = listOf("Flags"), targetParentStack = emptyList()))
    }

    @Test
    fun `cross-tab navigation with a shared route value is not a drill-down`() {
        // Codex P3: Flags top = Category(23) ; Forum tab = [Forum, Category(23), Topic]. The last keys
        // match (both Category(23)) but the full stacks differ -> must stay a tab switch (fade-through).
        assertFalse(
            isForwardDrillDown(
                sourceStack = listOf("FlagsList", "Category23"),
                targetParentStack = listOf("Forum", "Category23"),
            ),
        )
    }

    @Test
    fun `both stacks empty is not a drill-down`() {
        assertFalse(isForwardDrillDown(sourceStack = emptyList(), targetParentStack = emptyList()))
    }
}
