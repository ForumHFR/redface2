package fr.forumhfr.redface2.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import fr.forumhfr.redface2.BuildConfig
import fr.forumhfr.redface2.R
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
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
import fr.forumhfr.redface2.feature.messages.PrivateMessageThreadRequest
import fr.forumhfr.redface2.feature.messages.PrivateMessageThreadScreen
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
data class PrivateMessageThreadRoute(
    val threadId: Int,
    val page: Int = 1,
) : RedfaceNavKey

@Serializable
data class CategoryRoute(
    val cat: Int,
    val subcat: Int? = null,
    val page: Int = 1,
    /**
     * #206 workaround (« Exact post-création »). When the listing is reached right after a
     * successful create-topic POST, this carries the **exact posted title**. HFR redirects
     * a create to the category listing and never returns the new topic id (#214), so direct
     * navigation is impossible — instead the listing highlights the row whose title matches
     * this value (trimmed, case-insensitive). `null` on every normal navigation path (forum
     * tap, deep link, sub-category switch) → no highlight.
     */
    val highlightTitle: String? = null,
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
    /**
     * #231 — `true` when this route is pushed from a drapeau/flag tap. The topic screen
     * still shows the cached page instantly but always refreshes afterwards (bypassing the
     * 60s snappy-cache TTL), so opening a followed topic to catch up never shows it stale.
     * `false` on ordinary navigation (forum / deep link / back-nav) to keep the cache snappy.
     */
    val forceRefresh: Boolean = false,
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
     * `ref` parameter extracted from HFR's quote link on the source post when HFR
     * exposed it in clear HTML. Opaque positional id ; forwarded as-is to
     * `HfrClient.getReplyForm`. `null` for a simple reply or for an obfuscated /
     * absent quote link ; quote still works from `quotedNumreponse` alone.
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RedfaceApp(intent: Intent?) {
    // #286 — resolve the persisted theme selection before applying RedfaceTheme. SYSTEM (default)
    // keeps the historical isSystemInDarkTheme() behaviour; LIGHT/DARK force the app theme.
    val themeViewModel: AppThemeViewModel = hiltViewModel()
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val amoledEnabled by themeViewModel.amoledEnabled.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // #286 — keep the system bar ICON contrast in sync with the EFFECTIVE app theme, not the OS night
    // mode. MainActivity calls enableEdgeToEdge() once, whose default SystemBarStyle derives bar icon
    // contrast from the OS uiMode; once the user forces LIGHT/DARK against the OS, the status /
    // navigation bar icons would otherwise keep the OS contrast (e.g. light icons on a forced-light
    // background = invisible). SideEffect re-asserts it after each themed recomposition.
    val view = LocalView.current
    // Resolve the host Activity defensively (Context.findActivity) instead of casting view.context
    // directly: RedfaceApp is mounted under MainActivity today, but a future ContextWrapper in the
    // chain would make a hard `as Activity` cast crash. isInEditMode guards the @Preview path.
    val window = view.context.findActivity()?.window
    if (!view.isInEditMode && window != null) {
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    RedfaceTheme(darkTheme = darkTheme, amoledTheme = amoledEnabled) {
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
        var readPrivateMessageThreadIds by remember { mutableStateOf(emptySet<Int>()) }
        // Ephemeral, in-memory only (NOT persisted in any Navigation route): threads the user
        // opened from the inbox as multi-recipient (MultiMP / "DT"). Lets the thread screen show
        // "Interlocuteurs multiples" even when the current page reveals a single other author.
        // Purged on every auth transition, exactly like readPrivateMessageThreadIds, so private
        // metadata never outlives the session / account.
        var multiRecipientThreadIds by remember { mutableStateOf(emptySet<Int>()) }

        LaunchedEffect(authState) {
            when (authState) {
                null -> Unit
                AuthState.Anonymous -> {
                    readPrivateMessageThreadIds = emptySet()
                    multiRecipientThreadIds = emptySet()
                    resetStack(messagesBackStack, MessagesRoute, MessagesRoute)
                }
                is AuthState.Authenticated -> {
                    readPrivateMessageThreadIds = emptySet()
                    multiRecipientThreadIds = emptySet()
                    resetStack(messagesBackStack, MessagesRoute, MessagesRoute)
                }
            }
        }

        // #624 — the post/topic editor pins an « Envoyer » bar above the keyboard. Inside the bottom-nav
        // scaffold that bar sat ABOVE the navigation component, so the window-relative IME inset overshot
        // it by the nav bar height (the bar floated mid-screen with a gap). Hiding the navigation for editor
        // routes makes the editor full-screen: its submit bar then sits at the window bottom and the IME
        // inset lands exactly on the keyboard. Bonus UX: no tab switching mid-compose (would drop the draft).
        val topRoute = backStacks.getValue(currentDestination).lastOrNull()
        val navLayoutType = if (topRoute is PostEditorRoute || topRoute is TopicFormRoute) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        }

        NavigationSuiteScaffold(
            layoutType = navLayoutType,
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
                    privateMessageNavState = PrivateMessageNavState(
                        readThreadIds = readPrivateMessageThreadIds,
                        multiRecipientThreadIds = multiRecipientThreadIds,
                        onThreadLoaded = { threadId ->
                            readPrivateMessageThreadIds = readPrivateMessageThreadIds + threadId
                        },
                        onThreadOpenedAsMulti = { threadId ->
                            multiRecipientThreadIds = multiRecipientThreadIds + threadId
                        },
                    ),
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

/**
 * Ephemeral private-message navigation state, held in memory by the nav host and purged on every
 * auth transition. NONE of it is serialized into a Navigation route: [PrivateMessageThreadRoute]
 * stays opaque (threadId + page only) so private metadata never survives logout / account change
 * / process restore in the back stack.
 *
 * @property readThreadIds threads already opened (optimistic read marking in the inbox list).
 * @property multiRecipientThreadIds threads opened from the inbox as multi-recipient (MultiMP /
 *   "DT") — a UI hint so the thread header reads "Interlocuteurs multiples" even when the current
 *   page shows a single other author.
 * @property onThreadLoaded marks a thread read once its first page has successfully loaded.
 * @property onThreadOpenedAsMulti records that a thread was opened from a multi-recipient row.
 */
private data class PrivateMessageNavState(
    val readThreadIds: Set<Int>,
    val multiRecipientThreadIds: Set<Int>,
    val onThreadLoaded: (Int) -> Unit,
    val onThreadOpenedAsMulti: (Int) -> Unit,
)

@Composable
@Suppress("CyclomaticComplexMethod") // One entry per top-level route + per-screen navigation callbacks ;
// splitting the host would just push the same `when` shape one level deeper without reducing complexity.
private fun RedfaceNavHost(
    backStack: NavBackStack<NavKey>,
    accountMenu: @Composable () -> Unit,
    privateMessageNavState: PrivateMessageNavState,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
) {
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        // #282 — a topic page change (swipe) replaces the top TopicRoute with the same route at a new
        // page; our gesture already slides the outgoing page off-screen, so the default 700 ms NavDisplay
        // cross-fade is redundant AND keeps the incoming entry below RESUMED for its whole duration —
        // exactly the window the swipe is gated off. Making the FORWARD topic→topic transition instant
        // collapses that dead-zone to ~one frame. The pop direction ALWAYS cross-fades: a page change is
        // always a forward in-place replace (never a pop), so a genuine back-pop never goes instant even
        // if two TopicRoute entries ever coexist. Every other transition keeps nav3's default cross-fade.
        transitionSpec = { navContentTransform(initialState, targetState) },
        popTransitionSpec = { navCrossfade() },
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
                                // #231 — a flag open means « catch up on new posts » → refresh
                                // past the 60s snappy-cache TTL (the cached page is still shown
                                // instantly first). Avoids landing on a stale followed topic.
                                forceRefresh = true,
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
                MessagesScreen(
                    readThreadIds = privateMessageNavState.readThreadIds,
                    onOpenThread = { threadId, isMultiRecipient ->
                        // Record the multi-recipient hint in memory only; the route stays opaque.
                        if (isMultiRecipient) {
                            privateMessageNavState.onThreadOpenedAsMulti(threadId)
                        }
                        backStack.add(
                            PrivateMessageThreadRoute(
                                threadId = threadId,
                            ),
                        )
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<PrivateMessageThreadRoute> { route ->
                PrivateMessageThreadScreen(
                    request = PrivateMessageThreadRequest(
                        threadId = route.threadId,
                        page = route.page,
                    ),
                    isMultiRecipientHint = route.threadId in privateMessageNavState.multiRecipientThreadIds,
                    onLoaded = {
                        privateMessageNavState.onThreadLoaded(route.threadId)
                    },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    topBarActions = accountMenu,
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
                        // #206 workaround — forwards the freshly-created topic title so the
                        // listing highlights its row by exact-title match. Null on every
                        // normal nav path → no highlight.
                        highlightTitle = route.highlightTitle,
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
            entry<TopicRoute>(metadata = mapOf(TOPIC_SCENE_METADATA_KEY to true)) { route ->
                TopicScreen(
                    request = TopicRequest(
                        cat = route.cat,
                        post = route.post,
                        page = route.page,
                        scrollTo = route.scrollTo,
                        submitSignal = route.submitSignal,
                        forceRefresh = route.forceRefresh,
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
                        // #282 — replace the top entry IN PLACE rather than removeAt + add. The two-step
                        // version briefly leaves the parent on top (size-1), an observable intermediate
                        // state NavDisplay can start transitioning toward; an indexed set is a single
                        // mutation straight from TopicRoute(page=N) to TopicRoute(page=target).
                        backStack[backStack.lastIndex] = TopicRoute(
                            cat = route.cat,
                            post = route.post,
                            page = targetPage,
                            scrollTo = null,
                        )
                    },
                    onBack = {
                        // #285 — explicit back affordance in the topic top bar. Pop to the screen that
                        // opened the topic (list / flags). Guard size > 1 so we never pop a tab root
                        // (mirrors the global back handling used across the other entries).
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onNavigateToLastPage = { lastPage ->
                        // #226 — the plain reply overflowed onto a freshly created page; land the user
                        // there (their reply lives on the last page, not the stale form page). We do
                        // NOT carry a submitSignal here: a second force-refresh would re-run the overflow
                        // guard and, if a concurrent post created yet another page during the refresh
                        // window, keep chasing the moving tail (review finding). A plain cache-aside load
                        // surfaces the reply without re-triggering the redirect. Indexed set (not
                        // removeAt + add) for the same single-mutation reason as onOpenPage (#282).
                        backStack[backStack.lastIndex] = TopicRoute(
                            cat = route.cat,
                            post = route.post,
                            page = lastPage,
                            scrollTo = null,
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
                    onNewTopicCreated = { cat, subcat, newTopicId, newNumreponse, subject ->
                        // Phase 2E (#149) / #206. Two paths :
                        //  - Fallback (the real one) : `newTopicId` is null — HFR redirects a
                        //    create to the category listing and never returns the new id (#214).
                        //    Pop the composer and replace it with a [CategoryRoute] pointing at
                        //    the d'arrivée sub-category, carrying `highlightTitle = subject` so
                        //    the listing highlights the freshly-created row by exact-title match
                        //    (« Exact post-création »). The screen-side Toast also tells the user
                        //    the POST went through.
                        //  - Nominal (dead for create, kept for safety) : if HFR ever started
                        //    returning a parsable `sujet_` segment, `newTopicId` would be non-null
                        //    and we'd jump straight to the fresh topic. Never exercised today.
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
                            // the right rail, and pass the posted title so the row
                            // created by this POST is highlighted on arrival (#206).
                            val categoryBelow = backStack.lastOrNull() as? CategoryRoute
                            if (categoryBelow != null) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                            backStack.add(
                                CategoryRoute(
                                    cat = cat,
                                    subcat = subcat,
                                    page = 1,
                                    highlightTitle = subject,
                                ),
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

/**
 * #286 — walk the Context chain to the host [Activity] (or null), so the system-bar SideEffect never
 * crashes on a non-Activity / ContextWrapper context. Tail-recursive over [ContextWrapper.baseContext].
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Default NavDisplay cross-fade duration, mirroring nav3 1.1.1 `defaultTransitionSpec` (700 ms). */
private const val NAV_CROSSFADE_MILLIS = 700

/** nav3 1.1.1 default transition: a 700 ms cross-fade (mirrors androidx `defaultTransitionSpec`). */
private fun navCrossfade(): ContentTransform =
    fadeIn(tween(NAV_CROSSFADE_MILLIS)) togetherWith fadeOut(tween(NAV_CROSSFADE_MILLIS))

/**
 * Marks a [TopicRoute] NavEntry so [isTopicScene] can recognise a topic scene without relying on the
 * route type: nav3 1.1.1 exposes `Scene.key` as `route.toString()` (a String), not the route object,
 * so an `is TopicRoute` test on the scene key would never match. The entry's public metadata is the
 * stable signal instead.
 */
internal const val TOPIC_SCENE_METADATA_KEY = "fr.forumhfr.redface2.topicScene"

/**
 * True iff [metadata] carries the topic-scene marker. Null/empty/other-keys/false → false. Extracted
 * as a pure function so the marker contract (incl. the empty-`entries` → null case) is unit-testable
 * without a Compose/nav3 runtime.
 */
internal fun isTopicSceneMetadata(metadata: Map<String, Any>?): Boolean =
    metadata?.get(TOPIC_SCENE_METADATA_KEY) == true

/** True when this scene's top entry is a [TopicRoute] (tagged via [TOPIC_SCENE_METADATA_KEY]). */
private fun Scene<NavKey>.isTopicScene(): Boolean =
    isTopicSceneMetadata(entries.lastOrNull()?.metadata)

/**
 * Forward ContentTransform for a NavDisplay transition: instant for a topic→topic FORWARD transition
 * (the swipe page change is an in-place backStack replace — always forward, never a pop — collapsing
 * the dead-zone, see #282), nav3's default 700 ms cross-fade otherwise. Only used for the forward
 * direction; the pop direction always uses [navCrossfade]. A deep-link reset that swaps one topic for
 * another while already in a topic would also be instant here, which is benign.
 */
private fun navContentTransform(from: Scene<NavKey>, to: Scene<NavKey>): ContentTransform =
    if (from.isTopicScene() && to.isTopicScene()) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        navCrossfade()
    }
