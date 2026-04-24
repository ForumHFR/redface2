package fr.forumhfr.redface2.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.FlagsScreen
import fr.forumhfr.redface2.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.feature.editor.EditorScreen
import fr.forumhfr.redface2.feature.forum.CategoryScreen
import fr.forumhfr.redface2.feature.forum.ForumScreen
import fr.forumhfr.redface2.feature.messages.MessagesScreen
import fr.forumhfr.redface2.feature.search.SearchScreen
import fr.forumhfr.redface2.feature.topic.TopicRequest
import fr.forumhfr.redface2.feature.topic.TopicScreen
import kotlinx.serialization.Serializable

@Serializable
sealed interface RedfaceNavKey : NavKey

@Serializable
data object FlagsListRoute : RedfaceNavKey

@Serializable
data object ForumRoute : RedfaceNavKey

@Serializable
data object SearchRoute : RedfaceNavKey

@Serializable
data object MessagesRoute : RedfaceNavKey

@Serializable
data class CategoryRoute(
    val cat: Int,
    val subcat: Int? = null,
) : RedfaceNavKey

@Serializable
data class TopicRoute(
    val cat: Int,
    val post: Int,
    val page: Int = 1,
    val scrollTo: Int? = null,
) : RedfaceNavKey

@Serializable
data class EditorRoute(
    val mode: EditorMode,
    val cat: Int,
    val post: Int? = null,
) : RedfaceNavKey

@Serializable
enum class EditorMode {
    Reply,
    Edit,
    EditFirstPost,
}

private enum class TopLevelDestination(
    val labelRes: Int,
    val rootRoute: RedfaceNavKey,
) {
    Flags(R.string.nav_flags, FlagsListRoute),
    Forum(R.string.nav_forum, ForumRoute),
    Search(R.string.nav_search, SearchRoute),
    Messages(R.string.nav_messages, MessagesRoute),
}

private data class ParsedDeepLink(
    val destination: TopLevelDestination,
    val route: RedfaceNavKey,
)

@Composable
fun RedfaceApp(intent: Intent?) {
    RedfaceTheme {
        val flagsBackStack = rememberNavBackStack(FlagsListRoute)
        val forumBackStack = rememberNavBackStack(ForumRoute)
        val searchBackStack = rememberNavBackStack(SearchRoute)
        val messagesBackStack = rememberNavBackStack(MessagesRoute)

        var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.Flags) }

        val backStacks = remember(flagsBackStack, forumBackStack, searchBackStack, messagesBackStack) {
            mapOf(
                TopLevelDestination.Flags to flagsBackStack,
                TopLevelDestination.Forum to forumBackStack,
                TopLevelDestination.Search to searchBackStack,
                TopLevelDestination.Messages to messagesBackStack,
            )
        }

        LaunchedEffect(intent) {
            val parsed = intent?.data?.let(::parseHfrDeepLink) ?: return@LaunchedEffect
            currentDestination = parsed.destination
            resetStack(
                backStack = backStacks.getValue(parsed.destination),
                root = parsed.destination.rootRoute,
                route = parsed.route,
            )
        }

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { destination ->
                    item(
                        selected = currentDestination == destination,
                        onClick = { currentDestination = destination },
                        icon = { Text(text = stringResource(destination.labelRes).first().toString()) },
                        label = { Text(text = stringResource(destination.labelRes)) },
                    )
                }
            },
        ) {
            Surface(modifier = Modifier.padding(horizontal = 8.dp)) {
                val activeBackStack = backStacks.getValue(currentDestination)
                RedfaceNavHost(backStack = activeBackStack)
            }
        }
    }
}

@Composable
private fun RedfaceNavHost(backStack: NavBackStack<NavKey>) {
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        entryProvider = entryProvider {
            entry<FlagsListRoute> {
                FlagsScreen(
                    onOpenUnreadTopic = {
                        backStack.add(
                            TopicRoute(
                                cat = FixedTopicFixtures.cat,
                                post = FixedTopicFixtures.post,
                                page = 1,
                            ),
                        )
                    },
                    onOpenTrackedCategory = {
                        backStack.add(CategoryRoute(cat = 23, subcat = 0))
                    },
                )
            }
            entry<ForumRoute> {
                ForumScreen(
                    onOpenCategory = {
                        backStack.add(CategoryRoute(cat = 23, subcat = 0))
                    },
                    onOpenTopic = {
                        backStack.add(
                            TopicRoute(
                                cat = FixedTopicFixtures.cat,
                                post = FixedTopicFixtures.post,
                                page = 1,
                            ),
                        )
                    },
                )
            }
            entry<SearchRoute> {
                SearchScreen(
                    onOpenResult = {
                        backStack.add(
                            TopicRoute(
                                cat = FixedTopicFixtures.cat,
                                post = FixedTopicFixtures.post,
                                page = 146,
                                scrollTo = 18085119,
                            ),
                        )
                    },
                )
            }
            entry<MessagesRoute> {
                MessagesScreen(
                    onOpenTopic = {
                        backStack.add(
                            TopicRoute(
                                cat = FixedTopicFixtures.cat,
                                post = FixedTopicFixtures.post,
                                page = 2,
                            ),
                        )
                    },
                )
            }
            entry<CategoryRoute> { route ->
                CategoryScreen(
                    cat = route.cat,
                    subcat = route.subcat,
                    onOpenTopic = {
                        backStack.add(
                            TopicRoute(
                                cat = FixedTopicFixtures.cat,
                                post = FixedTopicFixtures.post,
                                page = 1,
                            ),
                        )
                    },
                )
            }
            entry<TopicRoute> { route ->
                TopicScreen(
                    request = TopicRequest(
                        cat = route.cat,
                        post = route.post,
                        page = route.page,
                        scrollTo = route.scrollTo,
                    ),
                    onReply = { postId ->
                        backStack.add(
                            EditorRoute(
                                mode = EditorMode.Reply,
                                cat = route.cat,
                                post = postId,
                            ),
                        )
                    },
                    onOpenPage = { targetPage ->
                        backStack.removeAt(backStack.lastIndex)
                        backStack.add(
                            TopicRoute(
                                cat = route.cat,
                                post = route.post,
                                page = targetPage,
                                scrollTo = null,
                            ),
                        )
                    },
                )
            }
            entry<EditorRoute> { route ->
                EditorScreen(
                    mode = route.mode.name,
                    cat = route.cat,
                    post = route.post,
                )
            }
        },
    )
}

private fun parseHfrDeepLink(uri: Uri): ParsedDeepLink? = when (uri.path) {
    "/forum1.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val post = uri.getQueryParameter("post")?.toIntOrNull() ?: return null
        val page = uri.getQueryParameter("page")?.toIntOrNull() ?: 1
        val scrollTo = uri.fragment?.removePrefix("t")?.toIntOrNull()
        ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(cat = cat, post = post, page = page, scrollTo = scrollTo),
        )
    }

    "/forum2.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        ParsedDeepLink(
            destination = TopLevelDestination.Forum,
            route = CategoryRoute(cat = cat, subcat = uri.getQueryParameter("subcat")?.toIntOrNull()),
        )
    }

    "/forum1f.php" -> ParsedDeepLink(
        destination = TopLevelDestination.Flags,
        route = FlagsListRoute,
    )

    else -> null
}

private fun resetStack(
    backStack: NavBackStack<NavKey>,
    root: RedfaceNavKey,
    route: RedfaceNavKey,
) {
    backStack.clear()
    backStack.add(root)
    if (route != root) {
        backStack.add(route)
    }
}
