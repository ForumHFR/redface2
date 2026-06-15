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
    // A forward navigation is a drill-down (shared-axis X) iff the target's immediate parent — the
    // entry just below its top, taken from previousEntries — is the source scene's top entry. Using
    // entries.first() (a stack "root") instead would misclassify deep pushes (Forums → Catégorie →
    // Topic) as tab switches and skip the slide; these pin the parent-matches-top contract.

    @Test
    fun `target parent equal to source top is a drill-down`() {
        assertTrue(isForwardDrillDown(fromTopContentKey = "ForumCategory", targetParentContentKey = "ForumCategory"))
    }

    @Test
    fun `null target parent (tab root) is not a drill-down`() {
        // A scene whose top is the stack root has empty previousEntries -> null parent -> tab switch.
        assertFalse(isForwardDrillDown(fromTopContentKey = "Flags", targetParentContentKey = null))
    }

    @Test
    fun `target parent different from source top is not a drill-down`() {
        // Switching to another tab whose top sits over a different parent: not a push from here.
        assertFalse(isForwardDrillDown(fromTopContentKey = "Flags", targetParentContentKey = "Forum"))
    }

    @Test
    fun `both null is not a drill-down`() {
        assertFalse(isForwardDrillDown(fromTopContentKey = null, targetParentContentKey = null))
    }
}
