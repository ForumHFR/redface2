package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure priority resolver for the initial scroll of a topic page landing (#307, #412):
 * route `scrollTo` > post-submit landing (`submitSignal`, #344) > saved anchor >
 * previous-page bottom (#412) > top.
 */
class TopicScrollRestorationTest {

    private val savedAnchor = TopicScrollAnchor(index = 7, offset = 123)

    @Test
    fun `level 1 - route scrollTo wins over everything`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = 4242,
            submitSignal = 1_000L,
            savedAnchor = savedAnchor,
        )

        assertEquals(TopicScrollRestoration.FollowScrollTo, restoration)
    }

    @Test
    fun `level 2 - a post-submit route blocks the saved anchor even without scrollTo`() {
        // #344 — plain-reply landing (and the #226 overflow landing, which always carries a fresh
        // submitSignal): the ViewModel emits ScrollToEndOfPage, so the saved anchor must yield.
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = 1_000L,
            savedAnchor = savedAnchor,
        )

        assertEquals(TopicScrollRestoration.FollowSubmitLanding, restoration)
    }

    @Test
    fun `level 2 - post-submit route without a saved anchor still defers to the submit landing`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = 1_000L,
            savedAnchor = null,
        )

        assertEquals(TopicScrollRestoration.FollowSubmitLanding, restoration)
    }

    @Test
    fun `level 3 - a plain landing with a saved anchor restores it`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = null,
            savedAnchor = savedAnchor,
        )

        assertEquals(TopicScrollRestoration.RestoreSaved(savedAnchor), restoration)
    }

    @Test
    fun `level 3 - a header-visible anchor is restored raw (item 0 plus offset)`() {
        // The anchor is the raw (firstVisibleItemIndex, firstVisibleItemScrollOffset) pair: item 0
        // is the header card, so a departure mid-header restores mid-header — the offset is never
        // re-applied to the first visible post (which would be wrong whenever the header shows).
        val headerAnchor = TopicScrollAnchor(index = 0, offset = 137)

        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = null,
            savedAnchor = headerAnchor,
        )

        assertEquals(TopicScrollRestoration.RestoreSaved(headerAnchor), restoration)
    }

    @Test
    fun `level 3 - a saved anchor beats the previous-page bottom landing`() {
        // #412 — the issue is explicit: « page précédente SANS position retenue ». A retained
        // position is what the user wants back, whatever direction brought them here.
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = null,
            savedAnchor = savedAnchor,
            previousPageLanding = true,
        )

        assertEquals(TopicScrollRestoration.RestoreSaved(savedAnchor), restoration)
    }

    @Test
    fun `level 4 - previous-page navigation without a saved anchor lands at the bottom`() {
        // #412 — reading backwards: the next posts to read are the last of the previous page.
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = null,
            savedAnchor = null,
            previousPageLanding = true,
        )

        assertEquals(TopicScrollRestoration.StartAtBottom, restoration)
    }

    @Test
    fun `level 1 and 2 - scrollTo and submit landings ignore the previous-page flag`() {
        // A quote/edit submit pops back onto « page - 1 » in some flows — the scroll effects own
        // those landings, the #412 bottom fallback must never compete with them.
        assertEquals(
            TopicScrollRestoration.FollowScrollTo,
            resolveTopicScrollRestoration(
                scrollTo = 4242,
                submitSignal = null,
                savedAnchor = null,
                previousPageLanding = true,
            ),
        )
        assertEquals(
            TopicScrollRestoration.FollowSubmitLanding,
            resolveTopicScrollRestoration(
                scrollTo = null,
                submitSignal = 1_000L,
                savedAnchor = null,
                previousPageLanding = true,
            ),
        )
    }

    @Test
    fun `level 5 - a never-visited page lands at the top`() {
        val restoration = resolveTopicScrollRestoration(
            scrollTo = null,
            submitSignal = null,
            savedAnchor = null,
        )

        assertEquals(TopicScrollRestoration.StartAtTop, restoration)
    }
}
