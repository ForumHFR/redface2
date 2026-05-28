package fr.forumhfr.redface2.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu
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
import fr.forumhfr.redface2.feature.profile.ProfilePreviewSheet
import fr.forumhfr.redface2.feature.profile.ProfileRoute
import fr.forumhfr.redface2.feature.profile.ProfileViewModel
import fr.forumhfr.redface2.feature.search.SearchScreen
import fr.forumhfr.redface2.feature.settings.SettingsScreen
import fr.forumhfr.redface2.feature.topic.TopicRequest
import fr.forumhfr.redface2.feature.topic.TopicScreen
import kotlinx.serialization.Serializable
import androidx.compose.material3.ExperimentalMaterial3Api

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
    /**
     * Issue #200 — bumped to `System.currentTimeMillis()` by the navigation host when the
     * editor pops back after a successful submit (reply / quote / edit / edit-FP /
     * create-topic). The new value invalidates the route key so the topic screen rebuilds
     * its ViewModel and `loadCurrentPage()` skips the cache to force-fetch the latest page
     * — otherwise the cache-aside path would serve a stale page that doesn't include the
     * post the user just published. `null` on the normal nav path (forum / flags / deep
     * link) so the cache-first behaviour is preserved everywhere else.
     */
    val submitSignal: Long? = null,
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

/**
 * Phase 2 finish (#208) — full profile page route.
 *
 * Navigation is always [userId]-first. [pseudo] and [avatarUrl] are display hints shown
 * while the profile is loading — they can be pre-populated from the topic page tap site
 * so the user sees a meaningful placeholder immediately.
 *
 * Architecture note (MVP limitation): the ModalBottomSheet preview ([ProfilePreviewSheet])
 * is hoisted in [RedfaceApp] as an overlay composable, while this route is a back-stack
 * nav entry with its own [androidx.lifecycle.ViewModelStore]. They are **two separate
 * [ProfileViewModel] instances** — each fetches the profile independently. This means
 * navigating from the sheet to the full page triggers a second network request. This is
 * the accepted MVP trade-off; no shared ViewModel or caching across the two entry points
 * is implemented yet.
 *
 * TODO(profile): caching follow-up — open a dedicated issue (none filed yet to keep this
 * PR scope-bound) and link it here. Candidate approaches: (a) shared `Singleton` Room-
 * backed cache keyed by `userId`, (b) repository-level in-memory `Cache<Int, UserProfile>`
 * with a short TTL, (c) hoisting the ViewModel to a `LocalViewModelStoreOwner` shared by
 * the sheet and the page.
 *
 * [ProfileViewModel] uses `@AssistedInject` (not SavedStateHandle) to receive [userId],
 * [pseudo], and [avatarUrl] at construction time.
 */
@Serializable
data class ProfileFullRoute(
    val userId: Int,
    val pseudo: String,
    val avatarUrl: String? = null,
) : RedfaceNavKey

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

/**
 * Holds the pending profile bottom sheet request, if any.
 *
 * Null = no sheet visible. Non-null = a profile sheet is open for the given user.
 * [avatarUrl] is a display hint populated from the topic page tap.
 *
 * Review feedback I3: [origin] captures the [TopLevelDestination] the user was on
 * **when the sheet was opened**, not « the tab currently focused ». Without this,
 * switching tabs while the sheet is open and then tapping « Voir le profil complet »
 * would push [ProfileFullRoute] onto the wrong tab's back stack, hijacking the
 * other tab's navigation history. The fix is to preserve the origin and route the
 * full-page entry there instead of the active tab.
 *
 * The [Saver] allows [rememberSaveable] to survive configuration changes (rotation).
 * Fields: [Int] userId, [String] pseudo, [String?] avatarUrl, [String] origin tag —
 * all primitive-compatible. The save lambda is typed `(ProfileSheetRequest?) -> List<Any?>`
 * because `listSaver` is parameterised on the original (nullable) type, so the legacy
 * null check is necessary even though in practice `rememberSaveable` only invokes save
 * on a non-null value. Review feedback M2: the inner expression is simplified to a single
 * `?:` instead of an if/else.
 */
private data class ProfileSheetRequest(
    val userId: Int,
    val pseudo: String,
    val avatarUrl: String?,
    val origin: TopLevelDestination,
) {
    companion object {
        val Saver = listSaver<ProfileSheetRequest?, Any?>(
            save = { req ->
                req?.let { listOf(it.userId, it.pseudo, it.avatarUrl, it.origin.name) }
                    ?: listOf(null, null, null, null)
            },
            restore = { list ->
                val userId = list[0] as? Int ?: return@listSaver null
                val pseudo = list[1] as? String ?: return@listSaver null
                val avatarUrl = list[2] as? String
                val originName = list[3] as? String ?: return@listSaver null
                val origin = runCatching { TopLevelDestination.valueOf(originName) }
                    .getOrNull()
                    ?: return@listSaver null
                ProfileSheetRequest(userId, pseudo, avatarUrl, origin)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedfaceApp(intent: Intent?) {
    RedfaceTheme {
        val flagsBackStack = rememberNavBackStack(FlagsListRoute)
        val forumBackStack = rememberNavBackStack(ForumRoute)
        val searchBackStack = rememberNavBackStack(SearchRoute)
        val messagesBackStack = rememberNavBackStack(MessagesRoute)

        var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.Flags) }

        // Phase 2 finish (#208) — profile bottom sheet state, hoisted to `:app` so that
        // `:feature:topic` never depends on `:feature:profile`. The sheet is opened from
        // any TopicScreen tap on an avatar/author with a non-null profileId.
        // rememberSaveable + custom Saver keeps the sheet open across configuration changes
        // (e.g. rotation) — without it the sheet would silently close on every rotation.
        var profileSheetRequest by rememberSaveable(stateSaver = ProfileSheetRequest.Saver) {
            mutableStateOf<ProfileSheetRequest?>(null)
        }

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

        // Issue #198 — global account menu hoisted out of `Messages` and re-injected into
        // every main screen as a trailing top-bar slot. Single ViewModel instance shared by
        // every tab (Hilt hands back the same scoped instance for an identical owner).
        val accountViewModel: AppAccountViewModel = hiltViewModel()
        val authState by accountViewModel.authState.collectAsStateWithLifecycle()
        val reportEmailSubject = stringResource(fr.forumhfr.redface2.core.ui.R.string.account_menu_report_email_subject)
        val reportNoEmailClient = stringResource(fr.forumhfr.redface2.core.ui.R.string.account_menu_no_email_client)
        val context = LocalContext.current

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
                val accountMenu: @Composable () -> Unit = {
                    RedfaceAccountMenu(
                        authState = authState,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        onLogin = { activeBackStack.add(LoginRoute) },
                        onLogout = accountViewModel::logout,
                        onOpenSettings = { activeBackStack.add(SettingsRoute) },
                        onOpenDiagnostics = { activeBackStack.add(DiagnosticsRoute) },
                        onReportContent = {
                            startReportEmail(context, reportEmailSubject, reportNoEmailClient)
                        },
                    )
                }
                RedfaceNavHost(
                    backStack = activeBackStack,
                    accountMenu = accountMenu,
                    onOpenProfile = { userId, pseudo, avatarUrl ->
                        // Review feedback I3: capture the **origin** tab so that
                        // « Voir le profil complet » lands on the correct back stack
                        // even if the user switches tabs while the sheet is open.
                        profileSheetRequest = ProfileSheetRequest(
                            userId = userId,
                            pseudo = pseudo,
                            avatarUrl = avatarUrl,
                            origin = currentDestination,
                        )
                    },
                )
            }
        }

        // Phase 2 finish (#208) — profile bottom sheet, rendered as an overlay on top of
        // the current tab. The sheet is not a Navigation entry, so its Hilt ViewModel uses
        // the Activity store. `ProfilePreviewSheet` supplies `key = "profile-$userId"` to
        // avoid reusing the first opened profile for every subsequent tap. Reopening the
        // same userId intentionally reuses that Activity-scoped state in this MVP.
        // `profileSheetRequest` drives visibility; setting it to null dismisses the sheet.
        profileSheetRequest?.let { request ->
            // key = userId recreates the Compose sheet slot when the target profile changes.
            // It does not by itself change the ViewModelStoreOwner; the Hilt key in the sheet
            // is what scopes one VM per userId.
            androidx.compose.runtime.key(request.userId) {
                ProfilePreviewSheet(
                    userId = request.userId,
                    pseudoHint = request.pseudo,
                    avatarUrlHint = request.avatarUrl,
                    onDismiss = { profileSheetRequest = null },
                    onOpenFullProfile = { userId, pseudo, avatarUrl ->
                        profileSheetRequest = null
                        // Review feedback I3: route the full-page entry to the back
                        // stack of the **origin** tab — the tab the user was on when
                        // the sheet was opened — not the tab currently focused. The
                        // user can switch tabs while the sheet is up ; tapping
                        // « Voir le profil complet » must land where they started.
                        backStacks.getValue(request.origin).add(
                            ProfileFullRoute(
                                userId = userId,
                                pseudo = pseudo,
                                avatarUrl = avatarUrl,
                            ),
                        )
                        // Switch back to the origin tab so the new entry is visible.
                        currentDestination = request.origin
                    },
                )
            }
        }
    }
}

/**
 * Issue #198 — fires the « Signaler un contenu » mailto intent. Falls back to a Toast when no
 * mail client is installed (rooted devices, emulators). Subject string lives in `:core:ui` so
 * `:feature:messages` no longer owns the global "report content" affordance.
 */
private fun startReportEmail(
    context: Context,
    subject: String,
    noEmailClientMessage: String,
) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = "mailto:$REPORT_EMAIL".toUri()
        putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, noEmailClientMessage, Toast.LENGTH_LONG).show()
    }
}

private const val REPORT_EMAIL: String = "xat@azora.fr"

@Composable
@Suppress("CyclomaticComplexMethod") // One entry per top-level route + per-screen navigation callbacks ;
// splitting the host would just push the same `when` shape one level deeper without reducing complexity.
private fun RedfaceNavHost(
    backStack: NavBackStack<NavKey>,
    accountMenu: @Composable () -> Unit,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
) {
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
                    topBarActions = accountMenu,
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
                    topBarActions = accountMenu,
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
                    topBarActions = accountMenu,
                )
            }
            entry<MessagesRoute> {
                MessagesScreen(topBarActions = accountMenu)
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
            entry<ProfileFullRoute> { route ->
                // Phase 2 finish (#208) — full profile page.
                // ProfileViewModel uses @AssistedInject (not SavedStateHandle) to receive
                // userId/pseudo/avatarUrl. With Compose Navigation 3 + Hilt, the ViewModel is
                // created fresh per nav entry (scoped to the entry's ViewModelStore by
                // `rememberViewModelStoreNavEntryDecorator`). Arguments are passed via the
                // assisted-inject factory: `hiltViewModel(creationCallback = { it.create(...) })`.
                // This is a separate ViewModel instance from the one created in ProfilePreviewSheet
                // — see ProfileFullRoute KDoc for the MVP trade-off explanation.
                ProfileRoute(
                    userId = route.userId,
                    pseudoHint = route.pseudo,
                    avatarUrlHint = route.avatarUrl,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
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
                        submitSignal = route.submitSignal,
                    ),
                    onOpenProfile = onOpenProfile,
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
                        // Pop the editor and refresh the topic page. `targetPage` is parsed
                        // from HFR's success URL and tells us which page to land on;
                        // `scrollTo` is the numreponse the parser extracted from the
                        // `#t{N}` URL fragment (quote / edit post), or null when HFR
                        // anchored `#bas` (plain reply). The bumped `submitSignal` invalidates
                        // the route key so the topic screen rebuilds its ViewModel and
                        // force-fetches the page — without it, the cache-aside path would
                        // serve a page that pre-dates the freshly-published post (#200).
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
                                    // Issue #200 — do NOT fall back to topicEntry.scrollTo here.
                                    // A topic opened from Flags / deep link / search may already carry
                                    // a `scrollTo` (the post the user was originally jumping to). After
                                    // a plain reply, the parser returns scrollTo=null because HFR anchors
                                    // `#bas` — if we re-use the previous scrollTo, the topic screen scrolls
                                    // back to that old post and never to the freshly-published reply, and
                                    // the `ScrollToEndOfPage` fallback in `TopicViewModel.maybeEmitScroll`
                                    // is silently suppressed (it gates on `target == null`).
                                    scrollTo = scrollTo,
                                    submitSignal = System.currentTimeMillis(),
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
                        // Phase 2D (#148) — pop the FP form, replace the topic route below
                        // with one that refreshes the target page and scrolls to the edited
                        // first post. Same pattern as `PostEditorRoute.onSubmitSucceeded`;
                        // `submitSignal` bumps the route key so the topic screen rebuilds
                        // and force-fetches the page (issue #200) — otherwise the cache-
                        // aside path could serve a stale page that pre-dates the FP edit.
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        val topicEntry = backStack.lastOrNull() as? TopicRoute
                        if (topicEntry != null) {
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(
                                topicEntry.copy(
                                    page = targetPage ?: topicEntry.page,
                                    // Issue #200 — do NOT fall back to topicEntry.scrollTo here.
                                    // A topic opened from Flags / deep link / search may already carry
                                    // a `scrollTo` (the post the user was originally jumping to). After
                                    // a plain reply, the parser returns scrollTo=null because HFR anchors
                                    // `#bas` — if we re-use the previous scrollTo, the topic screen scrolls
                                    // back to that old post and never to the freshly-published reply, and
                                    // the `ScrollToEndOfPage` fallback in `TopicViewModel.maybeEmitScroll`
                                    // is silently suppressed (it gates on `target == null`).
                                    scrollTo = scrollTo,
                                    submitSignal = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                    onNewTopicCreated = { cat, subcat, newTopicId, newNumreponse ->
                        // Phase 2E (#149) / #206. Two paths :
                        //  - Nominal : `newTopicId` is non-null (extracted from the
                        //    bddpost.php success refresh URL), jump straight to the
                        //    fresh topic so the user sees their first post.
                        //  - Fallback : HFR returned a success without a parsable
                        //    `sujet_` segment, so `newTopicId` is null — pop the
                        //    composer and replace it with a [CategoryRoute] pointing
                        //    at the d'arrivée sub-category. The screen-side Toast
                        //    tells the user the POST went through.
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
                                    // Bump the route key like the reply/quote/edit paths so the
                                    // topic screen builds fresh (not from a stale cache entry)
                                    // and scrolls to the freshly-created first post (#206).
                                    submitSignal = System.currentTimeMillis(),
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
