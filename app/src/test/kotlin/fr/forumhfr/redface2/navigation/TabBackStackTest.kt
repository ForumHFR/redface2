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

    @Test
    fun `in-app HFR topic link pushes onto the active stack`() {
        val topicA = TopicRoute(cat = 23, post = 100, page = 4)
        val topicB = TopicRoute(cat = 23, post = 200, page = 7)

        assertEquals(
            listOf(FlagsListRoute, topicA, topicB),
            inAppRouteBackStackAfterOpen(listOf(FlagsListRoute, topicA), topicB),
        )
    }

    @Test
    fun `in-app topic link keeps the active tab continuity`() {
        val category = CategoryRoute(cat = 23)
        val topic = TopicRoute(cat = 23, post = 100, page = 4)
        val stacks = mapOf(
            flags to listOf(FlagsListRoute),
            forum to listOf(ForumRoute, category),
        )

        val result = inAppRouteBackStackAfterOpen(
            currentDestination = forum,
            parsed = ParsedDeepLink(destination = flags, route = topic),
            backStackFor = { destination -> stacks.getValue(destination) },
        )

        assertEquals(forum, result.destination)
        assertEquals(listOf(ForumRoute, category, topic), result.backStack)
    }

    @Test
    fun `in-app flags link switches to Flags and pops that tab to root`() {
        val topic = TopicRoute(cat = 23, post = 100, page = 4)
        val stacks = mapOf(
            flags to listOf(FlagsListRoute, topic),
            forum to listOf(ForumRoute),
        )

        val result = inAppRouteBackStackAfterOpen(
            currentDestination = forum,
            parsed = ParsedDeepLink(destination = flags, route = FlagsListRoute),
            backStackFor = { destination -> stacks.getValue(destination) },
        )

        assertEquals(flags, result.destination)
        assertEquals(listOf(FlagsListRoute), result.backStack)
    }

    @Test
    fun `in-app category link switches to Forum and pushes on the Forum stack`() {
        val category = CategoryRoute(cat = 23, subcat = 550, page = 2)
        val stacks = mapOf(
            flags to listOf(FlagsListRoute),
            forum to listOf(ForumRoute),
        )

        val result = inAppRouteBackStackAfterOpen(
            currentDestination = flags,
            parsed = ParsedDeepLink(destination = forum, route = category),
            backStackFor = { destination -> stacks.getValue(destination) },
        )

        assertEquals(forum, result.destination)
        assertEquals(listOf(ForumRoute, category), result.backStack)
    }

    @Test
    fun `in-app HFR route already on top is a no-op`() {
        val topic = TopicRoute(cat = 23, post = 100, page = 4)

        assertEquals(
            listOf(FlagsListRoute, topic),
            inAppRouteBackStackAfterOpen(listOf(FlagsListRoute, topic), topic),
        )
    }

    @Test
    fun `re-opening the top route with an older duplicate below is a no-op`() {
        val topicA = TopicRoute(cat = 23, post = 100, page = 4)
        val topicB = TopicRoute(cat = 23, post = 200, page = 7)
        val backStack = listOf(FlagsListRoute, topicA, topicB, topicA)

        assertEquals(backStack, inAppRouteBackStackAfterOpen(backStack, topicA))
    }

    @Test
    fun `re-opening a route present twice pops to the most recent occurrence`() {
        val topicA = TopicRoute(cat = 23, post = 100, page = 4)
        val topicB = TopicRoute(cat = 23, post = 200, page = 7)
        val topicC = TopicRoute(cat = 23, post = 300, page = 9)

        assertEquals(
            listOf(FlagsListRoute, topicA, topicB, topicA),
            inAppRouteBackStackAfterOpen(
                listOf(FlagsListRoute, topicA, topicB, topicA, topicC),
                topicA,
            ),
        )
    }

    @Test
    fun `in-app HFR same topic with different page or anchor is pushed as a distinct key`() {
        val topic = TopicRoute(cat = 23, post = 100, page = 4, scrollTo = 123)
        val sameTopicDifferentPage = TopicRoute(cat = 23, post = 100, page = 5, scrollTo = 123)
        val sameTopicDifferentAnchor = TopicRoute(cat = 23, post = 100, page = 4, scrollTo = 456)

        assertEquals(
            listOf(FlagsListRoute, topic, sameTopicDifferentPage),
            inAppRouteBackStackAfterOpen(listOf(FlagsListRoute, topic), sameTopicDifferentPage),
        )
        assertEquals(
            listOf(FlagsListRoute, topic, sameTopicDifferentAnchor),
            inAppRouteBackStackAfterOpen(listOf(FlagsListRoute, topic), sameTopicDifferentAnchor),
        )
    }

    @Test
    fun `in-app HFR route already lower in the stack pops back to it`() {
        val topicA = TopicRoute(cat = 23, post = 100, page = 4)
        val topicB = TopicRoute(cat = 23, post = 200, page = 7)

        assertEquals(
            listOf(FlagsListRoute, topicA),
            inAppRouteBackStackAfterOpen(listOf(FlagsListRoute, topicA, topicB), topicA),
        )
    }
}
