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
import fr.forumhfr.redface2.feature.editor.PostEditorMode
import fr.forumhfr.redface2.feature.editor.PostEditorRequest
import fr.forumhfr.redface2.feature.editor.TopicFormRequest
import fr.forumhfr.redface2.feature.editor.PostEditorScreen
import fr.forumhfr.redface2.feature.editor.TopicFormMode
import fr.forumhfr.redface2.feature.editor.TopicFormScreen
import fr.forumhfr.redface2.feature.flags.FlagsRoute
import fr.forumhfr.redface2.feature.forum.CategoryRequest
import fr.forumhfr.redface2.feature.forum.ForumCategoryScreen
import fr.forumhfr.redface2.feature.forum.ForumScreen
import fr.forumhfr.redface2.feature.messages.MessagesScreen
import fr.forumhfr.redface2.feature.search.SearchScreen
import fr.forumhfr.redface2.feature.settings.SettingsScreen
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
data class PostEditorRoute(
    val mode: PostEditorMode,
    val cat: Int,
    val topicId: Int? = null,
    val numreponse: Int? = null,
    /**
     * Page index of the topic the user is replying to. Required by HFR's
     * `message.php` reply form URL (cf. `docs/specs/protocol-hfr.md` § POST `bddpost.php`)
     * — Phase 2C captures the value from the active topic state.
     */
    val page: Int? = null,
    /**
     * Sub-category id of the topic. Required by HFR's reply contract; carried
     * over from `Topic.subcat` parsed from the loaded topic page. Phase 2C-A
     * refuses to open the editor when this is null.
     */
    val subcat: Int? = null,
    /**
     * `numreponse` of the post being quoted (Phase 2C, #146). `null` for a
     * simple reply ; non-null when the user tapped « Citer » on a specific post.
     * Routed verbatim into `PostEditorRequest.quotedNumreponse`.
     */
    val quotedNumreponse: Int? = null,
    /**
     * `ref` parameter extracted from HFR's quote link on the source post.
     * Opaque positional id ; forwarded as-is to `HfrClient.getReplyForm`. `null`
     * for a simple reply or for a post whose HTML did not expose a quote link.
     */
    val quoteRef: Int? = null,
) : RedfaceNavKey

@Serializable
data class TopicFormRoute(
    val mode: TopicFormMode,
    val cat: Int? = null,
    val subcat: Int? = null,
    val topicId: Int? = null,
    /** Phase 2D #148 — `page` of the topic the user opened the FP editor from. Always 1 for EditFirstPost. */
    val page: Int? = null,
    /** Phase 2D #148 — `numreponse` of the first post being edited. Required for [TopicFormMode.EditFirstPost]. */
    val numreponse: Int? = null,
) : RedfaceNavKey

@Serializable
data object LoginRoute : RedfaceNavKey

@Serializable
data object DiagnosticsRoute : RedfaceNavKey

@Serializable
data object SettingsRoute : RedfaceNavKey

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
@Suppress("CyclomaticComplexMethod") // One entry per top-level route + per-screen navigation callbacks ;
// splitting the host would just push the same `when` shape one level deeper without reducing complexity.
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
                    onOpenFlag = { flag ->
                        backStack.add(
                            TopicRoute(
                                cat = flag.cat,
                                post = flag.topicId,
                                page = flag.lastReadPage,
                                // REST `last_post_read_id` is the LAST post the user
                                // read (not the first unread). Re-anchoring the reader
                                // there is close enough to the legacy "where I stopped"
                                // UX without claiming a first-unread we cannot prove
                                // from the REST flag payload. HFR numreponse fits in
                                // Int (largest observed ~10M), so the toInt() narrowing
                                // is safe in practice. null = no anchor available.
                                scrollTo = flag.lastPostReadId
                                    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                                    ?.toInt(),
                            ),
                        )
                    },
                    onLoginRequested = {
                        backStack.add(LoginRoute)
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
                    onOpenTopic = { result ->
                        // Title-search rows open page 1. Content-search rows can carry a
                        // matched `numreponse` link, so consume it when HFR provides it.
                        backStack.add(
                            TopicRoute(
                                cat = result.cat,
                                post = result.topicId,
                                page = result.page ?: 1,
                                scrollTo = result.numreponse,
                            ),
                        )
                    },
                )
            }
            entry<MessagesRoute> {
                MessagesScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onLoginRequested = { backStack.add(LoginRoute) },
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                    onOpenSettings = { backStack.add(SettingsRoute) },
                )
            }
            entry<SettingsRoute> {
                SettingsScreen()
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
                                // Compose Navigation 3 caps `scrollTo` at Int while
                                // `numreponse` is stored Long? to absorb a future
                                // overflow without breaking deserialisation.
                                // Production HFR values fit in Int (~10M); narrow
                                // defensively and drop the anchor when out of range.
                                scrollTo = topic.lastPostReadId
                                    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                                    ?.toInt(),
                            ),
                        )
                    },
                    onCreateTopic = { cat, subcat ->
                        // Phase 2E (#149) — push the topic form in `New` mode.
                        // `subcat` here is the chip d'arrivée (nullable on the
                        // « Toutes » view) ; the user picks the final
                        // sub-category in the composer's dropdown.
                        backStack.add(
                            TopicFormRoute(
                                mode = TopicFormMode.New,
                                cat = cat,
                                subcat = subcat,
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
                    onReply = { subcat, page ->
                        backStack.add(
                            PostEditorRoute(
                                mode = PostEditorMode.Reply,
                                cat = route.cat,
                                topicId = route.post,
                                page = page,
                                subcat = subcat,
                            ),
                        )
                    },
                    onQuote = { subcat, page, quotedNumreponse, quoteRef ->
                        // Phase 2C (#146) — same destination as reply ; only the
                        // editor's request differs (quotedNumreponse pulls the HFR
                        // quote prefill). Route is `PostEditorMode.Reply` because
                        // quote is a flavour of reply, not a new editor mode.
                        backStack.add(
                            PostEditorRoute(
                                mode = PostEditorMode.Reply,
                                cat = route.cat,
                                topicId = route.post,
                                page = page,
                                subcat = subcat,
                                quotedNumreponse = quotedNumreponse,
                                quoteRef = quoteRef,
                            ),
                        )
                    },
                    onEdit = { subcat, page, numreponse ->
                        // Phase 2D (#147) — `PostEditorMode.Edit` triggers the
                        // `bdd.php` flow inside the editor. `numreponse` identifies
                        // the post being rewritten ; HFR's edit form prefills the
                        // existing BBCode in `<textarea name=content_form>`.
                        backStack.add(
                            PostEditorRoute(
                                mode = PostEditorMode.Edit,
                                cat = route.cat,
                                topicId = route.post,
                                numreponse = numreponse,
                                page = page,
                                subcat = subcat,
                            ),
                        )
                    },
                    onEditFirstPost = { subcat, page, numreponse ->
                        // Phase 2D (#148) — topic-level form for first-post
                        // editing. `numreponse` here is the FIRST post of the
                        // topic, not the topic id ; `topicId` (route.post) stays
                        // separate. The `TopicFormScreen` reads the request and
                        // routes through `TopicFormRepository` rather than
                        // `EditPostRepository`.
                        backStack.add(
                            TopicFormRoute(
                                mode = TopicFormMode.EditFirstPost,
                                cat = route.cat,
                                subcat = subcat,
                                topicId = route.post,
                                page = page,
                                numreponse = numreponse,
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
            entry<PostEditorRoute> { route ->
                PostEditorScreen(
                    request = PostEditorRequest(
                        mode = route.mode,
                        cat = route.cat,
                        topicId = route.topicId,
                        numreponse = route.numreponse,
                        page = route.page,
                        subcat = route.subcat,
                        quotedNumreponse = route.quotedNumreponse,
                        quoteRef = route.quoteRef,
                    ),
                    onSubmitSucceeded = { targetPage, scrollTo ->
                        // Pop the editor and refresh the topic page. Phase 2C-A always
                        // pops back to the topic; targetPage informs which page to
                        // reload — null falls back to the page we replied from.
                        // Phase 2D (#147) edit sets `scrollTo = numreponse` so the
                        // topic screen jumps to the edited post after refresh ;
                        // reply / quote leave it null (refresh anchors `#bas`).
                        // Guard the pop the same way the global `onBack` lambda does:
                        // never collapse the back stack below the tab root.
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        val topicEntry = backStack.lastOrNull() as? TopicRoute
                        if (topicEntry != null) {
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(
                                topicEntry.copy(
                                    page = targetPage ?: topicEntry.page,
                                    scrollTo = scrollTo ?: topicEntry.scrollTo,
                                ),
                            )
                        }
                    },
                )
            }
            entry<TopicFormRoute> { route ->
                TopicFormScreen(
                    request = TopicFormRequest(
                        mode = route.mode,
                        cat = route.cat,
                        subcat = route.subcat,
                        topicId = route.topicId,
                        page = route.page,
                        numreponse = route.numreponse,
                    ),
                    onSubmitSucceeded = { targetPage, scrollTo ->
                        // Phase 2D (#148) — pop the FP form, replace the topic
                        // route below with one that refreshes the target page
                        // and scrolls to the edited first post. Same pattern
                        // as `PostEditorRoute.onSubmitSucceeded` ; the only
                        // difference is the source route type (`TopicFormRoute`
                        // vs `PostEditorRoute`).
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        val topicEntry = backStack.lastOrNull() as? TopicRoute
                        if (topicEntry != null) {
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(
                                topicEntry.copy(
                                    page = targetPage ?: topicEntry.page,
                                    scrollTo = scrollTo ?: topicEntry.scrollTo,
                                ),
                            )
                        }
                    },
                    onNewTopicCreated = { cat, subcat, newTopicId, newNumreponse ->
                        // Phase 2E (#149). Two paths :
                        //  - When the success URL parser eventually lands and
                        //    `newTopicId` is non-null, jump straight to the
                        //    fresh topic so the user sees their first post.
                        //  - Until then (no success fixture captured yet),
                        //    pop the composer and replace it with a
                        //    [CategoryRoute] pointing at the d'arrivée
                        //    sub-category. The screen-side Toast tells the
                        //    user the POST went through.
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        if (newTopicId != null) {
                            backStack.add(
                                TopicRoute(
                                    cat = cat,
                                    post = newTopicId,
                                    page = 1,
                                    scrollTo = newNumreponse,
                                ),
                            )
                        } else {
                            // Replace the current category entry with one keyed
                            // on the targeted subcat (may differ from the entry
                            // chip when the user picked another sub-category
                            // in the dropdown) so the listing refresh lands on
                            // the right rail.
                            val categoryBelow = backStack.lastOrNull() as? CategoryRoute
                            if (categoryBelow != null) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                            backStack.add(
                                CategoryRoute(cat = cat, subcat = subcat, page = 1),
                            )
                        }
                    },
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
