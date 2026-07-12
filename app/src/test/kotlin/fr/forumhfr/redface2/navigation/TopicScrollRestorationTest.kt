package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure priority resolver for the initial ENTRY scroll of a topic landing (#307):
 * route `scrollTo` > saved anchor > top.
 *
 * #895 étape 4 (PR 2) — the historical mid-topic levels (post-submit `submitSignal`, quote-jump
 * return #782, previous-page bottom #412) left this resolver: those landings are armed by the
 * in-VM page engine and covered by `TopicViewModelTest`.
 */
class TopicScrollRestorationTest {

    private val savedAnchor = TopicScrollAnchor(index = 7, offset = 123)

    @Test
    fun `level 1 - route scrollTo wins over the saved anchor`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = 4242,
            savedAnchor = savedAnchor,
        )

        assertEquals(TopicScrollRestoration.FollowScrollTo, restoration)
    }

    @Test
    fun `level 2 - a plain landing with a saved anchor restores it`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            savedAnchor = savedAnchor,
        )

        assertEquals(TopicScrollRestoration.RestoreSaved(savedAnchor), restoration)
    }

    @Test
    fun `level 2 - a header-visible anchor is restored raw (item 0 plus offset)`() {
        // The anchor is the raw (firstVisibleItemIndex, firstVisibleItemScrollOffset) pair: item 0
        // is the header card, so a departure mid-header restores mid-header — the offset is never
        // re-applied to the first visible post (which would be wrong whenever the header shows).
        val headerAnchor = TopicScrollAnchor(index = 0, offset = 137)

        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            savedAnchor = headerAnchor,
        )

        assertEquals(TopicScrollRestoration.RestoreSaved(headerAnchor), restoration)
    }

    @Test
    fun `level 3 - a never-visited page lands at the top`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            savedAnchor = null,
        )

        assertEquals(TopicScrollRestoration.StartAtTop, restoration)
    }
}
