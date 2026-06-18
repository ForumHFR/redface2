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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import fr.forumhfr.redface2.core.ui.R as CoreUiR
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu
import fr.forumhfr.redface2.core.ui.debug.DebugBoundsOverlay
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
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
import fr.forumhfr.redface2.feature.messages.PrivateMessageComposeScreen
import fr.forumhfr.redface2.feature.messages.PrivateMessageReplyRequest
import fr.forumhfr.redface2.feature.messages.PrivateMessageReplyScreen
import fr.forumhfr.redface2.feature.messages.PrivateMessageThreadRequest
import fr.forumhfr.redface2.feature.messages.PrivateMessageThreadScreen
import fr.forumhfr.redface2.feature.profile.ProfilePreviewSheet
import fr.forumhfr.redface2.feature.profile.ProfileRoute
import fr.forumhfr.redface2.feature.profile.ProfileViewModel
import fr.forumhfr.redface2.feature.search.SearchScreen
import fr.forumhfr.redface2.feature.settings.MyImagesScreen
import fr.forumhfr.redface2.feature.settings.SettingsAccountAboutScreen
import fr.forumhfr.redface2.feature.settings.SettingsCategoryDetailScreen
import fr.forumhfr.redface2.feature.settings.SettingsDisplayScreen
import fr.forumhfr.redface2.feature.settings.SettingsImagesScreen
import fr.forumhfr.redface2.feature.settings.SettingsMaintenanceScreen
import fr.forumhfr.redface2.feature.settings.SettingsBlacklistScreen
import fr.forumhfr.redface2.feature.settings.SettingsProxyScreen
import fr.forumhfr.redface2.feature.settings.SettingsScreen
import fr.forumhfr.redface2.feature.topic.TopicRequest
import fr.forumhfr.redface2.feature.topic.TopicScreen
import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor
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

/**
 * Author-only search pushed from the profile's « Derniers messages » button. A separate
 * route (NOT a parameter on [SearchRoute]) so the search tab root stays a serializable
 * `data object` — saved back stacks from previous app versions keep restoring — and so
 * the pushed screen gets its own nav-entry ViewModelStore, leaving the tab's idle
 * search state untouched. The entry pre-fills the author field and fires immediately
 * (cf. `SearchViewModel.initialPseudo`).
 */
@Serializable
data class SearchUserPostsRoute(val pseudo: String) : RedfaceNavKey

@Serializable
data object MessagesRoute : RedfaceNavKey

@Serializable
data class PrivateMessageThreadRoute(
    val threadId: Int,
    val page: Int = 1,
    /**
     * #301 — bumped to `System.currentTimeMillis()` by the navigation host when the private-message
     * reply editor pops back after a successful send. The new value invalidates the route key so a
     * fresh [PrivateMessageThreadViewModel] is created and re-fetches the conversation (there is no MP
     * cache, so a new entry always hits the network) — without it, returning to the retained entry
     * would show the conversation as it was before the reply. `null` on every normal nav path.
     */
    val submitSignal: Long? = null,
) : RedfaceNavKey

@Serializable
data class PrivateMessageReplyRoute(
    val threadId: Int,
    val page: Int = 1,
) : RedfaceNavKey

/**
 * #301 follow-up — standalone new-conversation composer, pushed from the MP list's « Nouveau »
 * button. [prefilledRecipient] rides HFR's `dest=` GET parameter and seeds the recipients field
 * (future « envoyer un MP à ce membre » entry points) ; the list button passes none.
 */
@Serializable
data class PrivateMessageComposeRoute(
    val prefilledRecipient: String? = null,
) : RedfaceNavKey

/**
 * Full-screen editor routes hide the navigation suite so their IME-pinned submit bar sits at the
 * window bottom (and to avoid dropping the draft on a tab switch). Extracted from `RedfaceApp` to
 * keep its cyclomatic complexity in check.
 */
private fun NavKey?.hidesNavigationSuite(): Boolean =
    this is PostEditorRoute || this is TopicFormRoute || this is PrivateMessageReplyRoute ||
        this is PrivateMessageComposeRoute

/**
 * #494 — type de barre de navigation à passer au [NavigationSuiteScaffold]. Sur téléphone l'adaptatif
 * renvoie `NavigationBar` (80dp) ; on lui substitue `ShortNavigationBarCompact` (M3 Expressive, ~64dp,
 * icône au-dessus du label, labels conservés, cible tactile ≥48dp). Les autres formes (rail/drawer sur
 * largeur medium/expanded) restent telles quelles ; la navigation est masquée (`None`) sur les routes
 * plein écran (éditeur, #624). Pure (l'`adaptiveType` composable est résolu côté appelant) + extraite
 * pour garder la complexité cyclomatique de `RedfaceApp` sous le seuil.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun resolveNavLayoutType(
    hidesNavigationSuite: Boolean,
    adaptiveType: NavigationSuiteType,
): NavigationSuiteType = when {
    hidesNavigationSuite -> NavigationSuiteType.None
    adaptiveType == NavigationSuiteType.NavigationBar -> NavigationSuiteType.ShortNavigationBarCompact
    else -> adaptiveType
}

/**
 * #529 — modifier appliqué au CONTENU du [NavigationSuiteScaffold]. Quand la suite est une barre du
 * BAS (téléphone), cette barre possède déjà `WindowInsets.navigationBars` : on consomme l'inset pour
 * le sous-arbre de contenu, de sorte que les `.navigationBarsPadding()` des écrans (toujours requis
 * pour les dispositions rail/drawer, où la suite est sur le côté et laisse l'inset bas) se résolvent
 * à 0 sous une barre du bas, au lieu de laisser une bande sombre de la couleur `Surface` au-dessus de
 * la barre (le « vide noir »). Le type custom `ShortNavigationBarCompact` ne consomme pas cet inset
 * pour le contenu côté scaffold, d'où la bande. Extrait (la branche reste hors de `RedfaceApp`).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun navSuiteContentInsetModifier(
    navLayoutType: NavigationSuiteType,
    navigationBarInsets: WindowInsets,
): Modifier = if (
    navLayoutType == NavigationSuiteType.ShortNavigationBarCompact ||
    navLayoutType == NavigationSuiteType.NavigationBar
) {
    // BOTTOM edge only : a side navigation bar (landscape / compact multi-window with 3-button
    // nav) reports a horizontal navigationBars inset too — consuming the whole thing would zero the
    // screens' horizontal `.navigationBarsPadding()` and run content under the side bar (Codex review).
    Modifier.consumeWindowInsets(navigationBarInsets.only(WindowInsetsSides.Bottom))
} else {
    Modifier
}

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
    /**
     * #226 — `true` only on the route re-pushed by [onNavigateToLastPage] after a plain reply
     * overflowed onto a freshly created last page. Forwarded to `TopicRequest.postSubmitOverflowLanding`.
     * Always paired with a fresh [submitSignal] (force-fetch, no stale cache) and tells the ViewModel
     * this is the overflow *landing*: scroll to the end of the fresh page, do NOT redirect again to
     * yet another last page (anti-chase under concurrent posting). Default `false` keeps every other
     * route — including the initial post-submit refresh — unaffected; defaulted so older serialised
     * back stacks deserialise without the field.
     */
    val postSubmitOverflowLanding: Boolean = false,
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
    /**
     * #291 multi-quote — `numreponse`s of the ADDITIONAL posts to quote after
     * [quotedNumreponse], in selection order. The editor replays the #146 quote
     * form fetch once per entry and concatenates the `[quotemsg]` prefills —
     * client-side only, no new HFR contract. Empty for single quote / plain
     * reply ; defaulted so older serialised back stacks deserialise.
     */
    val extraQuoteNumreponses: List<Int> = emptyList(),
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
 * #459 PR3 — « Mes images uploadées » screen (lists previously uploaded images + delete). Reached
 * from the Settings screen ; lives in `:feature:settings` (settings-adjacent), opaque route.
 */
@Serializable
data object MyImagesRoute : RedfaceNavKey

/**
 * #6 — read-only MPStorage inspector (debug). Reached from the Settings screen, only when the DT
 * section is enabled. Opaque route, no params (the screen owns its own fetch).
 */
@Serializable
data object MpStorageInspectorRoute : RedfaceNavKey

/**
 * #494 — settings sub-pages reached from the redesigned catalogue. Each is a distinct nav entry (a
 * distinct `ViewModelStore`), so each binds its own `SettingsViewModel`; DataStore is the single
 * source of truth so the instances stay consistent. Opaque routes, no params.
 */
@Serializable
data object SettingsProxyRoute : RedfaceNavKey

@Serializable
data object SettingsMaintenanceRoute : RedfaceNavKey

@Serializable
data object SettingsDisplayRoute : RedfaceNavKey

@Serializable
data object SettingsImagesRoute : RedfaceNavKey

@Serializable
data object SettingsAccountAboutRoute : RedfaceNavKey

/** #509 — sous-page « Utilisateurs masqués » (blacklist). */
@Serializable
data object SettingsBlacklistRoute : RedfaceNavKey

/** #494 v2 — détail générique d'une catégorie de réglages (cf. SettingsCategoryDetailScreen). */
@Serializable
data class SettingsCategoryRoute(val categoryId: String) : RedfaceNavKey

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
    val iconRes: Int,
    val rootRoute: RedfaceNavKey,
) {
    Flags(R.string.nav_flags, CoreUiR.drawable.ic_ms_flag, FlagsListRoute),
    Forum(R.string.nav_forum, CoreUiR.drawable.ic_ms_forum, ForumRoute),
    Search(R.string.nav_search, CoreUiR.drawable.ic_ms_search, SearchRoute),
    Messages(R.string.nav_messages, CoreUiR.drawable.ic_ms_mail, MessagesRoute),
    Settings(R.string.nav_settings, CoreUiR.drawable.ic_ms_settings, SettingsRoute),
}

/** #458 — maps the persisted cold-start choice onto the navigation's own destination enum. */
private fun StartScreenChoice.toTopLevelDestination(): TopLevelDestination = when (this) {
    StartScreenChoice.FLAGS -> TopLevelDestination.Flags
    StartScreenChoice.FORUM -> TopLevelDestination.Forum
    StartScreenChoice.MESSAGES -> TopLevelDestination.Messages
}

/** #313 — badge cap : beyond this the badge shows « 9+ » (page-1 proxy, cf. MpUnreadBadgeViewModel). */
private const val MAX_BADGE_COUNT = 9

/**
 * #313 — navigation item icon : the text glyph, plus the unread-MP count badge on the
 * « Messages » destination only, and only when the ViewModel resolved a positive count
 * ([mpUnreadCount] is null for 0/disabled/anonymous/failure). Capped at « 9+ » : page 1
 * of the inbox is the source, an exact two-digit count carries no extra signal at badge size.
 */
@Composable
private fun TopLevelDestinationIcon(destination: TopLevelDestination, mpUnreadCount: Int?) {
    val icon: @Composable () -> Unit = {
        Icon(painter = painterResource(destination.iconRes), contentDescription = null)
    }
    if (destination != TopLevelDestination.Messages || mpUnreadCount == null) {
        icon()
        return
    }
    BadgedBox(
        badge = {
            Badge {
                Text(
                    text = if (mpUnreadCount > MAX_BADGE_COUNT) {
                        stringResource(R.string.nav_messages_badge_overflow)
                    } else {
                        mpUnreadCount.toString()
                    },
                )
            }
        },
    ) {
        icon()
    }
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
    // #287 — reading presets (density + font scale) resolved at the root and bundled for RedfaceTheme.
    val displayDensity by themeViewModel.displayDensity.collectAsStateWithLifecycle()
    val fontScale by themeViewModel.fontScale.collectAsStateWithLifecycle()
    // #332 — « fold long quotes » reading preference, provided to the post renderer via RedfaceTheme.
    val foldLongQuotes by themeViewModel.foldLongQuotes.collectAsStateWithLifecycle()
    // #445 — debug bounds overlay preference (the dev-channel gate + render live in
    // [DevDebugBoundsOverlay], emitted last so it paints over everything; off by default).
    val debugBoundsOverlay by themeViewModel.debugBoundsOverlay.collectAsStateWithLifecycle()
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
    RedfaceTheme(
        darkTheme = darkTheme,
        amoledTheme = amoledEnabled,
        reading = ReadingDisplaySettings(
            density = displayDensity,
            fontScale = fontScale,
            foldLongQuotes = foldLongQuotes,
        ),
    ) {
        // #458 — cold-start screen, read synchronously from the bootstrap mirror and frozen for
        // the session. Only the INITIAL values below consume it: rememberSaveable and
        // rememberNavBackStack restore saved state first, so rotations / process restores keep
        // the user where they were, and a Settings change only applies on the next launch.
        val startScreenViewModel: StartScreenViewModel = hiltViewModel()
        val startScreen = startScreenViewModel.startScreen

        val flagsBackStack = rememberNavBackStack(FlagsListRoute)
        val forumStartCat = startScreen.forumCatId
            ?.takeIf { startScreen.screen == StartScreenChoice.FORUM }
        // SINGLE rememberNavBackStack call site on purpose (review Codex PR #464): two
        // conditional calls would occupy different saveable slots, so flipping the preference
        // between launches could orphan the saved Forum stack and reset it to the seed instead
        // of restoring. The pre-stacked category listing means back from it lands on the forum
        // root, like a manual navigation would.
        val forumInitialStack = if (forumStartCat != null) {
            arrayOf<NavKey>(ForumRoute, CategoryRoute(cat = forumStartCat))
        } else {
            arrayOf<NavKey>(ForumRoute)
        }
        // The spread copies a 1-2 element array once per cold start — the price of keeping the
        // single call site (rememberNavBackStack only has a vararg overload).
        @Suppress("SpreadOperator")
        val forumBackStack = rememberNavBackStack(*forumInitialStack)
        val searchBackStack = rememberNavBackStack(SearchRoute)
        val messagesBackStack = rememberNavBackStack(MessagesRoute)
        // #494 v2 — Réglages est désormais une destination top-level à part entière (5e onglet),
        // avec sa propre pile (sa racine = SettingsRoute), au lieu d'être poussé sur l'onglet actif.
        val settingsBackStack = rememberNavBackStack(SettingsRoute)

        var currentDestination by rememberSaveable {
            mutableStateOf(startScreen.screen.toTopLevelDestination())
        }

        // Phase 2 finish (#208) — profile bottom sheet state, hoisted to `:app` so that
        // `:feature:topic` never depends on `:feature:profile`. The sheet is opened from
        // any TopicScreen tap on an avatar/author with a non-null profileId.
        // rememberSaveable + custom Saver keeps the sheet open across configuration changes
        // (e.g. rotation) — without it the sheet would silently close on every rotation.
        var profileSheetRequest by rememberSaveable(stateSaver = ProfileSheetRequest.Saver) {
            mutableStateOf<ProfileSheetRequest?>(null)
        }

        val backStacks = remember(
            flagsBackStack,
            forumBackStack,
            searchBackStack,
            messagesBackStack,
            settingsBackStack,
        ) {
            mapOf(
                TopLevelDestination.Flags to flagsBackStack,
                TopLevelDestination.Forum to forumBackStack,
                TopLevelDestination.Search to searchBackStack,
                TopLevelDestination.Messages to messagesBackStack,
                TopLevelDestination.Settings to settingsBackStack,
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
        // #479 — avatar of the connected user for the top-bar account badge (null → pseudo initial).
        val accountAvatarUrl by accountViewModel.avatarUrl.collectAsStateWithLifecycle()
        // #313 — unread-MP badge on the « Messages » nav item. Same shared-instance logic as the
        // account ViewModel above. The ON_START hook refreshes the count when the app comes back
        // to the foreground (MPs received while backgrounded) ; the first start is skipped by the
        // ViewModel (the auth-flip fetch covers the cold start).
        val mpBadgeViewModel: MpUnreadBadgeViewModel = hiltViewModel()
        val mpUnreadCount by mpBadgeViewModel.unreadCount.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            mpBadgeViewModel.onAppForegrounded()
        }
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
        // #453 (Codex review) — threads that were UNREAD when opened from the inbox. The badge
        // decrement on first read must fire ONLY for these: opening an already-read conversation has
        // nothing to subtract. In-memory only and purged on auth transition, like the sets above —
        // it is private metadata (it reveals a conversation carried an unread message) and must never
        // outlive the session, hence it stays OUT of the opaque PrivateMessageThreadRoute.
        var unreadOnOpenThreadIds by remember { mutableStateOf(emptySet<Int>()) }
        // #301 follow-up — bumped when the new-conversation composer pops back after a successful
        // send. The MP list collects the signal and refreshes itself so the created conversation
        // appears at the top (its thread id is unknown — the bddpost success response of a new MP
        // is not topic-shaped). In-memory only, like the other private-message hints above.
        var privateMessageSentSignal by remember { mutableStateOf<Long?>(null) }

        // Bug fix (build 89) — per-topic title cache keyed by (cat, post). A page change replaces the
        // TopicRoute (new nav entry → new ViewModel → Loading with no topic), which used to flash the
        // generic « Sujet » title in the top bar. The topic screen reports its loaded title here; the
        // next page reads it back via TopicRequest.titleHint. Hoisted above NavDisplay so it survives
        // the entry recreation; keyed by (cat, post) so titles never bleed across categories.
        var topicTitleCache by remember { mutableStateOf(emptyMap<TopicTitleKey, String>()) }

        // #307 — per-page scroll anchors keyed by (cat, post, page), twin of topicTitleCache: a page
        // change destroys the nav entry (and its rememberSaveable LazyListState), so returning to an
        // already-visited page used to land at the top. The topic screen saves its read position here
        // on departure; the next landing on the same page restores it (unless the route carries a
        // scrollTo / submitSignal, cf. resolveTopicScrollRestoration). RAM/session only, like titles.
        var topicScrollAnchorCache by remember { mutableStateOf(emptyMap<TopicScrollKey, TopicScrollAnchor>()) }
        // #412 — transient « land at the bottom » marker, lifecycle-matched to the anchor cache
        // above (plain remember: lost on activity/process recreation, which falls back to the
        // pre-#412 top landing instead of replaying a stale bottom scroll).
        var topicPendingBottomLanding by remember { mutableStateOf<TopicScrollKey?>(null) }
        // #291 — multi-quote basket: numreponses selected for quoting, in tap order, keyed by
        // (cat, post) so a page change (which destroys the topic nav entry, cf. titles above)
        // keeps the cross-page selection while a different topic never sees it. One basket at a
        // time (selecting in another topic resets it — quoting is a single-topic act). Plain
        // remember: losing it on process death just means re-selecting, like the markers above.
        var multiQuoteBasket by remember { mutableStateOf<MultiQuoteBasket?>(null) }
        // #465 — per-topic MANUAL poll-expansion choice, keyed by (cat, post) (one poll per topic),
        // twin of topicTitleCache / topicScrollAnchorCache: a page change replaces the TopicRoute
        // (new nav entry → new ViewModel), so a `rememberSaveable` toggle inside the poll card was
        // re-seeded to the global default on every page. Hoisted above NavDisplay so collapsing /
        // expanding a poll survives navigation between the topic's pages. Absence of a key = follow
        // the `topicPollsExpanded` default; the toggle records the manual choice here. RAM/session
        // only, never serialized into a route.
        var topicPollExpansionCache by remember { mutableStateOf(emptyMap<TopicPollKey, Boolean>()) }

        LaunchedEffect(authState) {
            when (authState) {
                null -> Unit
                AuthState.Anonymous -> {
                    readPrivateMessageThreadIds = emptySet()
                    multiRecipientThreadIds = emptySet()
                    unreadOnOpenThreadIds = emptySet()
                    privateMessageSentSignal = null
                    // #291 — a write intention armed under another session must not survive the
                    // transition (Codex review: stale « Citer N » after logout/login).
                    multiQuoteBasket = null
                    resetStack(messagesBackStack, MessagesRoute, MessagesRoute)
                }
                is AuthState.Authenticated -> {
                    readPrivateMessageThreadIds = emptySet()
                    multiRecipientThreadIds = emptySet()
                    unreadOnOpenThreadIds = emptySet()
                    privateMessageSentSignal = null
                    multiQuoteBasket = null
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
        val adaptiveType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        val navLayoutType = resolveNavLayoutType(topRoute.hidesNavigationSuite(), adaptiveType)
        // #529 — consume the bottom nav-bar inset for the content only under a bottom-bar layout
        // (see navSuiteContentInsetModifier). Read in composable scope, branch lives in the helper.
        val contentInsetModifier = navSuiteContentInsetModifier(navLayoutType, WindowInsets.navigationBars)

        NavigationSuiteScaffold(
            layoutType = navLayoutType,
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { destination ->
                    item(
                        selected = currentDestination == destination,
                        onClick = { currentDestination = destination },
                        icon = {
                            TopLevelDestinationIcon(
                                destination = destination,
                                mpUnreadCount = mpUnreadCount,
                            )
                        },
                        label = { Text(text = stringResource(destination.labelRes)) },
                    )
                }
            },
        ) {
            // #398 — no global side gutter here. Each screen owns its own lateral rhythm
            // (listings keep their 16/24 dp content padding, readers compensate explicitly),
            // so the nav host no longer steals 8 dp/side from every screen. The Surface is kept
            // for the theme background/elevation; only its horizontal padding was removed.
            // #529 — consumes the bottom nav-bar inset under a bottom-bar layout (no-op otherwise).
            Surface(modifier = contentInsetModifier) {
                val activeBackStack = backStacks.getValue(currentDestination)
                val accountMenu: @Composable () -> Unit = {
                    RedfaceAccountMenu(
                        authState = authState,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        onLogin = { activeBackStack.add(LoginRoute) },
                        onLogout = accountViewModel::logout,
                        onOpenDiagnostics = { activeBackStack.add(DiagnosticsRoute) },
                        onReportContent = {
                            startReportEmail(context, reportEmailSubject, reportNoEmailClient)
                        },
                        avatarUrl = accountAvatarUrl,
                    )
                }
                RedfaceNavHost(
                    backStack = activeBackStack,
                    accountMenu = accountMenu,
                    onReportContent = {
                        startReportEmail(context, reportEmailSubject, reportNoEmailClient)
                    },
                    privateMessageNavState = PrivateMessageNavState(
                        readThreadIds = readPrivateMessageThreadIds,
                        multiRecipientThreadIds = multiRecipientThreadIds,
                        onThreadLoaded = { threadId ->
                            // #453 (Codex review) — decrement the badge ONLY when the conversation was
                            // unread when opened AND this is its first read of the session (predicate
                            // extracted to keep this composable under detekt's complexity threshold).
                            val decrement = shouldDecrementUnreadBadge(
                                threadId = threadId,
                                unreadOnOpen = unreadOnOpenThreadIds,
                                alreadyRead = readPrivateMessageThreadIds,
                            )
                            if (decrement) {
                                mpBadgeViewModel.onThreadRead(threadId)
                            }
                            readPrivateMessageThreadIds = readPrivateMessageThreadIds + threadId
                        },
                        onThreadOpenedAsMulti = { threadId ->
                            multiRecipientThreadIds = multiRecipientThreadIds + threadId
                        },
                        onThreadOpenedUnread = { threadId ->
                            unreadOnOpenThreadIds = unreadOnOpenThreadIds + threadId
                        },
                        sentSignal = privateMessageSentSignal,
                        onConversationSent = {
                            privateMessageSentSignal = System.currentTimeMillis()
                        },
                    ),
                    topicTitleNavState = TopicTitleNavState(
                        titles = topicTitleCache,
                        onTitleLoaded = { cat, post, title ->
                            topicTitleCache = topicTitleCache.withTitle(TopicTitleKey(cat, post), title)
                        },
                    ),
                    topicScrollNavState = TopicScrollNavState(
                        anchors = topicScrollAnchorCache,
                        onAnchorSaved = { cat, post, page, anchor ->
                            topicScrollAnchorCache = topicScrollAnchorCache.withScrollAnchor(
                                TopicScrollKey(cat, post, page),
                                anchor,
                            )
                        },
                        pendingBottomLanding = topicPendingBottomLanding,
                        onPendingBottomLanding = { topicPendingBottomLanding = it },
                    ),
                    multiQuoteNavState = MultiQuoteNavState(
                        basket = multiQuoteBasket,
                        onToggle = { cat, post, numreponse ->
                            multiQuoteBasket = multiQuoteBasket.toggled(cat, post, numreponse)
                        },
                        onClear = { multiQuoteBasket = null },
                    ),
                    topicPollNavState = TopicPollNavState(
                        expansions = topicPollExpansionCache,
                        onExpansionChanged = { cat, post, expanded ->
                            topicPollExpansionCache = topicPollExpansionCache.withPollExpansion(
                                TopicPollKey(cat, post),
                                expanded,
                            )
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

        // #445 — debug bounds overlay, emitted LAST so it paints on top of every sibling (the nav
        // scaffold + the profile sheet). The root content is Box-stacked by z-order = emission order,
        // so no explicit wrapper is needed.
        DevDebugBoundsOverlay(enabled = debugBoundsOverlay)
    }
}

/**
 * #445 — renders the [DebugBoundsOverlay] only when [enabled] AND the build is the DEV channel
 * ([BuildConfig.FLAVOR]). Both gates live here (not in [RedfaceApp]) so the channel check can NEVER be
 * bypassed and so [RedfaceApp] stays a single call site. Off the dev channel, or when the preference is
 * off, the overlay composable is never composed — zero cost.
 */
@Composable
private fun DevDebugBoundsOverlay(enabled: Boolean) {
    if (enabled && BuildConfig.FLAVOR == DEV_CHANNEL) {
        DebugBoundsOverlay()
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

// #445 — the `channel` product flavor whose BuildConfig.FLAVOR gates the debug bounds overlay. Matches
// the `dev` flavor name in app/build.gradle.kts; prod/beta never expose the overlay.
private const val DEV_CHANNEL: String = "dev"

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
 * @property onThreadOpenedUnread records that a thread was UNREAD when opened, so [onThreadLoaded]
 *   knows it may decrement the unread badge (an already-read conversation must not, #453).
 */
private data class PrivateMessageNavState(
    val readThreadIds: Set<Int>,
    val multiRecipientThreadIds: Set<Int>,
    val onThreadLoaded: (Int) -> Unit,
    val onThreadOpenedAsMulti: (Int) -> Unit,
    val onThreadOpenedUnread: (Int) -> Unit = {},
    /** #301 follow-up — last successful new-conversation send ; the MP list refreshes on change. */
    val sentSignal: Long? = null,
    /** #301 follow-up — bumps [sentSignal] when the composer reports a successful send. */
    val onConversationSent: () -> Unit = {},
)

/**
 * #453 (Codex review) — the unread badge decrements on a thread's first read of the session ONLY
 * when that thread was actually unread when opened ([unreadOnOpen]). Opening an already-read
 * conversation subtracts nothing, and re-opening one ([alreadyRead]) must not subtract twice.
 * Extracted from [onThreadLoaded] so the boolean connective stays out of RedfaceApp's cyclomatic
 * complexity budget.
 */
private fun shouldDecrementUnreadBadge(
    threadId: Int,
    unreadOnOpen: Set<Int>,
    alreadyRead: Set<Int>,
): Boolean = threadId in unreadOnOpen && threadId !in alreadyRead

/**
 * Bug fix (build 89) — per-topic title cache plumbed into [RedfaceNavHost]. A topic page change
 * replaces the TopicRoute (new nav entry → new ViewModel → Loading with no topic yet), which used to
 * flash the generic « Sujet » title in the top app bar. The `var` backing [titles] lives in
 * [RedfaceApp] so it survives the entry recreation; [onTitleLoaded] writes the freshly-loaded title
 * back and the next page reads it via TopicRequest.titleHint. Keyed by `(cat, post)` ([TopicTitleKey])
 * — a topic id is unique only per HFR category — so titles never bleed across categories. Same
 * read-map + onLoaded-callback shape as [PrivateMessageNavState].
 *
 * @property titles last known title per topic, fed into TopicRequest.titleHint.
 * @property onTitleLoaded records a topic's title once its page has loaded.
 */
private data class TopicTitleNavState(
    val titles: Map<TopicTitleKey, String>,
    val onTitleLoaded: (cat: Int, post: Int, title: String) -> Unit,
)

/**
 * #307 — per-page scroll-anchor cache plumbed into [RedfaceNavHost], twin of [TopicTitleNavState].
 * A topic page change replaces the TopicRoute in place (#282), destroying the nav entry and the
 * `rememberSaveable` `LazyListState` with it, so returning to an already-visited page landed at the
 * top. The `var` backing [anchors] lives in [RedfaceApp] so it survives the entry recreation;
 * [onAnchorSaved] records the read position when a topic screen leaves the composition and the next
 * landing on the same `(cat, post, page)` ([TopicScrollKey]) restores it — unless the route carries
 * a higher-priority scroll (`scrollTo` / `submitSignal`, cf. [resolveTopicScrollRestoration]). Same
 * read-map + on-event-callback shape as [TopicTitleNavState] / [PrivateMessageNavState]; in-memory
 * only (session-scoped), never serialized into a route.
 *
 * @property anchors last saved read position per visited topic page.
 * @property onAnchorSaved records a page's read position when its screen is disposed.
 * @property pendingBottomLanding #412 — the one page currently owed a bottom landing (armed by
 *   `onOpenPage` on a strict « page - 1 » step, cleared on any other page change and consumed by
 *   the screen after its first `Loaded`). Deliberately transient nav state, NOT a `TopicRoute`
 *   field: a serialized route would replay the bottom landing on process/configuration restore and
 *   could override the user's restored position (Codex review on PR #420). Losing it with the
 *   composition just falls back to the pre-#412 top landing.
 * @property onPendingBottomLanding rewrites the pending key (null clears it).
 */
private data class TopicScrollNavState(
    val anchors: Map<TopicScrollKey, TopicScrollAnchor>,
    val onAnchorSaved: (cat: Int, post: Int, page: Int, anchor: TopicScrollAnchor) -> Unit,
    val pendingBottomLanding: TopicScrollKey? = null,
    val onPendingBottomLanding: (TopicScrollKey?) -> Unit = {},
)

/**
 * #291 — multi-quote nav bundle threaded into [RedfaceNavHost], same shape as the other
 * hoisted-state bundles ([TopicScrollNavState], `TopicTitleNavState`).
 */
private data class MultiQuoteNavState(
    val basket: MultiQuoteBasket?,
    val onToggle: (cat: Int, post: Int, numreponse: Int) -> Unit,
    val onClear: () -> Unit,
)

/**
 * #465 — per-topic poll-expansion bundle threaded into [RedfaceNavHost], same shape and survival
 * rationale as the other hoisted-state bundles ([TopicScrollNavState], [TopicTitleNavState]): a page
 * change replaces the TopicRoute entry, so any expansion state owned by the topic screen would die
 * with it. The `var` backing [expansions] lives in [RedfaceApp]. A `null` lookup (no entry for the
 * topic) means « follow the global default »; [onExpansionChanged] records the user's manual toggle.
 *
 * @property expansions the manual collapse/expand choice per topic the user has toggled.
 * @property onExpansionChanged records a topic's manual poll choice when the card is tapped.
 */
private data class TopicPollNavState(
    val expansions: Map<TopicPollKey, Boolean>,
    val onExpansionChanged: (cat: Int, post: Int, expanded: Boolean) -> Unit,
)

/**
 * #291 — multi-quote selection, hoisted to RedfaceApp (same survival rationale as
 * [TopicScrollNavState]: a page change replaces the TopicRoute entry, so any state owned by the
 * topic screen dies with it). [numreponses] keeps SELECTION ORDER — the quotes are concatenated
 * in the order the user tapped them, not post order.
 */
internal data class MultiQuoteBasket(
    val cat: Int,
    val post: Int,
    val numreponses: List<Int>,
) {
    fun matches(cat: Int, post: Int): Boolean = this.cat == cat && this.post == post
}

/**
 * Toggles [numreponse] in the basket for topic ([cat], [post]). Selecting in a DIFFERENT topic
 * replaces the basket (one quoting act at a time); removing the last entry clears it to null so
 * the « Citer N » affordance disappears instead of advertising an empty selection.
 */
internal fun MultiQuoteBasket?.toggled(cat: Int, post: Int, numreponse: Int): MultiQuoteBasket? {
    val current = this?.takeIf { it.matches(cat, post) }
        ?: return MultiQuoteBasket(cat, post, listOf(numreponse))
    val next = if (numreponse in current.numreponses) {
        current.numreponses - numreponse
    } else {
        current.numreponses + numreponse
    }
    return if (next.isEmpty()) null else current.copy(numreponses = next)
}

/**
 * Composite cache key for [TopicTitleNavState]. A topic id (`post`) is unique only **per HFR
 * category**, not globally — two categories can theoretically expose the same id (cf. the same
 * `(cat, topicId)` composite key in `SearchScreen`), so keying by `post` alone could flash the
 * wrong title across categories while a page loads. Keyed by `(cat, post)` to stay correct.
 */
internal data class TopicTitleKey(val cat: Int, val post: Int)

// Upper bound on the per-topic title cache (display hint only). A long reading session opens many
// topics; capping at a generous size keeps the map from growing unbounded for the app's lifetime.
// Eviction is FIFO (oldest insertions dropped) — losing a stale hint just falls back to « Sujet »
// for one loading frame, which is harmless.
internal const val TOPIC_TITLE_CACHE_MAX = 128

/**
 * Inserts [title] for [key] into the per-topic title cache, evicting the oldest entries past
 * [TOPIC_TITLE_CACHE_MAX]. `Map + pair` preserves insertion order (LinkedHashMap), so dropping from
 * the front evicts the least-recently-inserted titles. Extracted from [RedfaceApp] to keep that
 * composable under the cyclomatic-complexity budget.
 */
internal fun Map<TopicTitleKey, String>.withTitle(key: TopicTitleKey, title: String): Map<TopicTitleKey, String> {
    // Short-circuit when the title is unchanged: a page change within the same topic re-emits the
    // identical loaded title, and re-inserting it would allocate a fresh map + trigger a global
    // RedfaceApp recomposition for nothing.
    if (this[key] == title) return this
    val updated = this + (key to title)
    return if (updated.size > TOPIC_TITLE_CACHE_MAX) {
        updated.entries.drop(updated.size - TOPIC_TITLE_CACHE_MAX).associate { it.toPair() }
    } else {
        updated
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "LongParameterList") // One entry per top-level route + per-screen
// navigation callbacks ; splitting the host would just push the same `when` shape one level deeper
// without reducing complexity. Param count: each nav-state bundle has a distinct owner/call-site.
private fun RedfaceNavHost(
    backStack: NavBackStack<NavKey>,
    accountMenu: @Composable () -> Unit,
    // #494 — the « Signaler un contenu » row of the settings Account/About sub-page reuses the same
    // report-email flow as the account menu (which owns `context` + the report strings).
    onReportContent: () -> Unit,
    privateMessageNavState: PrivateMessageNavState,
    // Bug fix (build 89) — per-topic title cache threaded down from RedfaceApp (where the `var` lives
    // so it survives entry recreation across page changes). Bundled to keep the param count in check.
    topicTitleNavState: TopicTitleNavState,
    // #307 — per-page scroll-anchor cache, same hoisting rationale as topicTitleNavState.
    topicScrollNavState: TopicScrollNavState,
    // #291 — multi-quote basket, same hoisting rationale (survives the per-page entry swap).
    multiQuoteNavState: MultiQuoteNavState,
    // #465 — per-topic poll-expansion cache, same hoisting rationale (survives the per-page swap).
    topicPollNavState: TopicPollNavState,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
) {
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        // Transitions (Claude + Codex, remplace le crossfade 700 ms global) : shared-axis X léger pour le
        // drill-down (forward/back), fade-through court pour un changement d'onglet, instantané pour le
        // swipe topic→topic (#282 : le geste glisse déjà la page sortante ; un crossfade garderait l'entrée
        // entrante sous RESUMED toute la durée — pile la fenêtre où le swipe est gaté). Le sens vient du
        // spec appelé (transitionSpec = forward/replace, popTransitionSpec = pop). Changer d'onglet remplace
        // le backStack → ça passe par transitionSpec : on le détecte par le changement de racine de pile
        // (chaque onglet a une racine distincte) pour ne pas hériter du slide de drill-down.
        transitionSpec = { navForwardTransform(initialState, targetState) },
        popTransitionSpec = { navPopTransform(initialState, targetState) },
        predictivePopTransitionSpec = { navPopTransform(initialState, targetState) },
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
                    // #414 — category band tap: push the listing INSIDE the Flags tab so back
                    // returns to the flags list (less surprising than switching to the Forum tab).
                    onOpenCategory = { catId ->
                        backStack.add(CategoryRoute(cat = catId, subcat = null, page = 1))
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
                    onOpenTopic = { cat, post, page, scrollTo ->
                        // #277 — the callback receives the FINAL values : `page` was resolved
                        // by SearchViewModel through HFR's server-side redirect when the row
                        // carried a matched numreponse (the search href always says page=1).
                        backStack.add(
                            TopicRoute(
                                cat = cat,
                                post = post,
                                page = page,
                                scrollTo = scrollTo,
                            ),
                        )
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<SearchUserPostsRoute> { route ->
                // Profile « Derniers messages » : same screen as the search tab, but
                // pushed onto the current tab's stack with the author pre-filled and the
                // search fired at construction. `onBack` gives the pushed entry its back
                // affordance (the tab root never sets it).
                SearchScreen(
                    onOpenTopic = { cat, post, page, scrollTo ->
                        backStack.add(
                            TopicRoute(
                                cat = cat,
                                post = post,
                                page = page,
                                scrollTo = scrollTo,
                            ),
                        )
                    },
                    initialPseudo = route.pseudo,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
            }
            entry<MessagesRoute> {
                MessagesScreen(
                    readThreadIds = privateMessageNavState.readThreadIds,
                    onOpenThread = { threadId, isMultiRecipient, openAtPage, wasUnread ->
                        // Record the multi-recipient hint in memory only; the route stays opaque.
                        if (isMultiRecipient) {
                            privateMessageNavState.onThreadOpenedAsMulti(threadId)
                        }
                        // #453 (Codex review) — remember the unread-on-open state so the badge only
                        // decrements for a conversation that actually had something unread.
                        if (wasUnread) {
                            privateMessageNavState.onThreadOpenedUnread(threadId)
                        }
                        backStack.add(
                            PrivateMessageThreadRoute(
                                threadId = threadId,
                                // #430 — web parity: open on the conversation's last page (the
                                // inbox "Pages" link), not page 1. The ViewModel may still land
                                // further via the locally saved reading position.
                                page = openAtPage,
                            ),
                        )
                    },
                    onComposeNew = { backStack.add(PrivateMessageComposeRoute()) },
                    sentSignal = privateMessageNavState.sentSignal,
                    topBarActions = accountMenu,
                )
            }
            entry<PrivateMessageComposeRoute> { route ->
                PrivateMessageComposeScreen(
                    initialRecipient = route.prefilledRecipient,
                    onSubmitSucceeded = {
                        // Pop the composer, then bump the sent signal so the MP list refreshes —
                        // the created thread id is unknown (the bddpost success response of a new
                        // conversation is not topic-shaped), so there is no thread to land on.
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        privateMessageNavState.onConversationSent()
                    },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
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
                    onReply = { threadId, page ->
                        backStack.add(PrivateMessageReplyRoute(threadId = threadId, page = page))
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<PrivateMessageReplyRoute> { route ->
                PrivateMessageReplyScreen(
                    request = PrivateMessageReplyRequest(
                        threadId = route.threadId,
                        page = route.page,
                    ),
                    onSubmitSucceeded = { threadId, page ->
                        // Pop the editor, then replace the conversation entry with a fresh key
                        // (bumped submitSignal) so the thread re-fetches and shows the sent message.
                        // Mirrors PostEditorRoute.onSubmitSucceeded. Never collapse below the tab root.
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                        val threadEntry = backStack.lastOrNull() as? PrivateMessageThreadRoute
                        if (threadEntry != null) {
                            backStack.removeAt(backStack.lastIndex)
                            backStack.add(
                                threadEntry.copy(
                                    page = page,
                                    submitSignal = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
            }
            entry<SettingsRoute> {
                SettingsScreen(
                    onOpenProxy = { backStack.add(SettingsProxyRoute) },
                    onOpenMaintenance = { backStack.add(SettingsMaintenanceRoute) },
                    onOpenDisplay = { backStack.add(SettingsDisplayRoute) },
                    onOpenImages = { backStack.add(SettingsImagesRoute) },
                    onOpenAccountAbout = { backStack.add(SettingsAccountAboutRoute) },
                    onOpenBlacklist = { backStack.add(SettingsBlacklistRoute) },
                    // #494 v2 — catégories sans sous-page dédiée → détail générique.
                    onOpenCategory = { categoryId -> backStack.add(SettingsCategoryRoute(categoryId)) },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsCategoryRoute> { key ->
                SettingsCategoryDetailScreen(
                    categoryId = key.categoryId,
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onOpenProxy = { backStack.add(SettingsProxyRoute) },
                    onOpenMaintenance = { backStack.add(SettingsMaintenanceRoute) },
                    onOpenDisplay = { backStack.add(SettingsDisplayRoute) },
                    onOpenImages = { backStack.add(SettingsImagesRoute) },
                    onOpenAccountAbout = { backStack.add(SettingsAccountAboutRoute) },
                    onOpenBlacklist = { backStack.add(SettingsBlacklistRoute) },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsBlacklistRoute> {
                SettingsBlacklistScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsProxyRoute> {
                SettingsProxyScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsMaintenanceRoute> {
                SettingsMaintenanceScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                    onOpenMpStorageInspector = { backStack.add(MpStorageInspectorRoute) },
                    // #445 — expose the debug bounds overlay toggle on the dev channel only.
                    debugOverlayAvailable = BuildConfig.FLAVOR == DEV_CHANNEL,
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsDisplayRoute> {
                SettingsDisplayScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsImagesRoute> {
                SettingsImagesScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    onOpenMyImages = { backStack.add(MyImagesRoute) },
                    topBarActions = accountMenu,
                )
            }
            entry<SettingsAccountAboutRoute> {
                SettingsAccountAboutScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    onOpenDiagnostics = { backStack.add(DiagnosticsRoute) },
                    onReportContent = onReportContent,
                    topBarActions = accountMenu,
                )
            }
            entry<MyImagesRoute> {
                MyImagesScreen(
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
            }
            entry<MpStorageInspectorRoute> {
                fr.forumhfr.redface2.feature.settings.MpStorageInspectorScreen(
                    onClose = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    },
                )
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
                    onShowUserPosts = { pseudo ->
                        backStack.add(SearchUserPostsRoute(pseudo = pseudo))
                    },
                )
            }
            entry<TopicRoute>(metadata = mapOf(TOPIC_SCENE_METADATA_KEY to true)) { route ->
                // #307 — resolve what the initial scroll of this landing should do. Strict priority
                // (route scrollTo > post-submit landing > saved anchor > top) lives in the pure
                // resolver; only a RestoreSaved outcome hands the screen an anchor to apply — the
                // Follow* levels resolve to null so the existing ScrollToPost / ScrollToEndOfPage
                // effects (#200/#226/#344) keep sole ownership of their landings.
                val scrollRestoration = resolveTopicScrollRestoration(
                    scrollTo = route.scrollTo,
                    submitSignal = route.submitSignal,
                    savedAnchor = topicScrollNavState
                        .anchors[TopicScrollKey(route.cat, route.post, route.page)],
                    // #412 — armed by onOpenPage on a strict « page - 1 » step; transient nav
                    // state rather than a route field (Codex review on PR #420: a serialized
                    // flag would replay the bottom landing on process/config restore).
                    previousPageLanding = topicScrollNavState.pendingBottomLanding ==
                        TopicScrollKey(route.cat, route.post, route.page),
                )
                TopicScreen(
                    request = TopicRequest(
                        cat = route.cat,
                        post = route.post,
                        page = route.page,
                        scrollTo = route.scrollTo,
                        submitSignal = route.submitSignal,
                        forceRefresh = route.forceRefresh,
                        postSubmitOverflowLanding = route.postSubmitOverflowLanding,
                        titleHint = topicTitleNavState.titles[TopicTitleKey(route.cat, route.post)],
                    ),
                    onTitleLoaded = { title ->
                        topicTitleNavState.onTitleLoaded(route.cat, route.post, title)
                    },
                    restoreScrollAnchor =
                        (scrollRestoration as? TopicScrollRestoration.RestoreSaved)?.anchor,
                    startAtBottom = scrollRestoration is TopicScrollRestoration.StartAtBottom,
                    onStartAtBottomConsumed = {
                        // One-shot: once the screen has executed (or skipped) the bottom landing
                        // for this page, drop the marker so it can never replay.
                        topicScrollNavState.onPendingBottomLanding(null)
                    },
                    onScrollAnchorSaved = { anchor ->
                        topicScrollNavState.onAnchorSaved(route.cat, route.post, route.page, anchor)
                    },
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
                    // #291 — selection of THIS topic's basket (another topic's selection must
                    // never leak into the menu checkmarks or the « Citer N » FAB).
                    multiQuoteSelection = multiQuoteNavState.basket
                        ?.takeIf { it.matches(route.cat, route.post) }
                        ?.numreponses
                        .orEmpty(),
                    onToggleMultiQuote = { numreponse ->
                        multiQuoteNavState.onToggle(route.cat, route.post, numreponse)
                    },
                    // #465 — the topic's saved manual poll choice (null = follow the global
                    // default), and the callback recording a tap on the poll card. Hoisted to
                    // :app so it survives the per-page TopicRoute swap, keyed by (cat, post).
                    pollManualExpanded = topicPollNavState.expansions[
                        TopicPollKey(route.cat, route.post),
                    ],
                    onPollExpansionChanged = { expanded ->
                        topicPollNavState.onExpansionChanged(route.cat, route.post, expanded)
                    },
                    onMultiQuote = { subcat, page ->
                        // #291 — quote flavour of reply with the EXTRA numreponses riding the
                        // route; the editor replays the #146 fetch per entry. The basket is
                        // cleared on launch: the selection's intent is consumed, and backing
                        // out of the editor should not re-arm a stale « Citer N ».
                        val selection = multiQuoteNavState.basket
                            ?.takeIf { it.matches(route.cat, route.post) }
                            ?.numreponses
                            .orEmpty()
                        if (selection.isNotEmpty()) {
                            backStack.add(
                                PostEditorRoute(
                                    mode = PostEditorMode.Reply,
                                    cat = route.cat,
                                    topicId = route.post,
                                    page = page,
                                    subcat = subcat,
                                    quotedNumreponse = selection.first(),
                                    quoteRef = null,
                                    extraQuoteNumreponses = selection.drop(1),
                                ),
                            )
                            multiQuoteNavState.onClear()
                        }
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
                        // #412 — a one-page step backwards lands at the bottom of the target page
                        // (reading direction) unless that page has a saved anchor. Strict
                        // « page - 1 » so pager jumps further back keep the top landing; any other
                        // page change clears a stale marker.
                        topicScrollNavState.onPendingBottomLanding(
                            TopicScrollKey(route.cat, route.post, targetPage)
                                .takeIf { targetPage == route.page - 1 },
                        )
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
                        // there (their reply lives on the last page, not the stale form page). Carry a
                        // fresh submitSignal so the destination ViewModel force-fetches the page — a
                        // plain cache-aside load could serve a TTL-fresh row that pre-dates the reply
                        // (the original #226 failure). The `postSubmitOverflowLanding` flag is what
                        // keeps the old anti-chase guarantee WITHOUT dropping the refresh: the landing
                        // ViewModel scrolls to the end but never re-emits NavigateToLastPage, so a
                        // concurrent post that pushes totalPages further during the refresh window does
                        // not start a moving-tail chase. Indexed set (not removeAt + add) for the same
                        // single-mutation reason as onOpenPage (#282).
                        backStack[backStack.lastIndex] = TopicRoute(
                            cat = route.cat,
                            post = route.post,
                            page = lastPage,
                            scrollTo = null,
                            submitSignal = System.currentTimeMillis(),
                            postSubmitOverflowLanding = true,
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
                        extraQuoteNumreponses = route.extraQuoteNumreponses,
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
                                    // #226 — a fresh submit may itself overflow, so it must be allowed
                                    // to redirect once. Reset the landing flag the previous route may
                                    // have carried (topicEntry could already be an overflow landing).
                                    postSubmitOverflowLanding = false,
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
                                    // #226 — a fresh submit may itself overflow, so it must be allowed
                                    // to redirect once. Reset the landing flag the previous route may
                                    // have carried (topicEntry could already be an overflow landing).
                                    postSubmitOverflowLanding = false,
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

// #494 — paramètres de transition (Claude + Codex). MotionScheme M3 absent en stable 1.4.x (1.5.0-alpha)
// → easings « emphasized » locaux. Slide LÉGER (1/4 de largeur) pour ne pas singer le swipe topic.
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val DRILL_MS = 320
private const val DRILL_FADE_IN_MS = 150
private const val DRILL_FADE_OUT_MS = 90
private const val TAB_FADE_IN_MS = 140
private const val TAB_FADE_OUT_MS = 80
private const val SLIDE_DIVISOR = 4

private fun navInstant(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/** Shared-axis X, sens AVANT : l'entrant glisse depuis la droite, le sortant part vers la gauche. */
private fun navSharedAxisXForward(): ContentTransform =
    (slideInHorizontally(tween(DRILL_MS, easing = EmphasizedDecelerate)) { it / SLIDE_DIVISOR } +
        fadeIn(tween(DRILL_FADE_IN_MS, delayMillis = 30))) togetherWith
        (slideOutHorizontally(tween(DRILL_MS, easing = EmphasizedAccelerate)) { -it / SLIDE_DIVISOR } +
            fadeOut(tween(DRILL_FADE_OUT_MS)))

/** Shared-axis X, sens ARRIÈRE : l'entrant glisse depuis la gauche, le sortant part vers la droite. */
private fun navSharedAxisXBack(): ContentTransform =
    (slideInHorizontally(tween(DRILL_MS, easing = EmphasizedDecelerate)) { -it / SLIDE_DIVISOR } +
        fadeIn(tween(DRILL_FADE_IN_MS, delayMillis = 30))) togetherWith
        (slideOutHorizontally(tween(DRILL_MS, easing = EmphasizedAccelerate)) { it / SLIDE_DIVISOR } +
            fadeOut(tween(DRILL_FADE_OUT_MS)))

/** Fade-through court entre onglets (contenus sans relation spatiale → pas de slide). */
private fun navTabFadeThrough(): ContentTransform =
    fadeIn(tween(TAB_FADE_IN_MS, delayMillis = 30)) togetherWith fadeOut(tween(TAB_FADE_OUT_MS))

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
 * Pure : une navigation AVANT est un drill-down (push) — par opposition à un changement d'onglet ou un
 * remplacement de pile — ssi la pile cible est exactement la pile source AVEC une entrée empilée au
 * sommet. On compare les piles ENTIÈRES (par `contentKey`), pas seulement le sommet : deux onglets
 * peuvent partager une même valeur de route (ex. `CategoryRoute(cat=23)` présent dans Drapeaux ET dans
 * Forums), et ne comparer que le dernier `contentKey` ferait passer un changement d'onglet pour un push
 * (slide au lieu de fade-through). On ne peut PAS s'appuyer sur une « racine » via `entries.first` : dans
 * le `SinglePaneScene` de nav3 `entries` ne contient que l'entrée visible (le sommet) ; la pile complète
 * se reconstruit par `previousEntries + entries`. [sourceStack] = pile source complète (bas→haut),
 * [targetParentStack] = pile cible privée de son sommet (ce vers quoi elle se dépilerait). Drill-down
 * ssi les deux coïncident et sont non vides — exact à toute profondeur.
 */
internal fun isForwardDrillDown(sourceStack: List<Any?>, targetParentStack: List<Any?>): Boolean =
    targetParentStack.isNotEmpty() && targetParentStack == sourceStack

/** True quand passer de [this] à [to] est un drill-down (cf. [isForwardDrillDown]). */
private fun Scene<NavKey>.isForwardDrillDownTo(to: Scene<NavKey>): Boolean =
    isForwardDrillDown(
        sourceStack = (previousEntries + entries).map { it.contentKey },
        targetParentStack = to.previousEntries.map { it.contentKey },
    )

/**
 * Transition AVANT (push/replace non-pop) : instantané pour le swipe topic→topic (#282), shared-axis X
 * avant pour un drill-down (push intra-onglet, toute profondeur), fade-through sinon (changement
 * d'onglet ou remplacement de pile — contenus sans relation spatiale parent/enfant).
 */
private fun navForwardTransform(from: Scene<NavKey>, to: Scene<NavKey>): ContentTransform = when {
    from.isTopicScene() && to.isTopicScene() -> navInstant()
    from.isForwardDrillDownTo(to) -> navSharedAxisXForward()
    else -> navTabFadeThrough()
}

/** Transition ARRIÈRE (pop / retour prédictif) : instantané topic→topic, sinon shared-axis X arrière. */
private fun navPopTransform(from: Scene<NavKey>, to: Scene<NavKey>): ContentTransform =
    if (from.isTopicScene() && to.isTopicScene()) navInstant() else navSharedAxisXBack()
