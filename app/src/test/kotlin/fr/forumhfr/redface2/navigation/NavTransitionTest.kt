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
}
