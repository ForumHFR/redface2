package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1301 — the editor→below handoff decision. The three outcomes are mutually exclusive: a topic
 * entry gets the #895 étape 4 result and nothing else, the flags list gets the bounded
 * acknowledgement and nothing else, and anything else gets neither (arming a result there would
 * leave it in the single slot for a LATER, unrelated open of the topic).
 */
class SubmitHandoffTest {

    @Test
    fun `the matching topic entry gets the deferred result, never the acknowledgement`() {
        assertEquals(
            SubmitHandoff.TopicOutcome,
            submitHandoffFor(
                below = TopicRoute(cat = 25, post = 1234, page = 7),
                cat = 25,
                topicId = 1234,
            ),
        )
    }

    @Test
    fun `the flags list gets the acknowledgement, never the deferred result`() {
        assertEquals(
            SubmitHandoff.FlagsAcknowledgement,
            submitHandoffFor(below = FlagsListRoute, cat = 25, topicId = 1234),
        )
    }

    @Test
    fun `an editor with no topic still acknowledges on the flags list`() {
        assertEquals(
            SubmitHandoff.FlagsAcknowledgement,
            submitHandoffFor(below = FlagsListRoute, cat = 25, topicId = null),
        )
    }

    @Test
    fun `a non-matching topic entry gets neither`() {
        assertEquals(
            SubmitHandoff.None,
            submitHandoffFor(
                below = TopicRoute(cat = 25, post = 4321, page = 1),
                cat = 25,
                topicId = 1234,
            ),
        )
        assertEquals(
            SubmitHandoff.None,
            submitHandoffFor(
                below = TopicRoute(cat = 12, post = 1234, page = 1),
                cat = 25,
                topicId = 1234,
            ),
        )
    }

    @Test
    fun `any other entry below the editor gets neither`() {
        assertEquals(
            SubmitHandoff.None,
            submitHandoffFor(below = CategoryRoute(cat = 25), cat = 25, topicId = 1234),
        )
        assertEquals(SubmitHandoff.None, submitHandoffFor(below = null, cat = 25, topicId = 1234))
    }

    @Test
    fun `a topic entry never carries the handoff of an editor without a topic`() {
        assertEquals(
            SubmitHandoff.None,
            submitHandoffFor(
                below = TopicRoute(cat = 25, post = 1234, page = 1),
                cat = 25,
                topicId = null,
            ),
        )
    }
}
