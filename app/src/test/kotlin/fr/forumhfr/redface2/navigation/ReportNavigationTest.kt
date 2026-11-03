package fr.forumhfr.redface2.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReportNavigationTest {
    @Test
    fun bothEntryPointsOpenReport() {
        val account = mutableListOf<NavKey>(ForumRoute)
        openReportFromAccount(account)
        assertEquals(ReportContentRoute(), account.last())
        val settings = mutableListOf<NavKey>(SettingsRoute, SettingsAccountAboutRoute)
        openReportFromSettings(settings)
        assertEquals(ReportContentRoute(), settings.last())
    }

    @Test
    fun topicContextPrefillsOnlyTopicLink() {
        val stack = mutableListOf<NavKey>(ForumRoute, TopicRoute(cat = 23, post = 35421, page = 9, scrollTo = 123))
        openReportFromAccount(stack)
        val route = stack.last() as ReportContentRoute
        assertEquals(ReportContentRoute(cat = 23, post = 35421), route)
        assertEquals("https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35421", route.topicLink())
        assertFalse(route.topicLink().contains("page="))
        assertFalse(route.topicLink().contains("#t"))
    }

    @Test
    fun privateMessageContextDoesNotPrefillLink() {
        val stack = mutableListOf<NavKey>(MessagesRoute, PrivateMessageThreadRoute(42))
        openReportFromAccount(stack)
        assertEquals("", (stack.last() as ReportContentRoute).topicLink())
    }

    @Test
    fun loginFromMessagesReturnsToReportRegardlessOfCallbackOrder() {
        val route = ReportContentRoute(23, 35421)
        val resetFirst = mutableListOf<NavKey>(MessagesRoute, route, LoginRoute)
        resetMessagesPreservingReport(resetFirst)
        finishLogin(resetFirst)
        assertEquals(listOf(MessagesRoute, route), resetFirst)

        val callbackFirst = mutableListOf<NavKey>(MessagesRoute, route, LoginRoute)
        finishLogin(callbackFirst)
        resetMessagesPreservingReport(callbackFirst)
        assertEquals(listOf(MessagesRoute, route), callbackFirst)
    }

    @Test
    fun sessionResetStillDropsPrivateConversationAndOrdinaryLogin() {
        val stack = mutableListOf<NavKey>(MessagesRoute, PrivateMessageThreadRoute(42), LoginRoute)
        resetMessagesPreservingReport(stack)
        finishLogin(stack)
        assertEquals(listOf(MessagesRoute), stack)
    }

    @Test
    fun messageCheckOpensTheInboxWithoutDuplicatingItsNavKey() {
        val stack = mutableListOf<NavKey>(MessagesRoute, ReportContentRoute())
        var selected = TopLevelDestination.Settings
        openMessagesForReport(stack) { selected = it }
        assertEquals(TopLevelDestination.Messages, selected)
        assertEquals(listOf(MessagesRoute), stack)
    }
}
