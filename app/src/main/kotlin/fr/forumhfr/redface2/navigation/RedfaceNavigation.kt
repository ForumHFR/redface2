package fr.forumhfr.redface2.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.Window
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.shouldRevealNavBar
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
import java.time.Instant
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
    /**
     * #618 — when true, the reply composer auto-opens its « Gérer les destinataires » bottom sheet on
     * mount (once the form has loaded and the owner-only member editor is available). Set by the
     * « Gérer les destinataires » entry of the conversation's Participants sheet; `false` on the
     * normal « Répondre » path. A plain serializable Boolean — kotlinx-serialization handles it with
     * no custom NavType.
     */
    val openRecipientManager: Boolean = false,
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
 * #494 — type de barre de navigation à passer au [NavigationSuiteScaffoldLayout]. Sur téléphone l'adaptatif
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
 * #529 — modifier appliqué au CONTENU du [NavigationSuiteScaffoldLayout]. Quand la suite est une barre du
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
 * Caching follow-up (tracked by #625). Candidate approaches: (a) shared `Singleton` Room-
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

// #603 PR6 / #679 — what a bottom-bar tap should do, given the tapped tab, the current tab and the
// Drapeaux stack depth. Pure (no callbacks → no LongParameterList, fully testable); the host maps the
// result to an action via [runTopLevelTap].
internal enum class TopLevelTapAction { ReselectFlags, PopFlagsToRoot, Switch }

// A tap on a DIFFERENT tab switches. A re-tap of the already-selected Drapeaux tab depends on its stack:
//   - at the tab ROOT ([flagsAtRoot]) → open the quick-config sheet (ReselectFlags) ;
//   - from a SUB-SCREEN (a topic opened from the list) → pop the tab back to its root (PopFlagsToRoot),
//     NOT arm the sheet. #679: arming from a sub-screen made the sheet pop open on return to the list.
// A re-tap of any other already-selected tab is a plain Switch (no special-case).
internal fun topLevelTapAction(
    tapped: TopLevelDestination,
    current: TopLevelDestination,
    flagsAtRoot: Boolean,
): TopLevelTapAction {
    val reselectFlags = current == tapped && tapped == TopLevelDestination.Flags
    return when {
        reselectFlags && flagsAtRoot -> TopLevelTapAction.ReselectFlags
        reselectFlags -> TopLevelTapAction.PopFlagsToRoot
        else -> TopLevelTapAction.Switch
    }
}

// #679 — dispatch a [topLevelTapAction] to the host's side effects. Kept out of RedfaceApp's body so the
// `when` does not count against its cyclomatic-complexity budget (at detekt's ceiling).
private fun runTopLevelTap(
    action: TopLevelTapAction,
    onReselectFlags: () -> Unit,
    onPopFlagsToRoot: () -> Unit,
    onSwitch: () -> Unit,
) = when (action) {
    TopLevelTapAction.ReselectFlags -> onReselectFlags()
    TopLevelTapAction.PopFlagsToRoot -> onPopFlagsToRoot()
    TopLevelTapAction.Switch -> onSwitch()
}

/**
 * #666 — the bottom-nav item label, or null (icon-only) when the user turned labels off. A plain
 * helper (not in [RedfaceApp]) so the toggle adds no branch to the host's cyclomatic-complexity budget.
 */
private fun navItemLabel(
    show: Boolean,
    content: @Composable () -> Unit,
): (@Composable () -> Unit)? = content.takeIf { show }

/**
 * #666 follow-up — height of the icon-only compact bottom bar. The adaptive
 * [NavigationSuiteType.ShortNavigationBarCompact] keeps reserving the label-row height (~64 dp) even with
 * labels hidden, so the bar looked needlessly tall in icon-only mode (« ça sert à rien », XaTriX).
 *
 * 52 dp (XaTriX, 2026-06-27) — a centered 24 dp icon then has 14 dp of breathing room above and below
 * (mirrors the icon-to-top gap of the labelled bar). The bar is built from a custom item ([CompactBarItem]),
 * NOT `NavigationBarItem`: the latter carries the standard 80 dp NavigationBar's internal metrics and would
 * not compact to this height (Codex review). 52 dp keeps a ≥ 48 dp touch target (Material minimum).
 */
private val CompactIconBarHeight = 52.dp

// #666 follow-up — the M3 active-indicator pill behind the selected icon (matches the adaptive bar's
// indicator). 32 dp tall fits inside the 52 dp bar; centering it leaves the icon's 14 dp top/bottom gap.
private val CompactIndicatorWidth = 56.dp
private val CompactIndicatorHeight = 32.dp

/**
 * #666 follow-up — true only for the phone bottom-bar layout ([NavigationSuiteType.ShortNavigationBarCompact])
 * with the labels hidden; that is the single case where the suite is swapped for the shorter icon-only bar.
 * Every other layout (rail / drawer on wide windows, or labels still on) keeps the adaptive suite. Pure →
 * unit-tested (`ShouldUseCompactIconBarTest`).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun shouldUseCompactIconBar(
    navBarLabels: Boolean,
    navLayoutType: NavigationSuiteType,
): Boolean = !navBarLabels && navLayoutType == NavigationSuiteType.ShortNavigationBarCompact

/**
 * #666 follow-up — the bottom navigation suite slot. When the phone bottom-bar layout has its labels turned
 * off (see [shouldUseCompactIconBar]) it renders the shorter icon-only [IconOnlyBottomBar]; every other case defers to
 * the adaptive [NavigationSuite] (bottom bar with labels on phones, rail / drawer on wider windows),
 * preserving the previous behaviour exactly. Extracted from [RedfaceApp] so the extra branch stays off that
 * composable's cyclomatic-complexity budget (it sits at detekt's ceiling).
 *
 * NB — the icon-only bar is built from a CUSTOM item ([CompactBarItem] in [IconOnlyBottomBar]), not
 * `NavigationBarItem` (which keeps the 80 dp bar's internal metrics and would not compact) nor the expressive
 * `ShortNavigationBar` (the whole `ExperimentalMaterial3ExpressiveApi` surface is `internal` in compose-bom
 * 2026.05.01 — same gotcha as `PullToRefreshDefaults.LoadingIndicator`). The labels-on branch keeps the
 * adaptive suite's short bar.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun RedfaceBottomNavigationSuite(
    navLayoutType: NavigationSuiteType,
    navBarLabels: Boolean,
    currentDestination: TopLevelDestination,
    mpUnreadCount: Int?,
    onItemClick: (TopLevelDestination) -> Unit,
) {
    val compactIconOnly = shouldUseCompactIconBar(navBarLabels, navLayoutType)
    if (compactIconOnly) {
        IconOnlyBottomBar(
            currentDestination = currentDestination,
            mpUnreadCount = mpUnreadCount,
            onItemClick = onItemClick,
        )
    } else {
        NavigationSuite(layoutType = navLayoutType) {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = currentDestination == destination,
                    onClick = { onItemClick(destination) },
                    icon = {
                        TopLevelDestinationIcon(
                            destination = destination,
                            mpUnreadCount = mpUnreadCount,
                        )
                    },
                    // #666 — null label = icon-only items when labels are off (helper keeps this off
                    // RedfaceApp's cyclomatic-complexity budget via takeIf).
                    label = navItemLabel(navBarLabels) {
                        Text(text = stringResource(destination.labelRes))
                    },
                )
            }
        }
    }
}

/**
 * #666 follow-up — the label-less compact bottom bar, [CompactIconBarHeight] (52 dp) tall. Phone + labels-off
 * only. Built from a CUSTOM item ([CompactBarItem]), not `NavigationBarItem`: the latter carries the standard
 * 80 dp NavigationBar's internal metrics and stays visually tall even inside a fixed-height [Row] (Codex
 * review), and the expressive short bar is `internal` in this bom (see [RedfaceBottomNavigationSuite]).
 * Window-inset handling mirrors the standard suite so #529 (no dark band) still holds: the [Surface] paints the
 * container colour across the whole region — including behind the system navigation bar — while the [Row] is
 * pushed above the system insets via [windowInsetsPadding] (bottom + horizontal: a landscape / multi-window
 * side bar also reports a horizontal inset). [selectableGroup] keeps the items a single a11y tab group.
 */
@Composable
private fun IconOnlyBottomBar(
    currentDestination: TopLevelDestination,
    mpUnreadCount: Int?,
    onItemClick: (TopLevelDestination) -> Unit,
) {
    Surface(
        color = NavigationBarDefaults.containerColor,
        tonalElevation = NavigationBarDefaults.Elevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .height(CompactIconBarHeight)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                CompactBarItem(
                    destination = destination,
                    selected = currentDestination == destination,
                    mpUnreadCount = mpUnreadCount,
                    onClick = { onItemClick(destination) },
                )
            }
        }
    }
}

/**
 * #666 follow-up — one item of [IconOnlyBottomBar]. Custom (not `NavigationBarItem`, which keeps the 80 dp
 * bar's internal metrics — Codex review) so the bar can be a true [CompactIconBarHeight]: the 24 dp icon is
 * centered in the 52 dp item (→ 14 dp top/bottom gap, the value XaTriX asked for), with the M3 active-indicator
 * pill behind it when selected. A11y: [TopLevelDestinationIcon] carries no contentDescription, so the item
 * itself supplies the accessible name + the tab role/selected state ([selectableGroup] groups them).
 */
@Composable
private fun RowScope.CompactBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    mpUnreadCount: Int?,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val iconColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(width = CompactIndicatorWidth, height = CompactIndicatorHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
        }
        CompositionLocalProvider(LocalContentColor provides iconColor) {
            TopLevelDestinationIcon(destination = destination, mpUnreadCount = mpUnreadCount)
        }
    }
}

/** #667 — target tab + remaining history when back is pressed at a secondary tab's root. */
internal data class TabBackResult(
    val target: TopLevelDestination,
    val history: List<TopLevelDestination>,
)

/**
 * #667 — visited-tab history (MRU stack of top-level tabs, most-recently-left LAST, excluding the
 * current tab). New history after switching the active tab from [current] to [target]: a reselect
 * (target == current) is a no-op; otherwise the left tab becomes the most-recent previous and any
 * stale occurrence of either tab is dropped so the history stays a deduplicated MRU. Pure → tested.
 */
internal fun tabHistoryOnSwitch(
    history: List<TopLevelDestination>,
    current: TopLevelDestination,
    target: TopLevelDestination,
): List<TopLevelDestination> =
    if (target == current) {
        history
    } else {
        history.filterNot { it == current || it == target } + current
    }

/**
 * #667 — target + remaining history when back is pressed at a secondary tab's root: pop the
 * most-recent visited tab (the popped tab is NOT re-pushed — back is a pop, not a forward nav, which
 * is what avoids the ping-pong Codex flagged), or [fallback] (Flags) when the history is empty.
 */
internal fun tabBackTarget(
    history: List<TopLevelDestination>,
    fallback: TopLevelDestination,
): TabBackResult =
    if (history.isEmpty()) {
        TabBackResult(fallback, emptyList())
    } else {
        TabBackResult(history.last(), history.dropLast(1))
    }

/**
 * #667 — back at a secondary tab's root returns to the previous tab instead of letting the system
 * finish the Activity. Extracted from [RedfaceApp] to keep it under detekt's complexity threshold.
 * Enabled only at the ROOT (size == 1) of a tab other than the home Flags tab.
 */
@Composable
private fun TabRootBackHandler(
    currentDestination: TopLevelDestination,
    activeBackStackSize: Int,
    onRootBack: () -> Unit,
) {
    BackHandler(
        enabled = currentDestination != TopLevelDestination.Flags && activeBackStackSize == 1,
    ) { onRootBack() }
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
    // TU 2788511 — accent colour family (rose ↔ vivid « REDFACE1 » red), resolved at the root for RedfaceTheme.
    val accentColor by themeViewModel.accentColor.collectAsStateWithLifecycle()
    // #287 — reading presets (density + font scale) resolved at the root and bundled for RedfaceTheme.
    val displayDensity by themeViewModel.displayDensity.collectAsStateWithLifecycle()
    val fontScale by themeViewModel.fontScale.collectAsStateWithLifecycle()
    // #332 — « fold long quotes » reading preference, provided to the post renderer via RedfaceTheme.
    val foldLongQuotes by themeViewModel.foldLongQuotes.collectAsStateWithLifecycle()
    // #105 — « afficher l'ascenseur » reading preference, provided to the reading scrollbar via RedfaceTheme.
    val showScrollbar by themeViewModel.showScrollbar.collectAsStateWithLifecycle()
    // #666 — show/hide the labels under the bottom-nav icons (resolved at the shell for the suite below).
    val navBarLabels by themeViewModel.navBarLabels.collectAsStateWithLifecycle()
    // #445 — debug bounds overlay preference (the dev-channel gate + render live in
    // [DevDebugBoundsOverlay], emitted last so it paints over everything; off by default).
    val debugBoundsOverlay by themeViewModel.debugBoundsOverlay.collectAsStateWithLifecycle()
    // #518 — immersive mode: hide the bottom Android system navigation bar (3 buttons or gesture pill,
    // device-dependent). Off by default; applied on the host window below.
    val hideSystemNavBar by themeViewModel.hideSystemNavBar.collectAsStateWithLifecycle()
    // #518 follow-up — in-app back button shown while immersive mode is active (companion to the above).
    val immersiveBackButton by themeViewModel.immersiveBackButton.collectAsStateWithLifecycle()
    // #518 follow-up — the in-app back FAB fires the SAME dispatcher the system back button drives, so a
    // press follows nav3's onBack (pop the active tab's back stack). Resolved here in composable scope;
    // null only on the @Preview path (no host Activity), where the FAB also never renders.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // #518 follow-up — scroll-driven reveal of the hidden system nav bar. The MODE is the user preference;
    // the raw scroll FACTS are reported up by the active topic screen. RedfaceApp stays the single owner
    // of the window bar (no dual ownership): it combines mode + facts via the pure shouldRevealNavBar and
    // drives the window below. topicNavBarScroll resets when the active route is no longer a topic.
    val immersiveNavBarReveal by themeViewModel.immersiveNavBarReveal.collectAsStateWithLifecycle()
    var topicNavBarScroll by remember { mutableStateOf(NavBarScrollFacts()) }
    // Effective hide + scroll-report gate are pure helpers so RedfaceApp stays under detekt's complexity.
    val hideNavBarNow = immersiveNavBarHidden(hideSystemNavBar, immersiveNavBarReveal, topicNavBarScroll)
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
        // #518 — apply immersive mode whenever the EFFECTIVE hide state changes (the master toggle, or a
        // scroll-driven reveal request flipping, #518 follow-up), and re-assert it on ON_RESUME (returning
        // from another app / the recents screen restores the bar without a recomposition). The
        // transient-bars-by-swipe behaviour handles user swipes; hiding sets the bottom inset to 0 so
        // navigationBarsPadding() collapses cleanly, while a transient swipe-reveal does NOT change insets
        // (no layout jump). Status bar and the in-app tab bar are untouched.
        LaunchedEffect(hideNavBarNow) {
            applyImmersiveNavBar(window, view, hideNavBarNow)
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            applyImmersiveNavBar(window, view, hideNavBarNow)
        }
    }
    RedfaceTheme(
        darkTheme = darkTheme,
        amoledTheme = amoledEnabled,
        accentColor = accentColor,
        reading = ReadingDisplaySettings(
            density = displayDensity,
            fontScale = fontScale,
            foldLongQuotes = foldLongQuotes,
            showScrollbar = showScrollbar,
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

        // #667 — visited-tab history (MRU) so back at a secondary tab's root returns to the previously
        // visited tab instead of falling through to the system (which closed the app). Saveable across
        // rotation/process death; stored as enum names.
        var tabHistory by rememberSaveable(
            stateSaver = listSaver(
                save = { it.map(TopLevelDestination::name) },
                // Defensive restore (Codex review): drop names that no longer resolve so a renamed/removed
                // tab in a future version cannot crash the process-death restore.
                restore = { names -> names.mapNotNull { runCatching { TopLevelDestination.valueOf(it) }.getOrNull() } },
            ),
        ) { mutableStateOf(emptyList<TopLevelDestination>()) }

        // #603 PR6 — re-tap of the already-selected Drapeaux tab raises this counter; threaded to
        // FlagsRoute, which opens its quick-config sheet on each increment. Deliberately a plain
        // `remember` (NOT rememberSaveable): a saved non-zero counter would re-open the sheet after a
        // config change without a new tap (Codex review). Resetting to 0 on recreation is the safe
        // event semantics — a tap mid-rotation is a negligible loss.
        var flagsQuickConfigRequest by remember { mutableStateOf(0) }

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

        // #667 — single entry point for every tab change (tap, deep link, root-back) so the visited-tab
        // history stays consistent. A reselect of the current tab leaves the history untouched.
        val switchTab: (TopLevelDestination) -> Unit = { target ->
            tabHistory = tabHistoryOnSwitch(tabHistory, currentDestination, target)
            currentDestination = target
        }
        // #667 — back at a secondary tab's root: pop the most-recent visited tab (fallback Flags). A pop,
        // NOT a forward switch (no re-push), so successive backs walk the history out without oscillating.
        val onRootTabBack: () -> Unit = {
            val result = tabBackTarget(tabHistory, TopLevelDestination.Flags)
            tabHistory = result.history
            currentDestination = result.target
        }

        LaunchedEffect(intent) {
            val parsed = intent?.data?.let(::parseHfrDeepLink) ?: return@LaunchedEffect
            switchTab(parsed.destination)
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
        // #531 — optimistic « read » marks of the inbox, now keyed by the conversation DATE seen at
        // open-time (was a bare Set<Int>). Storing the date lets a fresh page-1 network result RECONCILE
        // the mark: HFR re-flagging a thread unread is only honoured when its server date is STRICTLY
        // newer than the date recorded here (a true new MP), never on an identical date (an echo of the
        // pre-read dot — see reconcileReadMarks). Same lifecycle as before: in-memory only, purged on
        // every auth transition; membership (`threadId in map`) drives the list read-override.
        var readPrivateMessageThreadIds by remember { mutableStateOf(emptyMap<Int, Instant>()) }
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
        // #531 — conversation DATE captured at the moment a thread is opened from the inbox, used as
        // the read-mark value once the thread loads (onThreadLoaded). The route is opaque (threadId +
        // page only) and the date is private metadata, so it is held here in memory — purged on every
        // auth transition like the hints above — rather than carried in PrivateMessageThreadRoute.
        var openThreadDates by remember { mutableStateOf(emptyMap<Int, Instant>()) }
        // #531 (Codex BLOCKER 1) — highest inbox load generation already reconciled, held OUTSIDE the
        // MessagesScreen composition so the once-per-fetch guarantee survives the screen re-entering
        // composition (thread → back re-runs the reconcile effect on a stale page-1 Content for the
        // SAME generation). The handler ignores `generation <= lastReconciledGeneration`. Reset to 0
        // on every auth transition, in lockstep with the read-mark purge and with the ViewModel's own
        // generation reset (clearPrivateState rebuilds the state, so the generation restarts at 0 on
        // Anonymous) — keeping a positive value here would then silently swallow the next session's
        // first reconcile.
        var lastReconciledGeneration by remember { mutableStateOf(0) }
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
                    readPrivateMessageThreadIds = emptyMap()
                    multiRecipientThreadIds = emptySet()
                    unreadOnOpenThreadIds = emptySet()
                    openThreadDates = emptyMap()
                    // #531 — stay in lockstep with the VM's generation reset (clearPrivateState).
                    lastReconciledGeneration = 0
                    privateMessageSentSignal = null
                    // #291 — a write intention armed under another session must not survive the
                    // transition (Codex review: stale « Citer N » after logout/login).
                    multiQuoteBasket = null
                    resetStack(messagesBackStack, MessagesRoute, MessagesRoute)
                }
                is AuthState.Authenticated -> {
                    readPrivateMessageThreadIds = emptyMap()
                    multiRecipientThreadIds = emptySet()
                    unreadOnOpenThreadIds = emptySet()
                    openThreadDates = emptyMap()
                    // #531 — the VM reloads page 1 (generation back to 1) on a fresh authentication;
                    // resetting here lets that first reconcile run.
                    lastReconciledGeneration = 0
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
        // #518 follow-up — only a topic screen reports scroll facts; clear them whenever the active top
        // route is something else (other tab, editor, profile…) so a stale « at bottom » never keeps the
        // nav bar revealed off-topic. Returning to a topic re-emits its current facts on first frame. The
        // branch lives in the helper composable to keep RedfaceApp under detekt's complexity threshold.
        ResetNavBarScrollOffTopic(topRoute) { topicNavBarScroll = NavBarScrollFacts() }
        val adaptiveType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        val navLayoutType = resolveNavLayoutType(topRoute.hidesNavigationSuite(), adaptiveType)
        // #529 — consume the bottom nav-bar inset for the content only under a bottom-bar layout
        // (see navSuiteContentInsetModifier). Read in composable scope, branch lives in the helper.
        val contentInsetModifier = navSuiteContentInsetModifier(navLayoutType, WindowInsets.navigationBars)

        // #603 PR6 / #679 — bottom-bar tap routing (see [topLevelTapAction]). Hoisted to a val so the per-item
        // onClick in RedfaceBottomNavigationSuite carries no extra branch and RedfaceApp stays under detekt's
        // ceiling. `flagsAtRoot` is read at tap time (current stack depth), so a re-tap from a sub-screen
        // pops the tab to its root instead of arming the quick-config sheet (#679).
        val onNavItemClick: (TopLevelDestination) -> Unit = { destination ->
            runTopLevelTap(
                action = topLevelTapAction(
                    tapped = destination,
                    current = currentDestination,
                    flagsAtRoot = flagsBackStack.size <= 1,
                ),
                onReselectFlags = { flagsQuickConfigRequest++ },
                onPopFlagsToRoot = { popToRoot(flagsBackStack) },
                onSwitch = { switchTab(destination) },
            )
        }
        // #666 follow-up — NavigationSuiteScaffoldLayout (not the higher-level NavigationSuiteScaffold) so
        // the navigationSuite slot can swap a shorter icon-only bar in when labels are off (Option A). The
        // content slot below is unchanged (#529 content-inset handling stays put).
        NavigationSuiteScaffoldLayout(
            navigationSuite = {
                RedfaceBottomNavigationSuite(
                    navLayoutType = navLayoutType,
                    navBarLabels = navBarLabels,
                    currentDestination = currentDestination,
                    mpUnreadCount = mpUnreadCount,
                    onItemClick = onNavItemClick,
                )
            },
            layoutType = navLayoutType,
        ) {
            // #398 — no global side gutter here. Each screen owns its own lateral rhythm
            // (listings keep their 16/24 dp content padding, readers compensate explicitly),
            // so the nav host no longer steals 8 dp/side from every screen. The Surface is kept
            // for the theme background/elevation; only its horizontal padding was removed.
            // #529 — consumes the bottom nav-bar inset under a bottom-bar layout (no-op otherwise).
            val activeBackStack = backStacks.getValue(currentDestination)
            // #667 — at a secondary tab's ROOT, NavDisplay's own back handler is disabled (size == 1)
            // and the back would finish the Activity (bug). Intercept here to return to the previous
            // tab instead; Flags (home) keeps the default exit. Composed ABOVE NavDisplay, so NavDisplay
            // still wins while it can pop (size > 1); the immersive back FAB (#518) routes through the
            // same dispatcher, so it is covered. RedfaceNavHost.onRootBack is a belt-and-suspenders for a
            // future nav3 that might invoke onBack at the root.
            TabRootBackHandler(
                currentDestination = currentDestination,
                activeBackStackSize = activeBackStack.size,
                onRootBack = onRootTabBack,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(modifier = contentInsetModifier) {
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
                        // #667 — invoked if NavDisplay ever calls onBack at the root (defensive; the
                        // parent BackHandler above handles it in the current nav3 where it does not).
                        onRootBack = onRootTabBack,
                        accountMenu = accountMenu,
                        flagsQuickConfigRequest = flagsQuickConfigRequest,
                        // #603 bug fix — reset the counter once FlagsRoute handled it, so a re-mount
                        // (return from a category/topic) does not replay the sheet open (Codex review).
                        onFlagsQuickConfigConsumed = { flagsQuickConfigRequest = 0 },
                        onReportContent = {
                            startReportEmail(context, reportEmailSubject, reportNoEmailClient)
                        },
                        privateMessageNavState = PrivateMessageNavState(
                            // #531 — the screen only needs membership, so it gets the marked thread ids;
                            // the dates stay nav-internal, consumed by reconcileReadMarks below.
                            readThreadIds = readPrivateMessageThreadIds.keys,
                            multiRecipientThreadIds = multiRecipientThreadIds,
                            onThreadLoaded = { threadId ->
                                // #453 (Codex review) — decrement the badge ONLY when the conversation was
                                // unread when opened AND this is its first read of the session (predicate
                                // extracted to keep this composable under detekt's complexity threshold).
                                val decrement = shouldDecrementUnreadBadge(
                                    threadId = threadId,
                                    unreadOnOpen = unreadOnOpenThreadIds,
                                    alreadyRead = readPrivateMessageThreadIds.keys,
                                )
                                if (decrement) {
                                    mpBadgeViewModel.onThreadRead(threadId)
                                }
                                // (C1, 4-flavor MAJOR) the per-open unread flag is a PER-OPEN pending
                                // too: CONSUME it here once the decrement decision is made. Without
                                // this, a later re-fire / re-open keeps `threadId in unreadOnOpen`,
                                // and after #531 drops the read mark the thread re-decrements the
                                // badge from a stale unread state. A genuinely-unread re-open rearms
                                // it via onThreadOpenedUnread.
                                unreadOnOpenThreadIds = unreadOnOpenThreadIds - threadId
                                // #531 — record the mark keyed by the conversation date captured at
                                // open-time (openThreadDates). reconcileReadMarks later compares the
                                // server date against it. (Codex MAJOR 3) the open-time date is a
                                // PER-OPEN pending: CONSUME it here so a later re-open of the same
                                // thread WITHOUT a fresh inbox date can't reuse this stale baseline —
                                // (W1) but withReadMark now preserves an already-stored real baseline
                                // before falling back to the MAX sentinel.
                                readPrivateMessageThreadIds = readPrivateMessageThreadIds
                                    .withReadMark(threadId, openThreadDates)
                                openThreadDates = openThreadDates - threadId
                            },
                            // #531 — fresh page-1 network result: drop the marks HFR now reports as
                            // genuinely unread again (server date strictly newer than the open-time date).
                            // (Codex BLOCKER 1) dedupe by generation OUTSIDE the screen composition: the
                            // reconcile effect can refire for the same generation on a stale page-1
                            // Content, so ignore an already-reconciled generation; otherwise record it
                            // BEFORE reconciling.
                            onReconcileReadMarks = { generation, conversations ->
                                val pass = reconcilePass(
                                    marks = readPrivateMessageThreadIds,
                                    lastReconciled = lastReconciledGeneration,
                                    generation = generation,
                                    freshConversations = conversations,
                                )
                                lastReconciledGeneration = pass.lastReconciled
                                readPrivateMessageThreadIds = pass.marks
                            },
                            onThreadOpenedAsMulti = { threadId ->
                                multiRecipientThreadIds = multiRecipientThreadIds + threadId
                            },
                            onThreadOpenedUnread = { threadId ->
                                unreadOnOpenThreadIds = unreadOnOpenThreadIds + threadId
                            },
                            onThreadOpenedAt = { threadId, date ->
                                openThreadDates = openThreadDates + (threadId to date)
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
                        immersiveNavBarNavState = ImmersiveNavBarNavState(
                            // #518 follow-up — observe scroll only when immersive is on AND a scroll-driven
                            // mode is selected (helper keeps the && out of RedfaceApp's complexity budget).
                            active = immersiveScrollReportActive(hideSystemNavBar, immersiveNavBarReveal),
                            onScrollFacts = { atBottom, scrollingUp ->
                                topicNavBarScroll = NavBarScrollFacts(atBottom, scrollingUp)
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
                // #518 follow-up — in-app back affordance for immersive mode: a discreet FAB that
                // fires the SAME back action as the system button (OnBackPressedDispatcher), so the
                // hidden Android nav bar never has to be swiped back in. Visibility predicate is a pure
                // helper (keeps RedfaceApp under detekt's complexity threshold); the composable no-ops
                // when not visible so the `.align`/`.padding` BoxScope modifier stays at this call site.
                ImmersiveBackButton(
                    visible = shouldShowImmersiveBackButton(
                        hideSystemNavBar = hideSystemNavBar,
                        immersiveBackButton = immersiveBackButton,
                        backStackSize = activeBackStack.size,
                        topRoute = topRoute,
                    ),
                    onBack = { backDispatcher?.onBackPressed() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
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
 * #518 follow-up — whether to show the in-app immersive « back » FAB. Pure predicate, extracted so
 * [RedfaceApp] stays under detekt's cyclomatic-complexity threshold. Shown only when:
 * - immersive mode hides the system navigation bar ([hideSystemNavBar]), AND
 * - the companion option is on ([immersiveBackButton]), AND
 * - there is a back entry in the active tab ([backStackSize] > 1) — never an app-exit at a tab root, AND
 * - the current route is not a full-screen editor ([topRoute] does not hide the navigation suite): those
 *   own the bottom region for their IME-pinned submit bar, so a bottom-start FAB would overlap it. A
 *   3-button user there can still swipe-reveal the bar; gesture users keep the edge back gesture.
 */
private fun shouldShowImmersiveBackButton(
    hideSystemNavBar: Boolean,
    immersiveBackButton: Boolean,
    backStackSize: Int,
    topRoute: NavKey?,
): Boolean = hideSystemNavBar &&
    immersiveBackButton &&
    backStackSize > 1 &&
    !topRoute.hidesNavigationSuite()

/**
 * #518 follow-up — discreet in-app « back » affordance for immersive mode. A [SmallFloatingActionButton]
 * pinned bottom-start that fires [onBack] — wired in [RedfaceApp] to the host's `OnBackPressedDispatcher`,
 * i.e. the EXACT action of the system back button (nav3 pops the active tab's back stack). It lets a user
 * navigate back without swiping the hidden Android navigation bar in. Low-key secondary-container colours
 * so it does not fight the content. No-ops when [visible] is false (the visibility rule lives in
 * [shouldShowImmersiveBackButton]); the early return keeps the BoxScope `.align`/`.padding` modifier at
 * the single call site rather than duplicated here.
 */
@Composable
private fun ImmersiveBackButton(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val description = stringResource(R.string.immersive_back_description)
    SmallFloatingActionButton(
        onClick = onBack,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(CoreUiR.drawable.ic_arrow_back),
            contentDescription = null,
        )
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
 * @property onThreadOpenedAt #531 — records the conversation date seen when a thread is opened from
 *   the inbox; that date becomes the read mark's value so a later, strictly-newer server date can
 *   reconcile (re-unread) it (see [reconcileReadMarks]).
 * @property onReconcileReadMarks #531 — invoked on each fresh page-1 network result with the load
 *   GENERATION and the server conversations; drops the optimistic read marks HFR now reports as
 *   genuinely unread again. Deduped by generation in the host (Codex BLOCKER 1) so a re-fired effect
 *   on the same generation reconciles at most once.
 */
private data class PrivateMessageNavState(
    val readThreadIds: Set<Int>,
    val multiRecipientThreadIds: Set<Int>,
    val onThreadLoaded: (Int) -> Unit,
    val onThreadOpenedAsMulti: (Int) -> Unit,
    val onThreadOpenedUnread: (Int) -> Unit = {},
    val onThreadOpenedAt: (threadId: Int, date: Instant) -> Unit = { _, _ -> },
    val onReconcileReadMarks: (generation: Int, conversations: List<PrivateMessageSummary>) -> Unit =
        { _, _ -> },
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
 * complexity budget. `internal` (like the other nav-state helpers) so the [ReadMarkReconcileTest]
 * can model the C1 consume-on-load sequence without a Compose host.
 */
internal fun shouldDecrementUnreadBadge(
    threadId: Int,
    unreadOnOpen: Set<Int>,
    alreadyRead: Set<Int>,
): Boolean = threadId in unreadOnOpen && threadId !in alreadyRead

/**
 * #531 — reconciles the optimistic inbox read marks ([marks]: threadId → date seen at open-time)
 * against a FRESH page-1 network result ([freshConversations]). Returns the set of marked thread ids
 * to DROP: a conversation that the server still reports unread, that we had marked read, AND whose
 * server date is STRICTLY after the recorded open-time date — i.e. a genuine new MP arrived after the
 * user last read it, so the server's "unread" must win over our optimistic mark.
 *
 * Gating rationale (Codex): the comparison is `isAfter`, never `>=`. An identical date is the echo of
 * the pre-read dot (the server response that motivated the read, observed again on refresh) — dropping
 * the mark there would make a just-read thread re-blink unread (a regression). A conversation absent
 * from [freshConversations] is never inferred unread (no page-1 entry, no decision). A conversation the
 * server reports read (`!hasUnread`) leaves its mark untouched (it would be redundant anyway).
 *
 * Pure and Compose-free so it is unit-testable; the caller only runs it on a genuine network success
 * (not a recomposition / cached page), driven by [MessagesUiState.networkLoadGeneration].
 */
internal fun reconcileReadMarks(
    marks: Map<Int, Instant>,
    freshConversations: List<PrivateMessageSummary>,
): Set<Int> = freshConversations.asSequence()
    .filter { conversation ->
        conversation.hasUnread &&
            marks[conversation.threadId]?.let { conversation.date.isAfter(it) } == true
    }
    .map { it.threadId }
    .toSet()

/**
 * #531 — applies [reconcileReadMarks] to drop the now-genuinely-unread marks from this read-mark map.
 * Returns the SAME instance when nothing is reconciled, so a routine page-1 refresh (the common case:
 * no new MP) neither allocates a new map nor triggers a [RedfaceApp] recomposition. Folds the empty-set
 * guard out of the composable to keep it under detekt's cyclomatic-complexity budget.
 */
internal fun Map<Int, Instant>.withoutReconciled(
    freshConversations: List<PrivateMessageSummary>,
): Map<Int, Instant> {
    val stale = reconcileReadMarks(this, freshConversations)
    return if (stale.isEmpty()) this else this - stale
}

/**
 * #531 (Codex BLOCKER 1) — result of a generation-deduped reconcile pass. Both fields are returned so
 * the caller updates its two pieces of state atomically (the high-water generation and the read-mark
 * map). When [generation] is not newer than [lastReconciled], [marks] is the input map unchanged and
 * [lastReconciled] is unchanged too — the pass was a no-op.
 */
internal data class ReconcilePass(
    val marks: Map<Int, Instant>,
    val lastReconciled: Int,
)

/**
 * #531 (Codex BLOCKER 1) — once-per-generation reconciliation, hoisted OUT of the MessagesScreen
 * composition. The reconcile effect can re-fire for the SAME [generation] when the screen re-enters
 * composition (thread → back lands on a stale page-1 [MessagesUiState.Mode.Content]); keying the
 * effect alone does not guard that. So the host keeps a high-water mark ([lastReconciled]) and this
 * pure function:
 *  - ignores `generation <= lastReconciled` (returns the inputs untouched → idempotent re-fire), else
 *  - advances the high-water mark to [generation] AND applies [withoutReconciled].
 *
 * Compose-free so the dedupe + idempotence are unit-testable without a UI host.
 */
internal fun reconcilePass(
    marks: Map<Int, Instant>,
    lastReconciled: Int,
    generation: Int,
    freshConversations: List<PrivateMessageSummary>,
): ReconcilePass = if (generation <= lastReconciled) {
    ReconcilePass(marks, lastReconciled)
} else {
    ReconcilePass(marks.withoutReconciled(freshConversations), generation)
}

/**
 * #531 (Codex BLOCKER 2) — baseline used for a read mark when NO real open-time date was captured (the
 * DT / deep-link open path never records one). It is [Instant.MAX] on purpose: `serverDate.isAfter(MAX)`
 * is ALWAYS false, so such a mark is NEVER dropped by [reconcileReadMarks] — it only ever clears on the
 * auth purge. This is the conservative choice: a just-read thread with no baseline must never be
 * re-flagged unread on a mere echo of its dot. (The original [Instant.EPOCH] was the inverse: every
 * server date exceeded it, so any dot echo re-unread the thread.)
 */
private val NO_RECONCILE_BASELINE: Instant = Instant.MAX

/**
 * #531 — adds an optimistic read mark for [threadId], keyed by the conversation date captured at
 * open-time ([openDates]). A thread with no captured date falls back to [NO_RECONCILE_BASELINE]
 * ([Instant.MAX]), so it is never reconciled back to unread (see that constant). Extracted to keep
 * [RedfaceApp]'s Elvis out of its cyclomatic-complexity budget.
 *
 * (W1, 4-flavor MAJOR) — `onThreadLoaded` re-fires for EVERY Content emission (page change, PTR),
 * not once per visit, and the per-open [openDates] entry is consumed after the first fire. A later
 * re-fire in the SAME visit must NOT clobber a real baseline already stored for this thread: prefer
 * an existing real mark ([this] [threadId]) before falling back to the MAX sentinel. Order:
 * fresh open-time date → already-stored mark → sentinel.
 */
internal fun Map<Int, Instant>.withReadMark(
    threadId: Int,
    openDates: Map<Int, Instant>,
): Map<Int, Instant> =
    this + (threadId to (openDates[threadId] ?: this[threadId] ?: NO_RECONCILE_BASELINE))

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
 * #518 follow-up — raw scroll facts of the active topic, reported UP so RedfaceApp (the single owner of
 * the window navigation bar) can decide whether to reveal the hidden bar (cf. [shouldRevealNavBar]).
 * Plain booleans, structural equality so re-reporting identical facts is a recomposition no-op.
 */
private data class NavBarScrollFacts(
    val atBottom: Boolean = false,
    val scrollingUp: Boolean = false,
)

/**
 * #518 follow-up — immersive nav-bar reveal plumbing threaded into [RedfaceNavHost], same hoisted-bundle
 * shape as [TopicScrollNavState]. [active] (immersive on AND mode != MANUAL) gates the topic screen's
 * scroll observer so it is a no-op otherwise; [onScrollFacts] bubbles the facts up to the `var` in
 * [RedfaceApp].
 */
private data class ImmersiveNavBarNavState(
    val active: Boolean,
    val onScrollFacts: (atBottom: Boolean, scrollingUp: Boolean) -> Unit,
)

/**
 * #518 follow-up — effective « hide the system nav bar now » state: immersive on AND no active
 * scroll-driven reveal ([shouldRevealNavBar]). Extracted so the `&&` stays out of RedfaceApp's
 * cyclomatic-complexity budget.
 */
private fun immersiveNavBarHidden(
    hideSystemNavBar: Boolean,
    mode: ImmersiveNavBarReveal,
    scroll: NavBarScrollFacts,
): Boolean = hideSystemNavBar && !shouldRevealNavBar(mode, scroll.atBottom, scroll.scrollingUp)

/**
 * #518 follow-up — whether the topic screen should report its scroll facts: immersive on AND a
 * scroll-driven reveal mode selected (MANUAL / immersive-off makes the reporter a no-op). Extracted
 * to keep the `&&` out of RedfaceApp's complexity budget.
 */
private fun immersiveScrollReportActive(
    hideSystemNavBar: Boolean,
    mode: ImmersiveNavBarReveal,
): Boolean = hideSystemNavBar && mode != ImmersiveNavBarReveal.MANUAL

/**
 * #518 follow-up — clears the reported scroll facts whenever the active top route is NOT a topic, so a
 * stale « at bottom » can never keep the nav bar revealed off-topic. The `topRoute is TopicRoute` guard
 * (and thus its branch) lives here rather than in RedfaceApp's body, keeping the latter under detekt's
 * cyclomatic-complexity threshold.
 */
@Composable
private fun ResetNavBarScrollOffTopic(topRoute: NavKey?, onReset: () -> Unit) {
    LaunchedEffect(topRoute) {
        if (topRoute !is TopicRoute) onReset()
    }
}

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
    // #667 — called when back is pressed at the active tab's root (size == 1). Defensive: in the current
    // nav3 NavDisplay disables its own back handler at the root, so RedfaceApp's parent BackHandler
    // fires instead; this keeps the behaviour correct if a future nav3 invokes onBack at the root.
    onRootBack: () -> Unit,
    accountMenu: @Composable () -> Unit,
    // #603 PR6 — increments on each Drapeaux-tab re-tap; FlagsRoute opens its quick-config sheet on change.
    flagsQuickConfigRequest: Int,
    // #603 bug fix — FlagsRoute calls this once it has handled a request, resetting the counter to 0 so a
    // re-mount under the back stack does not re-open the sheet with a stale value (Codex review).
    onFlagsQuickConfigConsumed: () -> Unit,
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
    // #518 follow-up — immersive nav-bar reveal: the topic reports scroll facts up through this bundle.
    immersiveNavBarNavState: ImmersiveNavBarNavState,
    onOpenProfile: (userId: Int, pseudo: String, avatarUrl: String?) -> Unit = { _, _, _ -> },
) {
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            } else {
                onRootBack()
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
                    // #6 — DT (MultiMP) row tap: open the existing PrivateMessageThread route inside
                    // the Flags tab. DT rows are always multi-recipient, so record the hint (the
                    // route itself stays opaque, like the Messages tab does, cf. onOpenThread).
                    onOpenMultiMp = { threadId, page, wasUnread ->
                        privateMessageNavState.onThreadOpenedAsMulti(threadId)
                        // Badge fix — record the unread-on-open state so the badge decrements on
                        // first read, exactly like the Messages tab's onOpenThread. Without this the
                        // DT path never fed `unreadOnOpenThreadIds`, so shouldDecrementUnreadBadge
                        // was always false for a DT-opened conversation and the MP badge stayed high.
                        if (wasUnread) {
                            privateMessageNavState.onThreadOpenedUnread(threadId)
                        }
                        backStack.add(PrivateMessageThreadRoute(threadId = threadId, page = page))
                    },
                    quickConfigRequest = flagsQuickConfigRequest,
                    onQuickConfigConsumed = onFlagsQuickConfigConsumed,
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
                    onOpenThread = { threadId, isMultiRecipient, openAtPage, wasUnread, openedAtDate ->
                        // Record the multi-recipient hint in memory only; the route stays opaque.
                        if (isMultiRecipient) {
                            privateMessageNavState.onThreadOpenedAsMulti(threadId)
                        }
                        // #453 (Codex review) — remember the unread-on-open state so the badge only
                        // decrements for a conversation that actually had something unread.
                        if (wasUnread) {
                            privateMessageNavState.onThreadOpenedUnread(threadId)
                        }
                        // #531 — capture the conversation date seen now, so the read mark recorded on
                        // load can later be reconciled against a strictly-newer server date.
                        privateMessageNavState.onThreadOpenedAt(threadId, openedAtDate)
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
                    onReconcileReadMarks = privateMessageNavState.onReconcileReadMarks,
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
                    // #618 — owner-only « Gérer les destinataires » entry from the Participants sheet:
                    // open the reply composer with its recipient-manager sheet auto-opened.
                    onManageRecipients = { threadId, page ->
                        backStack.add(
                            PrivateMessageReplyRoute(
                                threadId = threadId,
                                page = page,
                                openRecipientManager = true,
                            ),
                        )
                    },
                    topBarActions = accountMenu,
                )
            }
            entry<PrivateMessageReplyRoute> { route ->
                PrivateMessageReplyScreen(
                    request = PrivateMessageReplyRequest(
                        threadId = route.threadId,
                        page = route.page,
                        openRecipientManager = route.openRecipientManager,
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
                    // #518 follow-up — report scroll facts so RedfaceApp can reveal the hidden system
                    // nav bar per the chosen mode. `active` is false (no-op) unless immersive + a
                    // scroll-driven mode are on; the screen clears stale facts when inactive.
                    immersiveNavBarRevealActive = immersiveNavBarNavState.active,
                    onImmersiveNavBarScroll = immersiveNavBarNavState.onScrollFacts,
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

// #679 — pop a tab's back stack down to its root, KEEPING the root entry instance (so its state is not
// lost, unlike [resetStack] which clears and re-adds a fresh root). Removes from the top, the same idiom
// the host uses for a single back-pop ([NavBackStack.removeAt] at lastIndex).
private fun popToRoot(backStack: NavBackStack<NavKey>) {
    while (backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
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

/**
 * #518 — hide or show ONLY the bottom Android system navigation bar on [window]. Never touches
 * `Type.statusBars()` (the top bar stays) nor the in-app tab bar. When hiding, the behaviour is set to
 * [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] so a swipe from the bottom edge
 * re-reveals the bar transiently (documented Android behaviour) without changing layout insets.
 */
private fun applyImmersiveNavBar(window: Window, view: View, hide: Boolean) {
    val controller = WindowCompat.getInsetsController(window, view)
    if (hide) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    } else {
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
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
