package fr.forumhfr.redface2.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReportNavigationTest {
    @Test
    fun bothEntryPointsOpenTheSameReportWithoutContext() {
        val account = mutableListOf<NavKey>(ForumRoute)
        openReportFromAccount(account)
        assertSame(ReportContentRoute, account.last())
        val settings = mutableListOf<NavKey>(SettingsRoute, SettingsAccountAboutRoute)
        openReportFromSettings(settings)
        assertSame(ReportContentRoute, settings.last())
    }

    @Test
    fun loginFromMessagesReturnsToReportRegardlessOfCallbackOrder() {
        val route = ReportContentRoute
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
        val stack = mutableListOf<NavKey>(MessagesRoute, ReportContentRoute)
        var selected = TopLevelDestination.Settings
        var refreshes = 0
        openMessagesForReport(stack, requestInboxRefresh = { refreshes++ }) { selected = it }
        assertEquals(TopLevelDestination.Messages, selected)
        assertEquals(listOf(MessagesRoute), stack)
        assertEquals(1, refreshes)
    }
}
