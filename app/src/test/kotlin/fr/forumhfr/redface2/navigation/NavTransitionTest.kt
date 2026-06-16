package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the topic-scene marker contract used by the instant `TopicRoute → TopicRoute` NavDisplay
 * transition (#282). The transition reads this marker off a scene's top NavEntry metadata; if the
 * lookup silently returned the wrong answer the page-change would degrade to the shared-axis X slide
 * (re-introducing the swipe dead-zone) without any crash. These cover the lookup + the edge cases
 * (empty `entries` surfaces as a null metadata map).
 */
class NavTransitionTest {

    @Test
    fun `marker present and true is a topic scene`() {
        assertTrue(isTopicSceneMetadata(mapOf(TOPIC_SCENE_METADATA_KEY to true)))
    }

    @Test
    fun `null metadata is not a topic scene`() {
        // entries.lastOrNull() on an empty scene yields null -> must be treated as non-topic.
        assertFalse(isTopicSceneMetadata(null))
    }

    @Test
    fun `empty metadata is not a topic scene`() {
        assertFalse(isTopicSceneMetadata(emptyMap()))
    }

    @Test
    fun `marker explicitly false is not a topic scene`() {
        assertFalse(isTopicSceneMetadata(mapOf(TOPIC_SCENE_METADATA_KEY to false)))
    }

    @Test
    fun `unrelated metadata keys are not a topic scene`() {
        assertFalse(isTopicSceneMetadata(mapOf("some.other.key" to true)))
    }

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
