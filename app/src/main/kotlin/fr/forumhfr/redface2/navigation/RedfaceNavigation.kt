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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import fr.forumhfr.redface2.BuildConfig
import fr.forumhfr.redface2.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.feature.auth.LoginScreen
import fr.forumhfr.redface2.feature.editor.EditorScreen
import fr.forumhfr.redface2.feature.flags.FlagsRoute
import fr.forumhfr.redface2.feature.forum.CategoryRequest
import fr.forumhfr.redface2.feature.forum.ForumCategoryScreen
import fr.forumhfr.redface2.feature.forum.ForumScreen
import fr.forumhfr.redface2.feature.messages.MessagesScreen
import fr.forumhfr.redface2.feature.search.SearchScreen
import fr.forumhfr.redface2.feature.topic.TopicRequest
import fr.forumhfr.redface2.feature.topic.TopicScreen
import kotlinx.serialization.Serializable

// Stubs used by the remaining placeholder tabs (Search/Messages) whose models are not
// yet wired. Each "open topic" button currently navigates to a hard-coded HFR thread so
// the navigation graph itself can be exercised end-to-end. Forum/Category dropped this
// crutch in Phase 1C-A (real REST-backed ForumScreen / ForumCategoryScreen). The
// remaining call-sites disappear as Phase 1C+ lands the real SearchResult / MpThread.
//
// The target is the community topic dedicated to Redface 2 itself
// (https://forum.hardware.fr/forum2.php?config=hfr.inc&cat=23&post=35395) — recent, short,
// and the most natural place to dogfood the app on the topic that discusses the app.
private const val DEMO_TOPIC_CAT: Int = 23
private const val DEMO_TOPIC_POST: Int = 35_395

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
    val page: Int = 1,
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

@Serializable
data object LoginRoute : RedfaceNavKey

@Serializable
data object DiagnosticsRoute : RedfaceNavKey

internal enum class TopLevelDestination(
    val labelRes: Int,
    val rootRoute: RedfaceNavKey,
) {
    Flags(R.string.nav_flags, FlagsListRoute),
    Forum(R.string.nav_forum, ForumRoute),
    Search(R.string.nav_search, SearchRoute),
    Messages(R.string.nav_messages, MessagesRoute),
}

internal data class ParsedDeepLink(
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
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<FlagsListRoute> {
                FlagsRoute(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onOpenFlag = { flag ->
                        backStack.add(
                            TopicRoute(
                                cat = flag.cat,
                                post = flag.topicId,
                                page = flag.lastReadPage,
                                // HFR numreponse fits in Int (largest observed ~10M),
                                // so the toInt() narrowing is safe in practice. 0 means
                                // "no first-unread known" → don't deep-link anywhere.
                                scrollTo = flag.firstUnreadPostId
                                    .takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                                    ?.toInt(),
                            ),
                        )
                    },
                    onLoginRequested = {
                        backStack.add(LoginRoute)
                    },
                    onOpenDiagnostics = {
                        backStack.add(DiagnosticsRoute)
                    },
                )
            }
            entry<DiagnosticsRoute> {
                fr.forumhfr.redface2.feature.flags.DiagnosticsScreen(
                    onClose = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
            }
            entry<LoginRoute> {
                LoginScreen(
                    onAuthenticated = {
                        // Pop the login screen — FlagsRoute reactively re-renders
                        // to "Connecté en tant que <pseudo>" through observeAuthState().
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onCancel = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
            }
            entry<ForumRoute> {
                ForumScreen(
                    onOpenCategory = { category ->
                        backStack.add(CategoryRoute(cat = category.id, subcat = null, page = 1))
                    },
                )
            }
            entry<SearchRoute> {
                SearchScreen(
                    onOpenResult = {
                        backStack.add(
                            TopicRoute(
                                cat = DEMO_TOPIC_CAT,
                                post = DEMO_TOPIC_POST,
                                page = 1,
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
                                cat = DEMO_TOPIC_CAT,
                                post = DEMO_TOPIC_POST,
                                page = 1,
                            ),
                        )
                    },
                )
            }
            entry<CategoryRoute> { route ->
                ForumCategoryScreen(
                    request = CategoryRequest(
                        cat = route.cat,
                        initialSubcat = route.subcat,
                        initialPage = route.page,
                    ),
                    onOpenTopic = { topic ->
                        backStack.add(
                            TopicRoute(
                                cat = topic.cat,
                                post = topic.topicId,
                                page = topic.lastReadPage ?: 1,
                                scrollTo = topic.lastPostReadId,
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

internal fun parseHfrDeepLink(uri: Uri): ParsedDeepLink? = when (uri.path) {
    // forum1.php is the topic-list page (per category / subcategory). Required:
    // `cat`. Optional: `subcat`, `page`. Lands on the Forum tab so the back stack
    // walks Forum -> Category -> (deeper) instead of Flags.
    "/forum1.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val subcat = uri.getQueryParameter("subcat")?.toIntOrNull()
        // Preserve `page` from the deep link so a shared link to e.g.
        // forum1.php?cat=23&subcat=550&page=2 lands on page 2 instead of silently
        // resetting to 1. Out-of-range / malformed values fall back to 1.
        val page = uri.getQueryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        ParsedDeepLink(
            destination = TopLevelDestination.Forum,
            route = CategoryRoute(cat = cat, subcat = subcat, page = page),
        )
    }

    // forum2.php is the topic-content page (the actual posts). Required: `cat`,
    // `post`. Optional: `page`, fragment `#t<numreponse>` for scroll-to-post.
    // Lands on the Flags tab — the typical reading surface.
    "/forum2.php" -> {
        val cat = uri.getQueryParameter("cat")?.toIntOrNull() ?: return null
        val post = uri.getQueryParameter("post")?.toIntOrNull() ?: return null
        val page = uri.getQueryParameter("page")?.toIntOrNull() ?: 1
        val scrollTo = uri.fragment?.removePrefix("t")?.toIntOrNull()
        ParsedDeepLink(
            destination = TopLevelDestination.Flags,
            route = TopicRoute(cat = cat, post = post, page = page, scrollTo = scrollTo),
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
