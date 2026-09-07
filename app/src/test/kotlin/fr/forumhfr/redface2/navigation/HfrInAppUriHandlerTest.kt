package fr.forumhfr.redface2.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.UriHandler
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkState
import fr.forumhfr.redface2.feature.topic.ModerationAlertLinkTarget
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HfrInAppUriHandlerTest {
    private val target = ModerationAlertLinkTarget(cat = 23, post = 35421, numreponse = 2_800_456, page = 76)
    private val alertUrl = "https://forum.hardware.fr/user/modo.php?cat=23&post=35421&numreponse=2800456&page=76"

    @Test
    fun `modo tap opens root information without navigation from every tab`() {
        for (origin in TopLevelDestination.entries) {
            val stacks = backStacks()
            val before = stacks.mapValues { it.value.toList() }
            val sheets = mutableListOf<ModerationAlertLinkTarget>()
            val routes = mutableListOf<ParsedDeepLink>()
            val switches = mutableListOf<TopLevelDestination>()
            val handler = handler(
                onOpenRoute = { parsed ->
                    routes += parsed
                    openRouteInApp(origin, parsed, stacks, switches::add)
                },
                onOpenAlert = sheets::add,
            )

            handler.openUri(alertUrl)

            assertEquals(listOf(target), sheets)
            assertTrue(routes.isEmpty())
            assertTrue(switches.isEmpty())
            assertEquals(before, stacks.mapValues { it.value.toList() })
        }
    }

    @Test
    fun `repeated modo taps reopen information and keep page one unresolved until navigation`() {
        val sheets = mutableListOf<ModerationAlertLinkTarget>()
        val handler = handler(onOpenRoute = { error("Unexpected navigation") }, onOpenAlert = sheets::add)
        val uri = "http://forum.hardware.fr/user/modo.php?cat=23&post=35421&numreponse=2800456"

        handler.openUri(uri)
        handler.openUri(uri)

        assertEquals(List(2) { target.copy(page = 1) }, sheets)
    }

    @Test
    fun `ordinary topic tap still navigates within the current tab`() {
        val stacks = backStacks()
        val before = stacks.getValue(TopLevelDestination.Messages).toList()
        val switches = mutableListOf<TopLevelDestination>()
        val handler = handler(
            onOpenRoute = { openRouteInApp(TopLevelDestination.Messages, it, stacks, switches::add) },
            onOpenAlert = { error("Unexpected moderation sheet") },
        )

        handler.openUri("https://forum.hardware.fr/forum2.php?cat=23&post=35421&page=76#t2800456")

        assertEquals(listOf(TopLevelDestination.Messages), switches)
        assertEquals(
            before + TopicRoute(cat = 23, post = 35421, page = 76, scrollTo = 2_800_456),
            stacks.getValue(TopLevelDestination.Messages).toList(),
        )
    }

    @Test
    fun `view post uses the in-app stack and resolves page one without an arrival sheet`() {
        for (page in listOf(1, 76)) {
            val stacks = backStacks()
            val before = stacks.getValue(TopLevelDestination.Forum).toList()
            val otherStacks = stacks.filterKeys { it != TopLevelDestination.Forum }.mapValues { it.value.toList() }
            val navigation = ModerationAlertLinkState.NavigateToPost(target.copy(page = page), withAlertSheet = false)
            val parsed = navigation.toParsedDeepLink()
            val switches = mutableListOf<TopLevelDestination>()

            openRouteInApp(TopLevelDestination.Forum, parsed, stacks, switches::add)

            val route = TopicRoute(
                cat = target.cat, post = target.post, page = page, scrollTo = target.numreponse,
                resolveScrollToPage = page == 1, moderationAlertFor = null,
            )
            assertEquals(route, parsed.route)
            assertEquals(before + route, stacks.getValue(TopLevelDestination.Forum).toList())
            assertEquals(listOf(TopLevelDestination.Forum), switches)
            assertEquals(
                otherStacks,
                stacks.filterKeys { it != TopLevelDestination.Forum }.mapValues { it.value.toList() },
            )
        }
    }

    @Test
    fun `form navigation and external VIEW retain the existing alert route`() {
        val navigation = ModerationAlertLinkState.NavigateToPost(target, withAlertSheet = true)
        val expected = ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(
                cat = target.cat, post = target.post, page = target.page, scrollTo = target.numreponse,
                moderationAlertFor = target.numreponse,
            ),
        )

        assertEquals(expected, navigation.toParsedDeepLink())
        assertEquals(
            HfrDeepLinkResolution.Route(expected),
            resolveHfrDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(alertUrl))),
        )
    }

    private fun handler(
        onOpenRoute: (ParsedDeepLink) -> Unit,
        onOpenAlert: (ModerationAlertLinkTarget) -> Unit,
    ): HfrInAppUriHandler = HfrInAppUriHandler(
        context = mockk(),
        openRouteInApp = onOpenRoute,
        openModerationAlert = onOpenAlert,
        platformUriHandler = object : UriHandler {
            override fun openUri(uri: String) = error("Unexpected external URI: $uri")
        },
        alwaysAskExternalApp = false,
    )

    private fun backStacks(): Map<TopLevelDestination, NavBackStack<NavKey>> =
        TopLevelDestination.entries.associateWith { destination ->
            val stack = NavBackStack<NavKey>(destination.rootRoute)
            when (destination) {
                TopLevelDestination.Flags -> stack.add(TopicRoute(cat = 13, post = 100, page = 4))
                TopLevelDestination.Forum -> stack.add(CategoryRoute(cat = 23))
                TopLevelDestination.Messages -> stack.add(PrivateMessageThreadRoute(threadId = 123, page = 2))
                else -> Unit
            }
            stack
        }
}
