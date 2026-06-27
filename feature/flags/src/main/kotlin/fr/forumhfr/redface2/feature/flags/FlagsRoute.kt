package fr.forumhfr.redface2.feature.flags

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.preferences.CategoryBandStyle
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.MarkerStyle
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.ui.FlagItem
import fr.forumhfr.redface2.core.ui.FlagItemDivider
import fr.forumhfr.redface2.core.ui.FlagItemLongPress
import fr.forumhfr.redface2.core.ui.FlagMetadata
import fr.forumhfr.redface2.core.ui.ForumListRow
import fr.forumhfr.redface2.core.ui.LocalFlagMarkerBorder
import fr.forumhfr.redface2.core.ui.LocalForumRowTitleMaxLines
import fr.forumhfr.redface2.core.ui.R as CoreUiR
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.formatLastReplyTimestamp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.icon.categoryIcon
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup
import fr.forumhfr.redface2.core.ui.theme.FlagPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Home tab entry point.
 *
 * Phase 2 finish UI polish (#198 / #199 then tab UX iteration):
 * - The "compte / outils alpha" footer that lived in [feature/messages] now sits in a
 *   global account menu surfaced via [topBarActions] in the header row. Each main screen
 *   accepts the same slot so the affordance is consistent across Drapeaux, Forum,
 *   Recherche and Messages.
 * - The « Cyans lus » visibility is no longer a separate FilterChip: re-tapping the already
 *   selected **Cyan** tab toggles it, and the tab label gains a discreet « · +lus » suffix
 *   while read participated topics are shown.
 * - A 4th **Super** tab (right of Favoris) is a placeholder for the future « super favoris »
 *   feature — it renders a sober M3 placeholder body, no list, no network call.
 * - Refresh is now a Material 3 `PullToRefreshBox` (swipe down) instead of a header button,
 *   matching `feature/forum`. The error state still surfaces a `Retry` affordance because
 *   it's a recovery action, not a permanent secondary control.
 * - Flag removal went trailing button → swipe-to-dismiss (#99) → **long-press** (#457): the
 *   horizontal swipe now changes the flag tab ([flagsTabSwipe]), so the row-level dismiss
 *   gesture was retired. The long-press only *opens* the existing confirmation dialog
 *   ([requestRemoveFlag]) — see [RemovableFlagItem].
 */
@OptIn(ExperimentalMaterial3Api::class)
// Intentional: the Scaffold here only anchors the SnackbarHost above system bars; the inner
// Column applies its own statusBars/navigationBars padding (see comment below). Suppression
// justified inline at the content lambda usage.
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
@Suppress("LongParameterList") // Screen route: 4 host nav callbacks + the quick-config trigger + the account slot.
fun FlagsRoute(
    onOpenFlag: (Flag) -> Unit,
    onLoginRequested: () -> Unit,
    onOpenCategory: (Int) -> Unit = {},
    // #6 — open a DT (MultiMP) conversation : the host pushes the existing PrivateMessageThread
    // route. `page` is the conversation's last inbox page (web parity, #430). `wasUnread` lets the
    // host decrement the MP unread badge on first read, mirroring the Messages tab's onOpenThread
    // (badge regression: the DT path never reported it, so the badge stayed stuck high).
    onOpenMultiMp: (threadId: Int, page: Int, wasUnread: Boolean) -> Unit = { _, _, _ -> },
    // #603 PR6 — bumped by the host on each re-tap of the already-selected Drapeaux bottom-bar icon;
    // opens the quick-config sheet (LaunchedEffect below).
    quickConfigRequest: Int = 0,
    // #603 — reset the host's quick-config counter once handled, so a re-mount (return from a category/
    // topic) does not re-open the sheet with a stale request (bug fix, Codex review).
    onQuickConfigConsumed: () -> Unit = {},
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val viewModel: FlagsViewModel = hiltViewModel()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val flagsState by viewModel.flagsState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val removeFlagState by viewModel.removeFlagState.collectAsStateWithLifecycle()
    val removeFlagEvent by viewModel.removeFlagEvent.collectAsStateWithLifecycle()
    val flagsViewSettings by viewModel.flagsViewSettings.collectAsStateWithLifecycle()
    val flagsPerTabOverride by viewModel.flagsPerTabOverride.collectAsStateWithLifecycle()
    val superFavoriteIds by viewModel.superFavoriteTopicIds.collectAsStateWithLifecycle()

    // « +lus » suffix on the Cyan tab: shown when the Cyan tab is selected and CYAN's « non-lus
    // uniquement » filter is off (read participated topics are visible). The ViewModel derives this
    // from [cyanUnreadOnly] (CYAN-specific, optimistic, eager `true`) so the suffix never flashes on
    // a cold start or a tab switch before DataStore re-resolves the selected tab (#317).
    val cyanShowsRead by viewModel.cyanShowsReadShortcut.collectAsStateWithLifecycle()

    // Opt-in « DT » tab (Settings toggle). Its MultiMP list is fetched on tab open (#6).
    val showDtTab by viewModel.showDtTab.collectAsStateWithLifecycle()
    // #662 — « états vides humoristiques » opt-in (smiley empty state).
    val funnyEmptyState by viewModel.funnyEmptyState.collectAsStateWithLifecycle()
    // #546 directive XaTriX — the screen consumes the FILTERED DT state (dtDisplayState), not the raw
    // union: DT defaults to « non-lus uniquement » and a re-tap of the DT tab toggles « +lus ».
    val dtListState by viewModel.dtDisplayState.collectAsStateWithLifecycle()
    val dtIsRefreshing by viewModel.dtIsRefreshing.collectAsStateWithLifecycle()
    // « +lus » suffix on the DT tab, mirroring [cyanShowsRead]: DT selected AND its unread filter off.
    val dtShowsRead by viewModel.dtShowsRead.collectAsStateWithLifecycle()

    // #6 — trigger the DT scan only when the DT tab is OPENED (a stable LaunchedEffect, not the raw
    // composition): fetchStorage scans the inbox and must stay off the per-category auto-refresh.
    // The ViewModel guards it to once per session, so re-selecting DT reuses the loaded list.
    // Gated on showDtTab (a stale `selectedTab == Dt` while the toggle is off must NOT scan) and
    // keyed on the auth session pseudo so an account switch — which resets the DT state to Loading
    // without changing selectedTab — re-runs the effect and re-scans for the new session (Codex
    // review). Extracted so its branch stays out of FlagsRoute's cyclomatic-complexity budget.
    val authSessionKey = (authState as? AuthState.Authenticated)?.pseudo
    DtTabOpenEffect(
        selectedTab = selectedTab,
        showDtTab = showDtTab,
        authSessionKey = authSessionKey,
        onDtTabOpened = viewModel::onDtTabOpened,
    )

    val snackbarHostState = remember { SnackbarHostState() }

    // #385 — hoisted list state + scroll-to-top when the unread filter flips (cf.
    // FilterFlipScrollResetEffect).
    // #695 — ONE LazyListState per flag tab (helper below) so a single shared state no longer carries
    // Cyan's scroll index onto Favori/Rouge when switching tabs. The current tab's state drives the
    // filter-flip reset (#385) and the landing recall-to-top (#546) below, both already scoped to the
    // active tab.
    // #660 — hoisted holder (all tabs' states) so the body's Shared Axis X AnimatedContent can resolve
    // each pane's state during a transition. The active tab's state still drives the filter-flip reset
    // (#385) and the landing recall-to-top (#546) below, both already scoped to the current tab.
    val flagTabListStates = rememberFlagTabListStates()
    val flagsListState = flagTabListStates.forType(selectedTab.flagType)
    val tabUnreadFilter by viewModel.tabUnreadFilter.collectAsStateWithLifecycle()
    FilterFlipScrollResetEffect(
        tabUnreadFilter = tabUnreadFilter,
        listState = flagsListState,
    )

    // #546 — recall the list to the top after a LANDING auto-refresh (app open / tab switch /
    // resume): the refresh prepends freshly-surfaced flags and a held scroll position would leave
    // them off-screen (« faut scroller vers le haut », tinc/Lt Ripley). Driven by a one-shot
    // ViewModel signal (consumed once handled) rather than a replayable counter, so a rotation /
    // route recreation does not replay a stale scroll. Return-from-topic refreshes never raise it.
    val recallListToTop by viewModel.recallListToTop.collectAsStateWithLifecycle()
    LaunchedEffect(recallListToTop) {
        if (recallListToTop) {
            // requestScrollToItem (not scrollToItem) pins index 0 on the next remeasure ignoring the
            // key-based position restoration — without that, when the refresh prepends freshly-
            // surfaced flags the old top row stays anchored and the new rows sit above the viewport
            // (the original #546 bug). But the request is honoured per-remeasure and is NOT durable:
            // the refreshed list can land a frame later (repository SharedFlow → combine/flatMapLatest
            // defers the final emission), and a remeasure that ran first on the old list would consume
            // the request before the prepend. So re-assert it across a few frames — the top then wins
            // whichever frame the new list lands on. Bounded so a no-change landing still disarms the
            // signal (no replay on rotation/recreation). Codex review #546.
            repeat(RECALL_TO_TOP_FRAMES) {
                flagsListState.requestScrollToItem(0)
                withFrameNanos { }
            }
            viewModel.consumeRecallListToTop()
        }
    }

    // #309 — display-settings bottom sheet. Opened from the header « Affichage » action; the trigger
    // is only offered when there is a real list to configure (authenticated AND a real FlagType tab,
    // i.e. not the Super placeholder).
    var showViewSettingsSheet by remember { mutableStateOf(false) }
    // #603 PR5 — the flag whose long-press actions sheet is open (null = closed).
    var sheetFlag by remember { mutableStateOf<Flag?>(null) }
    val canConfigureView = authState is AuthState.Authenticated && selectedTab.flagType != null

    // If the screen stops being configurable while the sheet is open (session expired, or the user
    // lands on the Super tab), clear the flag so the sheet can't silently reappear on the next
    // configurable tab/re-auth.
    LaunchedEffect(canConfigureView) {
        if (!canConfigureView) {
            showViewSettingsSheet = false
            sheetFlag = null
        }
    }

    // #603 PR6 — open the quick-config sheet on each re-tap of the Drapeaux bottom-bar icon.
    QuickConfigRequestEffect(
        request = quickConfigRequest,
        canConfigure = canConfigureView,
        onOpen = { showViewSettingsSheet = true },
        onConsumed = onQuickConfigConsumed,
    )

    DtTabFallbackEffect(
        showDtTab = showDtTab,
        selectedTab = selectedTab,
        onFallback = { viewModel.selectTab(FlagTab.Cyan) },
    )

    // #378 — auto-refresh on landing. Keyed on [selectedTab] (not Unit) so it re-fires both when
    // this screen (re)enters the composition — app open, back from a topic, return from another
    // bottom tab — AND when the user switches flag tab WITHOUT leaving the screen (#501: a tab
    // change kept FlagsRoute composed, so a Unit key never re-fired and the new tab showed stale
    // data). The ViewModel snapshots the tab at call time and gates the call (preference opt-out,
    // auth, real tab, in-flight refresh, 15 s throttle), so a rapid tab burst is absorbed by the
    // throttle and reuses the pull-to-refresh indicator as the visual cue.
    LaunchedEffect(selectedTab) {
        viewModel.maybeAutoRefresh()
    }

    // #501 — also refresh when the app returns to the foreground. A warm resume (home → reopen
    // without process death) does NOT leave/re-enter the composition, so the LaunchedEffect above
    // never re-fired on relaunch. ON_RESUME covers exactly that case; the ViewModel's throttle and
    // in-flight guard make a resume that lands right after another trigger a no-op (no double
    // fan-out — the post-suspension recheck in maybeAutoRefresh is concurrency-safe).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.maybeAutoRefresh()
    }

    // One-shot snackbar for the delflag outcome (#99). Keyed on the event instance so a
    // config change does not replay it ; consumed once shown so it never re-fires.
    val successMessage = stringResource(R.string.flags_remove_success)
    val failureMessage = stringResource(R.string.flags_remove_failure)
    LaunchedEffect(removeFlagEvent) {
        when (val event = removeFlagEvent) {
            null -> Unit
            is RemoveFlagEvent.Success -> {
                snackbarHostState.showSnackbar(String.format(successMessage, event.title))
                viewModel.consumeRemoveFlagEvent()
            }
            is RemoveFlagEvent.Failure -> {
                snackbarHostState.showSnackbar(String.format(failureMessage, event.title))
                viewModel.consumeRemoveFlagEvent()
            }
        }
    }

    // #603 PR2 — client-side search over the loaded flags + the app-bar tab picker. The query is
    // hoisted here (the app bar edits it, the body filters with it) and reset on a tab change so a
    // query never carries silently from one tab to another.
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        searchQuery = ""
        searchActive = false
        sheetFlag = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        // The screen already manages its own status/navigation bars padding inside the
        // Column ; the Scaffold is here purely to anchor the SnackbarHost above the system
        // bars, so its content padding is intentionally not applied to the Column.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                FlagsSearchAppBar(
                    state = FlagsAppBarState(
                        currentTabColor = flagTabColor(selectedTab),
                        tabs = flagAppBarTabs(
                            authState = authState,
                            showDtTab = showDtTab,
                            cyanShowsRead = cyanShowsRead,
                            dtShowsRead = dtShowsRead,
                        ),
                        searchEnabled = canConfigureView,
                        query = searchQuery,
                        searchActive = searchActive,
                        // #661 — the picker dropdown surfaces a contextual « +lus » toggle (Cyan/DT) and
                        // the display-settings sheet for discoverability.
                        currentTab = selectedTab,
                        readFilterShowsRead = flagsReadFilterShowsRead(
                            tab = selectedTab,
                            cyanShowsRead = cyanShowsRead,
                            dtShowsRead = dtShowsRead,
                        ),
                    ),
                    onSelectTab = viewModel::selectTab,
                    onQueryChange = { searchQuery = it },
                    onSearchActiveChange = { searchActive = it },
                    // #661 — open the quick-config sheet from the picker (same sheet as the bottom-bar re-tap).
                    onOpenViewSettings = { showViewSettingsSheet = true },
                    accountMenu = { topBarActions?.invoke() },
                )

                // #603 — thin, flat (non-wavy) M3 progress bar under the app bar, shown during any load
                // of the current tab: manual pull-to-refresh, auto-refresh AND the initial cold load —
                // it is now the single loading cue (the central spinner was retired). ADR-017 decision 7.
                FlagsLoadingBar(
                    // Anonymous gate: when not authenticated, flagsState stays null and we must NOT show
                    // the bar over the « Se connecter » prompt (Codex review #648) — short-circuit here
                    // so the helper stays at 5 params (detekt LongParameterList).
                    loading = authState is AuthState.Authenticated && flagsLoadingBarVisible(
                        selectedTab = selectedTab,
                        flagsState = flagsState,
                        isRefreshing = isRefreshing,
                        dtListState = dtListState,
                        dtIsRefreshing = dtIsRefreshing,
                    ),
                )

                // Render nothing while authState is null (cookie jar warming up). Same
                // anti-flicker convention as PR #91; defaulting to "Anonymous" here would
                // bring the cold-start "Se connecter" flash back.
                authState?.let { state ->
                    when (state) {
                        AuthState.Anonymous -> AnonymousBody(onLoginRequested)
                        is AuthState.Authenticated -> CompositionLocalProvider(
                            // #603 — single-line topic titles when enabled (GLOBAL pref); the leaf
                            // ForumListRow reads this local, so no threading through list composables.
                            LocalForumRowTitleMaxLines provides
                                flagTitleMaxLines(flagsViewSettings.singleLineTitle),
                            // #690 — marker outline (GLOBAL pref); the leaf FlagMarker reads this local,
                            // same no-threading pattern as the single-line titles above.
                            LocalFlagMarkerBorder provides flagsViewSettings.markerBorder,
                        ) {
                            AuthenticatedBody(
                            state = FlagsBodyState(
                                selectedTab = selectedTab,
                                flagsState = flagsState,
                                cyanShowsRead = cyanShowsRead,
                                isRefreshing = isRefreshing,
                                removeFlagState = removeFlagState,
                                showDtTab = showDtTab,
                                dtListState = dtListState,
                                dtShowsRead = dtShowsRead,
                                dtIsRefreshing = dtIsRefreshing,
                                searchQuery = searchQuery,
                                markerStyle = flagsViewSettings.markerStyle,
                                categoryBandStyle = flagsViewSettings.categoryBandStyle,
                                funnyEmptyState = funnyEmptyState,
                                hideReadActive = flagsViewSettings.hideReadCategories,
                            ),
                            actions = AuthenticatedActions(
                                onSelectTab = viewModel::selectTab,
                                // #378 follow-up — record the read BEFORE navigating: returning
                                // from this topic must bypass the auto-refresh throttle (the flag
                                // state just changed), cf. FlagsViewModel.onFlagOpened.
                                onOpenFlag = { flag ->
                                    viewModel.onFlagOpened()
                                    onOpenFlag(flag)
                                },
                                onRefresh = viewModel::refresh,
                                onLoginRequested = onLoginRequested,
                                onLongPressFlag = { sheetFlag = it },
                                onOpenCategory = onOpenCategory,
                                onOpenMultiMp = onOpenMultiMp,
                                onRefreshDt = viewModel::refreshDt,
                            ),
                            listStates = flagTabListStates,
                            )
                        }
                    }
                }
            }
        }
    }

    // #309 — display-settings bottom sheet. Reflects + edits the current tab's resolved view
    // settings (or the global pair when « par onglet » is off). Writes route through the ViewModel
    // so they land on the right scope; the sheet stays open so the user sees the list re-render live.
    if (showViewSettingsSheet && canConfigureView) {
        FlagsViewSettingsSheet(
            selectedTab = selectedTab,
            settings = flagsViewSettings,
            perTabOverride = flagsPerTabOverride,
            actions = FlagsViewSettingsActions(
                onPerTabOverrideChange = viewModel::setFlagsPerTabOverride,
                onGroupByCategoryChange = viewModel::setFlagsGroupByCategory,
                onHideReadCategoriesChange = viewModel::setFlagsHideReadCategories,
                onUnreadOnlyChange = viewModel::setFlagsUnreadOnly,
                onMarkerStyleChange = viewModel::setFlagsMarkerStyle,
                onMarkerBorderChange = viewModel::setFlagsMarkerBorder,
                onSingleLineTitleChange = viewModel::setFlagsSingleLineTitle,
                onCategoryBandStyleChange = viewModel::setFlagsCategoryBandStyle,
                onDismiss = { showViewSettingsSheet = false },
            ),
        )
    }

    // #603 PR5 — long-press actions sheet: API metadata + quick actions + local super-favorite +
    // removal (which still routes through the existing confirmation dialog). No color picker.
    FlagActionsSheetHost(
        flag = sheetFlag,
        superFavoriteIds = superFavoriteIds,
        onOpen = {
            sheetFlag = null
            viewModel.onFlagOpened()
            onOpenFlag(it)
        },
        onToggleSuperFavorite = viewModel::toggleSuperFavorite,
        onRemove = {
            sheetFlag = null
            viewModel.requestRemoveFlag(it)
        },
        onDismiss = { sheetFlag = null },
    )

    // Confirmation gate before any network call (#99). The dialog renders only while the
    // ViewModel is in the Confirming state ; confirming moves to Removing (action disabled)
    // and fires the delflag call.
    (removeFlagState as? RemoveFlagState.Confirming)?.let { confirming ->
        RemoveFlagConfirmationDialog(
            flag = confirming.flag,
            onConfirm = viewModel::confirmRemoveFlag,
            onDismiss = viewModel::cancelRemoveFlag,
        )
    }
}

/**
 * M3 confirmation dialog shown before the delflag network call (#99). Spells out the topic
 * title and the drapeau type so the user knows exactly what they are removing — the removal
 * is not undoable in-app (no optimistic re-add), so confirmation is mandatory.
 */
@Composable
private fun RemoveFlagConfirmationDialog(
    flag: Flag,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val typeLabel = stringResource(flagTypeLabel(flag.type))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flags_remove_dialog_title)) },
        text = {
            Text(stringResource(R.string.flags_remove_dialog_message, flag.title, typeLabel))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.flags_remove_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.flags_remove_dialog_cancel))
            }
        },
    )
}

// Type label for use OUTSIDE the tab row (e.g. the #99 removal confirmation dialog). RED uses
// the unabbreviated `flags_type_read_only` (« Lus uniquement »); the tab row uses the shorter
// `flags_tab_read_only` (« Lu ») which is only meant to fit the cramped tab strip.
private fun flagTypeLabel(type: FlagType): Int = when (type) {
    FlagType.CYAN -> R.string.flags_tab_my_topics
    FlagType.RED -> R.string.flags_type_read_only
    FlagType.FAVORITE -> R.string.flags_tab_favorite
}

/**
 * Display-settings bottom sheet (#309 + #317). The first three switches edit the active LAYOUT
 * scope: the per-tab override master switch, then « grouper par catégorie » and « masquer les
 * catégories sans non-lu » (the latter disabled in the flat view, mirroring Settings). A caption
 * spells out the current scope so the user knows whether a layout flip touches every tab or only
 * [selectedTab]. The last switch, « non-lus uniquement » (#317), is ALWAYS per-tab (independent of
 * the scope caption) — its description says so. Writes route through the ViewModel
 * ([onPerTabOverrideChange]/[onGroupByCategoryChange]/[onHideReadCategoriesChange]/[onUnreadOnlyChange])
 * so they land on the right key; the sheet stays open so the list re-renders live underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlagsViewSettingsSheet(
    selectedTab: FlagTab,
    settings: FlagsViewSettings,
    perTabOverride: Boolean,
    actions: FlagsViewSettingsActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // #673 — the sheet grew (override + grouper + masquer lues + non-lus + marqueur + titre
                // 1 ligne + style de bande + Fermer) and overflowed without scrolling, hiding the bottom
                // options (band-style selector) on short screens. Make the content scrollable so every
                // option stays reachable; navigationBarsPadding scrolls with it so the last row clears
                // the system bar.
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.flags_view_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val scope = if (perTabOverride) {
                    stringResource(
                        R.string.flags_view_settings_scope_per_tab,
                        stringResource(flagTabLabel(selectedTab)),
                    )
                } else {
                    stringResource(R.string.flags_view_settings_scope_global)
                }
                Text(
                    text = scope,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_per_tab_title),
                description = stringResource(R.string.flags_view_settings_per_tab_description),
                checked = perTabOverride,
                enabled = true,
                onCheckedChange = actions.onPerTabOverrideChange,
            )
            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_group_title),
                description = stringResource(R.string.flags_view_settings_group_description),
                checked = settings.groupByCategory,
                enabled = true,
                onCheckedChange = actions.onGroupByCategoryChange,
            )
            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_hide_read_title),
                description = stringResource(R.string.flags_view_settings_hide_read_description),
                checked = settings.hideReadCategories,
                // Hiding read categories is only meaningful in the grouped view (mirrors Settings).
                enabled = settings.groupByCategory,
                onCheckedChange = actions.onHideReadCategoriesChange,
            )
            // #317 — « non-lus uniquement ». Always per-tab (not governed by the override above), so
            // it's always enabled and its description says it applies to this tab only.
            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_unread_only_title),
                description = stringResource(R.string.flags_view_settings_unread_only_description),
                checked = settings.unreadOnly,
                enabled = true,
                onCheckedChange = actions.onUnreadOnlyChange,
            )

            // #603 PR6 — GLOBAL marker shape selector (segmented). Shown regardless of the per-tab
            // override (the shape is global); the write routes through the ViewModel and the list
            // re-renders live underneath.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.flags_view_settings_marker_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.flags_view_settings_marker_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RedfaceSettingsChoiceGroup(
                    options = listOf(
                        RedfaceSettingsChoice(
                            MarkerStyle.STRIPE,
                            stringResource(R.string.flags_view_settings_marker_stripe),
                        ),
                        RedfaceSettingsChoice(
                            MarkerStyle.PASTILLE,
                            stringResource(R.string.flags_view_settings_marker_pastille),
                        ),
                        RedfaceSettingsChoice(
                            MarkerStyle.DOT,
                            stringResource(R.string.flags_view_settings_marker_dot),
                        ),
                    ),
                    selected = settings.markerStyle,
                    onSelected = actions.onMarkerStyleChange,
                )
            }

            // #690 — GLOBAL « marker outline » toggle (not per-tab, like the marker shape). A thin
            // 0.5 dp dark border so the amber FAVORITE reads cleanly on a light background.
            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_marker_border_title),
                description = stringResource(R.string.flags_view_settings_marker_border_description),
                checked = settings.markerBorder,
                enabled = true,
                onCheckedChange = actions.onMarkerBorderChange,
            )

            // #603 — GLOBAL « single-line topic titles » toggle (not per-tab, like the marker shape).
            ViewSettingsSwitchRow(
                title = stringResource(R.string.flags_view_settings_single_line_title_title),
                description = stringResource(R.string.flags_view_settings_single_line_title_description),
                checked = settings.singleLineTitle,
                enabled = true,
                onCheckedChange = actions.onSingleLineTitleChange,
            )

            // #603 — GLOBAL grouped-view category band style selector (segmented). Only takes visual
            // effect in the grouped view (the band is the per-category sticky header); it's disabled
            // while « grouper par catégorie » is off so the control reflects when it actually applies.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.flags_view_settings_band_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.flags_view_settings_band_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RedfaceSettingsChoiceGroup(
                    options = listOf(
                        RedfaceSettingsChoice(
                            CategoryBandStyle.MINIMAL,
                            stringResource(R.string.flags_view_settings_band_minimal),
                        ),
                        RedfaceSettingsChoice(
                            CategoryBandStyle.SOFT,
                            stringResource(R.string.flags_view_settings_band_soft),
                        ),
                        RedfaceSettingsChoice(
                            CategoryBandStyle.ACCENT,
                            stringResource(R.string.flags_view_settings_band_accent),
                        ),
                        RedfaceSettingsChoice(
                            CategoryBandStyle.BULLET,
                            stringResource(R.string.flags_view_settings_band_bullet),
                        ),
                    ),
                    selected = settings.categoryBandStyle,
                    onSelected = actions.onCategoryBandStyleChange,
                    enabled = settings.groupByCategory,
                )
            }

            TextButton(
                // Animate the sheet out (M3 stable pattern) before removing it from composition,
                // instead of an abrupt `if`-driven teardown. Swipe/scrim dismissals already animate
                // via onDismissRequest.
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) actions.onDismiss()
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.flags_view_settings_done))
            }
        }
    }
}

/** One label + description + Material 3 [Switch] row for the display-settings sheet (#309). */
@Composable
private fun ViewSettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * Human label for a real [FlagTab], used in the display-settings sheet scope caption (#309). RED
 * uses the full « Lus uniquement » (not the cramped « Lu » tab label) since the caption has room.
 * [FlagTab.Super] has no list to configure (the trigger is hidden there), so its label is only a
 * defensive fallback.
 */
private fun flagTabLabel(tab: FlagTab): Int = when (tab) {
    FlagTab.Cyan -> R.string.flags_tab_my_topics
    FlagTab.Red -> R.string.flags_type_read_only
    FlagTab.Favorite -> R.string.flags_tab_favorite
    FlagTab.Super -> R.string.flags_tab_super
    FlagTab.Dt -> R.string.flags_tab_dt
}

// #603 PR2 — flag color of the current tab for the app-bar indicator glyph. DT (MultiMP) uses fuchsia
// (#603 polish, the 4th flag color); only the Super placeholder falls back to a neutral on-surface tint.
@Composable
private fun flagTabColor(tab: FlagTab): Color = when (tab) {
    FlagTab.Cyan -> FlagPalette.Cyan
    FlagTab.Red -> FlagPalette.Red
    FlagTab.Favorite -> FlagPalette.Favorite
    FlagTab.Dt -> FlagPalette.Dt
    FlagTab.Super -> MaterialTheme.colorScheme.onSurfaceVariant
}

// #603 PR2 — app-bar tab entries, or an empty list when anonymous (no flags ⇒ the flag glyph is a
// static indicator with no picker). Extracted so the auth branch stays out of FlagsRoute's
// cyclomatic-complexity budget.
@Composable
private fun flagAppBarTabs(
    authState: AuthState?,
    showDtTab: Boolean,
    cyanShowsRead: Boolean,
    dtShowsRead: Boolean,
): List<FlagTabEntry> = if (authState is AuthState.Authenticated) {
    flagTabEntries(showDtTab = showDtTab, cyanShowsRead = cyanShowsRead, dtShowsRead = dtShowsRead)
} else {
    emptyList()
}

// #603 PR2 — the entries of the app-bar tab picker (FlagsSearchAppBar). Labels carry the « +lus »
// suffix exactly like the retired tab row; order MUST match the swipe `tabs` list in AuthenticatedBody.
@Composable
private fun flagTabEntries(
    showDtTab: Boolean,
    cyanShowsRead: Boolean,
    dtShowsRead: Boolean,
): List<FlagTabEntry> {
    val readSuffix = stringResource(R.string.flags_tab_cyan_read_shown_suffix)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return buildList {
        add(
            FlagTabEntry(
                FlagTab.Cyan,
                stringResource(R.string.flags_tab_my_topics) + if (cyanShowsRead) readSuffix else "",
                FlagPalette.Cyan,
            ),
        )
        add(FlagTabEntry(FlagTab.Red, stringResource(R.string.flags_tab_read_only), FlagPalette.Red))
        add(FlagTabEntry(FlagTab.Favorite, stringResource(R.string.flags_tab_favorite), FlagPalette.Favorite))
        if (showDtTab) {
            add(
                FlagTabEntry(
                    FlagTab.Dt,
                    stringResource(R.string.flags_tab_dt) + if (dtShowsRead) readSuffix else "",
                    FlagPalette.Dt,
                ),
            )
        }
        add(FlagTabEntry(FlagTab.Super, stringResource(R.string.flags_tab_super), neutral))
    }
}

// #603 PR4 — thin flat M3 linear progress bar shown only while [loading] (manual or auto refresh of
// the current tab). Flat (non-wavy) indeterminate indicator; only rendered when loading — the brief
// appearance under the app bar is the intended cue (ADR-017 decision 7).
// #603 PR6 — opens the quick-config sheet when [request] increments (a Drapeaux bottom-bar re-tap).
// Keyed on the counter so each re-tap re-fires; the initial 0 is skipped (no pop on fresh
// composition) and it only opens when the screen is configurable. Extracted to keep FlagsRoute's
// cyclomatic complexity in budget.
@Composable
private fun QuickConfigRequestEffect(
    request: Int,
    canConfigure: Boolean,
    onOpen: () -> Unit,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(request) {
        // One-shot CONSUMED event: the host counter persists across FlagsRoute re-mounts (return from a
        // category/topic), so an unconsumed request > 0 would re-open the sheet on every recompose (the
        // reported bug, confirmed by Codex). Consume on ANY request > 0 — even when not configurable
        // (Super tab) — so a stale event can't fire later; only open when the screen is configurable.
        // onConsumed() resets the host counter to 0 → recompose → LaunchedEffect(0) no-ops (no loop).
        if (request > 0) {
            if (canConfigure) onOpen()
            onConsumed()
        }
    }
}

// #603 — the top loading bar is shown during a refresh OR the INITIAL load (no content yet) of the
// current tab, so the central CircularProgressIndicator could be retired: the bar is the single loading
// cue (manual + auto refresh + cold load). Plain function (no composition) — keeps FlagsRoute's
// cyclomatic complexity in budget.
// NB: the anonymous gate (`authState is Authenticated`) lives at the CALL SITE — when anonymous,
// flagsState stays null and this would otherwise keep the bar up forever over the « Se connecter »
// prompt (Codex review #648). Kept at 5 params here (detekt LongParameterList threshold = 6).
private fun flagsLoadingBarVisible(
    selectedTab: FlagTab,
    flagsState: FlagsListUiState?,
    isRefreshing: Boolean,
    dtListState: DtListUiState,
    dtIsRefreshing: Boolean,
): Boolean = when (selectedTab) {
    FlagTab.Dt -> dtIsRefreshing || dtListState is DtListUiState.Loading
    FlagTab.Super -> false
    else -> isRefreshing || flagsState == null || flagsState == FlagsListUiState.Loading
}

// #603 — extracted so the single-line-title branch doesn't count against FlagsRoute's cyclomatic budget.
private fun flagTitleMaxLines(singleLineTitle: Boolean): Int = if (singleLineTitle) 1 else 2

// #603 — height of the always-present loading-bar SLOT. Reserving it unconditionally (rather than
// rendering the bar with `if (loading)`) stops the list + sticky category bands from jumping by the
// bar's height each time a load starts/ends (XaTriX dogfood). Sized to the ~4dp M3 bar itself with no
// extra air (preset D, XaTriX): the bar sits flush at the top of the slot and the list/first band follow
// immediately, so the slot costs no visible gap when idle.
private val LOADING_BAR_SLOT_HEIGHT = 4.dp

@Composable
private fun FlagsLoadingBar(loading: Boolean) {
    // The SLOT is always laid out; only the bar inside is conditional → toggling never reflows the list.
    Box(modifier = Modifier.fillMaxWidth().height(LOADING_BAR_SLOT_HEIGHT)) {
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

// #603 PR2 — empty state of an active search with no match. Scrollable so the surrounding
// PullToRefreshBox keeps a swipe target on this listless state (#229).
@Composable
private fun NoFlagsSearchResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.flags_search_no_results, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnonymousBody(onLoginRequested: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.flags_login_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onLoginRequested,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.flags_login_cta))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.AuthenticatedBody(
    state: FlagsBodyState,
    actions: AuthenticatedActions,
    // #660 — hoisted holder: the Shared Axis X AnimatedContent below composes two panes during a
    // transition, so each resolves its own tab's LazyListState (never recreated, #695 preserved).
    listStates: FlagTabListStates,
) {
    val selectedTab = state.selectedTab
    // #603 PR2 — the text PrimaryTabRow is gone: the app-bar flag picker (FlagsSearchAppBar) now
    // both indicates the current tab and switches it, and the « +lus » re-tap survives there
    // (re-selecting Cyan/DT routes through the same onSelectTab). This list is kept ONLY to map the
    // committed horizontal-swipe index back to a FlagTab (placeholders included, so a swipe can
    // leave Super/DT too). Its order MUST match the picker's (flagTabEntries).
    val tabs = buildList {
        add(FlagTab.Cyan)
        add(FlagTab.Red)
        add(FlagTab.Favorite)
        if (state.showDtTab) add(FlagTab.Dt)
        add(FlagTab.Super)
    }
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    // #457 — horizontal swipe between flag tabs, carried by the whole body under the app bar
    // (placeholders included, so a swipe can leave Super/DT too). The committed target INDEX is
    // mapped back to a FlagTab against the same `tabs` list the row renders — read through
    // rememberUpdatedState because the gesture is keyed on Unit and never restarts, while the
    // tab list itself can change (the DT tab is a Settings toggle).
    val haptics = LocalHapticFeedback.current
    val dragOffset = remember { mutableFloatStateOf(0f) }
    val updatedTabs = rememberUpdatedState(tabs)
    val updatedSelectedIndex = rememberUpdatedState(selectedIndex)
    val updatedActions = rememberUpdatedState(actions)
    // #660 — the swipe's direction for the NEXT committed transition (null = a tab tap, which falls
    // back to the tabs' order). Reset after each tab change so a later tap never inherits a stale swipe
    // direction. Set synchronously in the gesture handler, BEFORE the ViewModel emits the new tab, so
    // the transitionSpec below reads it on the recomposition that starts the slide.
    var pendingSwipeForward by remember { mutableStateOf<Boolean?>(null) }
    val swipeHandlers = remember(haptics) {
        FlagsTabSwipeHandlers(
            haptics = haptics,
            onSelectTab = { index, forward ->
                pendingSwipeForward = forward
                updatedTabs.value.getOrNull(index)
                    ?.let { tab -> updatedActions.value.onSelectTab(tab) }
            },
        )
    }
    LaunchedEffect(selectedTab) { pendingSwipeForward = null }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Without weight(1f) the box would not claim the remaining vertical space inside
            // the parent Column, breaking the gesture areas on short lists.
            .weight(1f)
            // #660 — clip so the outgoing/incoming panes don't draw past the viewport mid-slide.
            .clipToBounds()
            .flagsTabSwipe(
                currentIndex = { updatedSelectedIndex.value },
                tabCount = { updatedTabs.value.size },
                dragOffset = dragOffset,
                handlers = swipeHandlers,
            ),
    ) {
        // #660 — Material « Shared Axis X » on tab commit (styx42's pick). targetState is the WHOLE
        // FlagsBodyState and the lambda renders from its PARAMETER (`bodyState`), never the captured
        // outer `state`: per the AnimatedContent contract (official docs: « use targetCount, not
        // count ») each pane composes with its own state value, so the OUTGOING pane keeps its tab's
        // data while it slides out — no flash of the incoming tab's data. contentKey = selectedTab so
        // only a tab change slides; a data refresh within the same tab updates in place.
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                val from = tabs.indexOf(initialState.selectedTab)
                val to = tabs.indexOf(targetState.selectedTab)
                flagsTabSlide(flagsTabSlideForward(from, to, pendingSwipeForward))
            },
            contentKey = { it.selectedTab },
            label = "flagsTabSwipe",
            modifier = Modifier.fillMaxSize(),
        ) { bodyState ->
            when (bodyState.selectedTab) {
                // Super has no backend, no fetch, no pull-to-refresh (cf. its KDoc).
                FlagTab.Super -> SuperPlaceholderBody(funny = bodyState.funnyEmptyState)
                // #6 — DT is a real list now: the user's MultiMP conversations, enriched best-effort
                // with the MPStorage reading positions.
                FlagTab.Dt -> DtListBody(
                    state = bodyState.dtListState,
                    isRefreshing = bodyState.dtIsRefreshing,
                    actions = actions,
                    funny = bodyState.funnyEmptyState,
                )
                else -> FlagListBody(
                    state = bodyState,
                    actions = actions,
                    listState = listStates.forType(bodyState.selectedTab.flagType),
                )
            }
        }
    }
}

// #603 (XaTriX dogfood) — distance under which the pull is considered back at rest. The post-refresh
// settle animates distanceFraction → 0; the amorce stays suppressed until it lands at ~rest.
private const val AMORCE_REST_EPSILON = 0.001f

/**
 * Pure visibility predicate for the pull « amorce ». Shows the indicator ONLY while the user is
 * actively pulling (`!isRefreshing && distanceFraction > 0`) AND not in the post-refresh settle
 * ([settling]). The [settling] guard fixes the « ça repop en fin de load » bug (XaTriX): when a
 * refresh ends, `isRefreshing` clears while `distanceFraction` is still animating back to 0, which
 * without the guard re-pops the indicator for a frame before it dismisses.
 */
internal fun shouldShowPullAmorce(
    isRefreshing: Boolean,
    distanceFraction: Float,
    settling: Boolean,
): Boolean = !isRefreshing && !settling && distanceFraction > 0f

/**
 * #603 (XaTriX) — « amorce seule » pull-to-refresh cue: the standard [PullToRefreshDefaults.Indicator]
 * shown ONLY while the user is actively pulling, then NOTHING once the refresh starts so the thin top
 * [FlagsLoadingBar] is the single loading cue (no double indicator). The whole wrapper is gated, not
 * just the content, per Codex (an indicator left with empty content can keep its container visible).
 * Shared by both pull-to-refresh surfaces.
 *
 * The [settling] state keeps the indicator hidden through the post-refresh return-to-rest (see
 * [shouldShowPullAmorce]). Passing `isRefreshing = false` keeps the indicator in its determinate pull
 * state — it never reaches the persistent circular spin here (the M3-expressive `LoadingIndicator` is
 * `internal` in this BOM, so the determinate default Indicator is the amorce visual).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlagsPullAmorce(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            // A refresh started: suppress the amorce until the pull settles back to rest.
            settling = true
        } else {
            // Refresh ended (or never started): re-arm only once distanceFraction has animated home,
            // so the indicator doesn't re-pop while it slides back. On first composition df is already
            // ~0 so this resolves immediately.
            snapshotFlow { state.distanceFraction }.first { it <= AMORCE_REST_EPSILON }
            settling = false
        }
    }
    if (shouldShowPullAmorce(isRefreshing, state.distanceFraction, settling)) {
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = false,
            modifier = modifier,
        )
    }
}

/**
 * The real flag-list body of a [FlagTab] with a backend (Cyan/Red/Favorite) — extracted from
 * [AuthenticatedBody] when the #457 tab-swipe wrapper landed, so the swipe surface (placeholders
 * included) and the pull-to-refresh surface stay two distinct layers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlagListBody(
    state: FlagsBodyState,
    actions: AuthenticatedActions,
    listState: LazyListState,
) {
    val selectedTab = state.selectedTab
    // Pull-to-refresh (swipe down) replaces the legacy header « Actualiser » button, matching
    // feature/forum. It wraps the whole flag body so the indicator stays anchored over the
    // existing content during the refresh round-trip (Material 3 stable, cf. Context7).
    // #603 — « amorce seule » (XaTriX): a pull cue while dragging, then NOTHING during the refresh —
    // the thin top FlagsLoadingBar is the single loading cue (manual, auto and initial loads), so no
    // double indicator. The pull gesture still fires onRefresh; isRefreshing drives the box state so a
    // pull mid-refresh doesn't double-trigger.
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        state = pullState,
        indicator = {
            FlagsPullAmorce(pullState, state.isRefreshing, Modifier.align(Alignment.TopCenter))
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val current = state.flagsState) {
            null, FlagsListUiState.Loading -> Box(
                // #603 — central spinner retired: the top FlagsLoadingBar is now the single loading cue
                // (it covers the initial load too). An empty but scrollable box keeps the surrounding
                // PullToRefreshBox a swipe target (#229).
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            )

            is FlagsListUiState.Failure -> Column(
                // #229 — scrollable so the PullToRefreshBox still captures a swipe-to-refresh
                // on this short, listless state (an un-scrollable body gives the pull gesture
                // nothing to anchor on).
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val sessionExpired = current.cause is SessionExpiredException
                Text(
                    text = stringResource(
                        // The dedicated session branch stays FIRST — the #324 classifier
                        // only refines the remaining failures (shared ServerDown/Network
                        // labels vs the generic flags_error), so the reconnect CTA below
                        // never regresses.
                        if (sessionExpired) {
                            R.string.flags_session_expired
                        } else {
                            classifyHfrError(current.cause).sharedLabelResOrNull()
                                ?: R.string.flags_error
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (sessionExpired) {
                    TextButton(onClick = actions.onLoginRequested) {
                        Text(stringResource(R.string.flags_login_cta))
                    }
                } else {
                    TextButton(onClick = actions.onRefresh) {
                        Text(stringResource(R.string.flags_retry))
                    }
                }
            }

            is FlagsListUiState.Success -> {
                // #603 PR2 — apply the client-side search filter (no-op on a blank query) before
                // rendering. An active query that matches nothing shows a scrollable empty state so
                // the pull-to-refresh still has a target (#229). `remember`-ised on (content, query)
                // so the filter does not re-run/re-allocate on every recomposition (ADR-017 review).
                val content = remember(current.content, state.searchQuery) {
                    current.content.filteredBy(state.searchQuery)
                }
                if (state.searchQuery.isNotBlank() && content.isEmpty()) {
                    NoFlagsSearchResults(query = state.searchQuery)
                } else {
                    when (content) {
                        is FlagsContent.Grouped -> CategorySectionedFlagList(
                            sections = content.sections,
                            selectedTab = selectedTab,
                            removalInFlight = state.removeFlagState is RemoveFlagState.Removing,
                            markerStyle = state.markerStyle,
                            categoryBandStyle = state.categoryBandStyle,
                            funnyEmptyState = state.funnyEmptyState,
                            hideReadActive = state.hideReadActive,
                            actions = actions,
                            listState = listState,
                        )

                        is FlagsContent.Flat -> FlatFlagList(
                            flags = content.flags,
                            selectedTab = selectedTab,
                            removalInFlight = state.removeFlagState is RemoveFlagState.Removing,
                            markerStyle = state.markerStyle,
                            funnyEmptyState = state.funnyEmptyState,
                            actions = actions,
                            listState = listState,
                        )
                    }
                }
            }
        }
    }
}

/**
 * #695 — one [LazyListState] per flag tab so the scroll position of Mes sujets / Lu / Favoris does not
 * bleed across tabs (a single shared state carried one tab's index onto another). The three states are
 * remembered + saveable, so each tab keeps its own position across switches and rotation.
 *
 * #660 — held as a HOISTED holder rather than resolving a single active state: the body's Shared Axis X
 * [AnimatedContent] composes BOTH the outgoing and incoming panes during a transition, so each pane must
 * read its own tab's state. Resolving (not recreating) per pane keeps every tab's scroll position intact
 * — recreating a [LazyListState] inside the transition would lose it and reintroduce the cross-tab bleed.
 */
@Stable
private class FlagTabListStates(
    val cyan: LazyListState,
    val red: LazyListState,
    val favorite: LazyListState,
) {
    // Super/DT have no real flag list — they reuse the Cyan state harmlessly (never rendered as a list).
    fun forType(flagType: FlagType?): LazyListState = when (flagType) {
        FlagType.RED -> red
        FlagType.FAVORITE -> favorite
        FlagType.CYAN, null -> cyan
    }
}

@Composable
private fun rememberFlagTabListStates(): FlagTabListStates {
    val cyan = rememberLazyListState()
    val red = rememberLazyListState()
    val favorite = rememberLazyListState()
    return remember(cyan, red, favorite) { FlagTabListStates(cyan, red, favorite) }
}

/**
 * #385 — « +lus » left the first re-appearing topics hidden above the viewport: the list state
 * anchors on the first VISIBLE item's key, so rows inserted above it (read topics re-shown)
 * require a manual scroll up to be discovered. Reset the hoisted [listState] to the top when the
 * « non-lus uniquement » filter flips ON THE SAME TAB — the user just asked for a different topic
 * set, show it from the start. Tab switches keep the current behaviour (no reset).
 *
 * [tabUnreadFilter] is the ViewModel's ATOMIC (tab, unreadOnly) pair — each filter value is
 * pinned to the tab that produced it (`flatMapLatest`), so a tab switch can never be observed as
 * « new tab + stale filter » then « new tab + real filter », which this effect would misread as a
 * same-tab flip and reset the scroll on every switch (Codex review on PR #421).
 */
@Composable
private fun FilterFlipScrollResetEffect(
    tabUnreadFilter: Pair<FlagTab, Boolean>,
    listState: LazyListState,
) {
    var lastFilterByTab by remember { mutableStateOf<Pair<FlagTab, Boolean>?>(null) }
    LaunchedEffect(tabUnreadFilter) {
        val previous = lastFilterByTab
        lastFilterByTab = tabUnreadFilter
        if (previous != null &&
            previous.first == tabUnreadFilter.first &&
            previous.second != tabUnreadFilter.second
        ) {
            listState.scrollToItem(0)
        }
    }
}

// #546 — number of consecutive frames over which the « recall to top » scroll is re-asserted after a
// landing auto-refresh. requestScrollToItem is per-remeasure and not durable, while the refreshed list
// can land a frame or two after the signal (repository SharedFlow → combine/flatMapLatest), so a small
// window covers the prepend whichever frame it arrives on. Three is generous for an in-memory emission
// and the loop always terminates, so the one-shot signal is always consumed (no rotation replay).
private const val RECALL_TO_TOP_FRAMES = 3

// LazyColumn contentType tags (#179 compose-perf): one reuse pool per structurally distinct slot
// kind so Compose recycles like-for-like across the header→row→header alternation of the grouped
// list. Plain strings (the contentType is only ever compared for equality).
private const val CONTENT_TYPE_HEADER = "category_header"
private const val CONTENT_TYPE_EMPTY = "empty_section"
private const val CONTENT_TYPE_ROW = "flag_row"

/**
 * Category-grouped flag list (#179). Renders one sticky band per [FlagCategorySection] in the
 * canonical category order the ViewModel produced, with the rows under each band. Empty sections
 * are kept (HFR web parity) and show a per-tab placeholder instead of a row list.
 *
 * The whole list is a single [LazyColumn] so the surrounding `PullToRefreshBox` keeps a real
 * scrollable child to anchor the pull gesture on (#229) — the former `verticalScroll(Column)`
 * empty-state trick is no longer needed now that the body is always a lazy list.
 *
 * `stickyHeader` is the idiomatic foundation API for grouped lists; it is still annotated
 * `@ExperimentalFoundationApi` in the locked stable Compose (verified via Context7), hence the
 * `@OptIn`. Keys are prefixed so headers, empty placeholders and rows never collide
 * (`key` throws on duplicates): `topicId` is unique per category only (cf. AGENTS.md), so rows
 * use `"${flag.cat}-${flag.topicId}"`. Each slot kind also carries a distinct `contentType`
 * ([CONTENT_TYPE_HEADER]/[CONTENT_TYPE_EMPTY]/[CONTENT_TYPE_ROW]) so Compose keeps a separate
 * reuse pool per kind across the header→row→header alternation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
// List composable: sections + tab + removal + marker + band + funny + hideRead + actions + listState.
@Suppress("LongParameterList")
private fun CategorySectionedFlagList(
    sections: List<FlagCategorySection>,
    selectedTab: FlagTab,
    removalInFlight: Boolean,
    markerStyle: MarkerStyle,
    categoryBandStyle: CategoryBandStyle,
    funnyEmptyState: Boolean,
    hideReadActive: Boolean,
    actions: AuthenticatedActions,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // « Masquer les catégories sans non-lu » can filter every section out (no unread anywhere,
        // or all-read CYAN with « +lus » off). Without this guard the LazyColumn would render an
        // empty body — a blank screen with no scrollable target for the PullToRefreshBox (#229).
        // #662 — a real visual empty state (icon/smiley + contextual text) keeps the body informative
        // and the pull gesture anchored (fillParentMaxSize centers it). When the hide-read filter is
        // active, empty sections do NOT mean « no topic » (read topics may exist) — show a distinct,
        // factual wording instead of the per-tab « aucun … » that would be misleading (#662 Codex).
        if (sections.isEmpty()) {
            item(key = "grouped-empty", contentType = CONTENT_TYPE_EMPTY) {
                if (hideReadActive) {
                    FlagsEmptyState(
                        iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_flag,
                        title = stringResource(R.string.flags_no_unread_category),
                        subtitle = stringResource(R.string.flags_no_unread_category_subtitle),
                        funny = funnyEmptyState,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                } else {
                    FlagsTabEmptyState(
                        tab = selectedTab,
                        funny = funnyEmptyState,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
        }

        sections.forEach { section ->
            // contentType groups the three structurally distinct slot kinds (band / placeholder /
            // row) into separate reuse pools so scrolling across a header→rows→header boundary
            // recycles a row slot for a row instead of recreating the node from a header slot.
            stickyHeader(key = "cat-${section.catId}-header", contentType = CONTENT_TYPE_HEADER) {
                CategoryHeaderBand(
                    catId = section.catId,
                    label = section.catName
                        ?: stringResource(R.string.flags_category_fallback, section.catId),
                    style = categoryBandStyle,
                    // #414 — parité RF1 : the band navigates to the category's topic listing.
                    onClick = { actions.onOpenCategory(section.catId) },
                )
            }

            if (section.topics.isEmpty()) {
                item(key = "cat-${section.catId}-empty", contentType = CONTENT_TYPE_EMPTY) {
                    Text(
                        text = stringResource(emptySectionLabel(selectedTab)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(
                    items = section.topics,
                    key = { "${it.cat}-${it.topicId}" },
                    contentType = { CONTENT_TYPE_ROW },
                ) { flag ->
                    // Anti double-tap (#99): while a removal is in flight, swipe is disabled
                    // across the list. `removeFlagState` is Removing only between confirm and the
                    // repository result (a brief window); the modal dialog already blocks the
                    // Confirming phase and the ViewModel rejects re-entry.
                    RemovableFlagItem(
                        flag = flag,
                        metadata = flagRowMetadata(flag),
                        removalInFlight = removalInFlight,
                        markerStyle = markerStyle,
                        onClick = { actions.onOpenFlag(flag) },
                        onLongPress = { actions.onLongPressFlag(flag) },
                    )
                    FlagItemDivider()
                }
            }
        }
    }
}

/**
 * Category separator band for the grouped list (#179). #603 — the visual treatment is user-selectable
 * ([CategoryBandStyle], display sheet) ; this dispatches to the chosen leaf. ALL leaves render on an
 * OPAQUE base background because the band is a `stickyHeader`: a transparent background would let the
 * scrolling rows bleed through (the two originally-transparent mockups, ACCENT and BULLET, are
 * therefore opacified — XaTriX-approved).
 *
 * #414 — the whole band is tappable and opens the category's topic listing (RF1 parity); the trailing
 * chevron vector (`ic_chevron_right`) is the affordance. Foundation [clickable] does not enforce a
 * Material touch-target minimum, hence the explicit [heightIn] on every leaf. #603 / #671 — preset C
 * (XaTriX, after preset D's 34 dp felt too cramped for the band height) sets that min to 38 dp: still
 * below Material's 48 dp / WCAG AAA 44 dp recommendation but above the WCAG 2.2 AA 24 dp floor — a
 * deliberate density-over-target-size trade-off. A per-category band height setting is a future option.
 */
@Composable
private fun CategoryHeaderBand(catId: Int, label: String, style: CategoryBandStyle, onClick: () -> Unit) {
    when (style) {
        CategoryBandStyle.MINIMAL -> CategoryBandMinimal(catId, label, onClick)
        CategoryBandStyle.SOFT -> CategoryBandSoft(catId, label, onClick)
        CategoryBandStyle.ACCENT -> CategoryBandAccent(catId, label, onClick)
        CategoryBandStyle.BULLET -> CategoryBandBullet(catId, label, onClick)
    }
}

/** Trailing decorative chevron shared by the band styles; the open affordance is the band's onClickLabel. */
@Composable
private fun CategoryBandChevron() {
    RedfaceVectorIcon(
        resId = CoreUiR.drawable.ic_chevron_right,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        size = 18.dp,
    )
}

/**
 * MINIMAL (default, #654) — an opaque `surface` subhead: uppercase letter-spaced name in
 * `onSurfaceVariant` + a hairline divider below. Reads as a light subhead, not a heavy block.
 */
@Composable
private fun CategoryBandMinimal(catId: Int, label: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clickable(onClickLabel = stringResource(R.string.flags_category_open_label)) { onClick() }
                .padding(horizontal = 24.dp, vertical = 5.dp),
        ) {
            RedfaceVectorIcon(
                resId = categoryIcon(catId),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CategoryBandChevron()
        }
        FlagItemDivider()
    }
}

/**
 * SOFT — a soft tonal block (`surfaceContainer`, opaque), normal-case `titleSmall` name in
 * `onSurface`, no divider: the tonal block itself is the separator.
 */
@Composable
private fun CategoryBandSoft(catId: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .heightIn(min = 38.dp)
            .clickable(onClickLabel = stringResource(R.string.flags_category_open_label)) { onClick() }
            .padding(horizontal = 24.dp, vertical = 5.dp),
    ) {
        RedfaceVectorIcon(
            resId = categoryIcon(catId),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        CategoryBandChevron()
    }
}

/**
 * ACCENT — a full-height left accent bar (`primary`) + a `primary`-tinted icon, on an opaque `surface`
 * base, uppercase name + hairline divider. The bar uses `fillMaxHeight`, so the row is measured at its
 * min intrinsic height (the bounded-height-parent contract the marker stripe relies on).
 */
@Composable
private fun CategoryBandAccent(catId: Int, label: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(onClickLabel = stringResource(R.string.flags_category_open_label)) { onClick() },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 20.dp, vertical = 5.dp),
            ) {
                RedfaceVectorIcon(
                    resId = categoryIcon(catId),
                    tint = MaterialTheme.colorScheme.primary,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                CategoryBandChevron()
            }
        }
        FlagItemDivider()
    }
}

/**
 * BULLET — the category name carried in a tonal pill chip (`surfaceContainerHigh`) on an opaque
 * `surface` base, chevron pushed to the trailing edge. The chip wraps its content; the chip + base
 * give the originally-transparent mockup an opaque sticky-safe ground.
 */
@Composable
private fun CategoryBandBullet(catId: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 38.dp)
            .clickable(onClickLabel = stringResource(R.string.flags_category_open_label)) { onClick() }
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
            // weight(fill = false): the chip hugs a short name (chevron stays right via SpaceBetween)
            // but is capped at the available width for a long one, so it can never push the chevron off
            // (Codex review) — the label then ellipsises inside.
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                RedfaceVectorIcon(
                    resId = categoryIcon(catId),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 16.dp,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CategoryBandChevron()
    }
}

/**
 * Empty-section wording per tab (#179). Cyan (« Mes sujets ») is the only bucket where « Aucun
 * nouveau message » is literally true (web parity); RED/FAVORITE list read topics too, so a
 * neutral « Aucun sujet dans cette catégorie » is used to avoid a misleading label.
 */
private fun emptySectionLabel(tab: FlagTab): Int = when (tab) {
    FlagTab.Cyan -> R.string.flags_category_empty_cyan
    else -> R.string.flags_category_empty
}

/**
 * Flat flag list — the legacy pre-#179 view, kept reachable via the « grouper par catégorie »
 * preference (Settings). Renders every [flags] entry in repository order (last reply descending)
 * with no category bands. Like [CategorySectionedFlagList] it is a single [LazyColumn] so the
 * surrounding `PullToRefreshBox` keeps a scrollable child to anchor the pull gesture on (#229);
 * an empty list still emits one placeholder item so the « rien à afficher » wording is shown and
 * the pull gesture has a target. Rows reuse [RemovableFlagItem] so the long-press removal (#457)
 * and accessibility action behave identically to the grouped view.
 */
@Composable
@Suppress("LongParameterList") // List composable: flags + tab + removal flag + marker + funny + actions + listState.
private fun FlatFlagList(
    flags: List<Flag>,
    selectedTab: FlagTab,
    removalInFlight: Boolean,
    markerStyle: MarkerStyle,
    funnyEmptyState: Boolean,
    actions: AuthenticatedActions,
    listState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (flags.isEmpty()) {
            item(key = "flat-empty", contentType = CONTENT_TYPE_EMPTY) {
                // Flat view ignores « masquer les catégories sans non-lu », so an empty list always
                // means the tab genuinely has no flag → the per-tab empty state is correct.
                FlagsTabEmptyState(
                    tab = selectedTab,
                    funny = funnyEmptyState,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            items(
                items = flags,
                key = { "${it.cat}-${it.topicId}" },
                contentType = { CONTENT_TYPE_ROW },
            ) { flag ->
                RemovableFlagItem(
                    flag = flag,
                    metadata = flagRowMetadata(flag),
                    removalInFlight = removalInFlight,
                    markerStyle = markerStyle,
                    onClick = { actions.onOpenFlag(flag) },
                    onLongPress = { actions.onLongPressFlag(flag) },
                )
                FlagItemDivider()
            }
        }
    }
}

/**
 * #662 — per-tab visual empty state for a fully-empty Drapeaux tab. Resolves the tab's icon, title
 * and subtitle, then delegates to [FlagsEmptyState]. Used for the genuinely-empty case (the tab has
 * no flag at all). The grouped « masquer les catégories sans non-lu » case uses [FlagsEmptyState]
 * directly with a distinct, factual wording (cf. #662 Codex review).
 */
@Composable
private fun FlagsTabEmptyState(
    tab: FlagTab,
    funny: Boolean,
    modifier: Modifier = Modifier,
) {
    FlagsEmptyState(
        iconRes = emptyStateIcon(tab),
        title = stringResource(emptyStateTitle(tab)),
        subtitle = emptyStateSubtitle(tab)?.let { stringResource(it) },
        funny = funny,
        modifier = modifier,
    )
}

/**
 * #662 — visual empty state renderer for a fully-empty Drapeaux tab (grouped or flat). Default
 * (style A) shows a thin icon; with the « états vides humoristiques » opt-in ([funny], style C) the
 * same contextual text appears under a HFR perso smiley instead. The title + subtitle carry all the
 * meaning, so TalkBack reads an identical state either way — the visual is decorative
 * (`contentDescription = null`). Centered via [fillParentMaxSize] from the calling lazy item so the
 * surrounding `PullToRefreshBox` keeps a swipe target (#229).
 */
@Composable
private fun FlagsEmptyState(
    iconRes: Int,
    title: String,
    subtitle: String?,
    funny: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (funny) {
            AsyncImage(
                model = FUNNY_EMPTY_SMILEY_URL,
                // Decorative: the title/subtitle below carry the meaning (a11y). The perso smiley is a
                // ~47×50 px PHOTO (not pixel-art), so the previous FilterQuality.None upscaled it into
                // visible blocks (#662 feedback, rejected). Use smooth (default) filtering and a modest
                // size so it stays clean. The app-wide ImageLoader registers AnimatedImageDecoder, so
                // the .gif animates.
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            RedfaceVectorIcon(
                resId = iconRes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 48.dp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** #662 — per-tab style-A icon (local vector drawable; the project forbids Material `Icons.*`). */
private fun emptyStateIcon(tab: FlagTab): Int = when (tab) {
    FlagTab.Cyan -> fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_forum
    FlagTab.Red -> fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_flag
    FlagTab.Favorite -> fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_star
    else -> fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_flag
}

/** #662 — per-tab empty-state title. */
private fun emptyStateTitle(tab: FlagTab): Int = when (tab) {
    FlagTab.Cyan -> R.string.flags_empty_title_cyan
    FlagTab.Red -> R.string.flags_empty_title_red
    FlagTab.Favorite -> R.string.flags_empty_title_favorite
    else -> R.string.flags_empty_title_generic
}

/** #662 — per-tab empty-state subtitle (null = title only, e.g. the placeholder tabs). */
private fun emptyStateSubtitle(tab: FlagTab): Int? = when (tab) {
    FlagTab.Cyan -> R.string.flags_empty_subtitle_cyan
    FlagTab.Red -> R.string.flags_empty_subtitle_red
    FlagTab.Favorite -> R.string.flags_empty_subtitle_favorite
    else -> null
}

// #662 — perso smiley for the « états vides humoristiques » opt-in (style C). The space in the HFR
// perso filename is percent-encoded so the request URL is well-formed.
private const val FUNNY_EMPTY_SMILEY_URL =
    "https://forum-images.hardware.fr/images/perso/eric%20le%20looser.gif"

/**
 * #662 — [FlagsEmptyState] wrapped in a scrollable, centred container for the non-lazy bodies (the DT
 * tab states). Unlike the flag tabs (a lazy `item` using `fillParentMaxSize`), these sit directly
 * under a `PullToRefreshBox`, so the body itself must stay vertically scrollable to anchor the pull
 * gesture (#229) — even when the empty content is shorter than the viewport.
 */
@Composable
private fun ScrollableFlagsEmptyState(
    iconRes: Int,
    title: String,
    subtitle: String?,
    funny: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        FlagsEmptyState(iconRes = iconRes, title = title, subtitle = subtitle, funny = funny)
    }
}

// #603 PR5 — hosts the long-press actions sheet; the null-check lives here (not in FlagsRoute) to keep
// the route's cyclomatic complexity in budget. `flag == null` ⇒ nothing composed (sheet closed).
@Composable
@Suppress("LongParameterList") // host: the sheet flag + super-favorite set + 4 action callbacks.
private fun FlagActionsSheetHost(
    flag: Flag?,
    superFavoriteIds: Set<Int>,
    onOpen: (Flag) -> Unit,
    onToggleSuperFavorite: (Flag) -> Unit,
    onRemove: (Flag) -> Unit,
    onDismiss: () -> Unit,
) {
    if (flag == null) return
    FlagActionsSheet(
        flag = flag,
        categoryName = flagCategoryName(flag.cat),
        isSuperFavorite = flag.topicId in superFavoriteIds,
        actions = FlagSheetActions(
            onOpen = { onOpen(flag) },
            onToggleSuperFavorite = { onToggleSuperFavorite(flag) },
            onRemove = { onRemove(flag) },
            onDismiss = onDismiss,
        ),
    )
}

// #603 PR5 — canonical category name for the sheet header, fallback « Catégorie N » for unknown ids.
@Composable
private fun flagCategoryName(catId: Int): String =
    FALLBACK_CATEGORY_ORDER.firstOrNull { it.id == catId }?.name
        ?: stringResource(R.string.flags_category_fallback, catId)

/**
 * One removable flag row. The « Retirer » affordance went swipe-to-dismiss (#99) → **long-press**
 * (#457): the horizontal swipe now changes the flag tab, and a row-level `SwipeToDismissBox`
 * would consume the horizontal drag first and steal the tab gesture. #603 PR5: the long-press now
 * opens the rich actions sheet ([FlagActionsSheet]) via [onLongPress] — removal is one action inside
 * it and still goes through the existing confirmation dialog (the repository then evicts the item
 * from the cache, which recomposes the list away). Arbitrage XaTriX (#457): the swipe-to-remove may
 * come back later in another form.
 *
 * The long-press being invisible, the row keeps the TalkBack/switch-access `customActions`
 * entry (#99) in addition to the `onLongClickLabel` semantics. While a removal is in flight
 * ([removalInFlight]) the long-press is a no-op — the ViewModel also guards re-entry;
 * `Removing` is only the brief confirm→result window.
 */
@Composable
@Suppress("LongParameterList") // Row binding: flag + metadata + removalInFlight + marker style + 2 callbacks.
private fun RemovableFlagItem(
    flag: Flag,
    metadata: FlagMetadata,
    removalInFlight: Boolean,
    markerStyle: MarkerStyle,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val actionsLabel = stringResource(R.string.flags_row_actions_label)

    FlagItem(
        flag = flag,
        metadata = metadata,
        onClick = onClick,
        markerStyle = markerStyle,
        longPress = FlagItemLongPress(label = actionsLabel) {
            if (!removalInFlight) onLongPress()
        },
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(actionsLabel) {
                        if (!removalInFlight) onLongPress()
                        true
                    },
                )
            },
    )
}

/**
 * Sober M3 placeholder for the future « super favoris » [FlagTab.Super] tab. No list, no
 * network call — just an explanatory message until the feature ships.
 */
@Composable
private fun SuperPlaceholderBody(funny: Boolean) {
    // #662 — same visual empty state as the flag tabs (icon/smiley + title + subtitle). No
    // pull-to-refresh here (Super has no backend), so a plain centered, non-scrollable body is fine.
    FlagsEmptyState(
        iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_star,
        title = stringResource(R.string.flags_super_placeholder_title),
        subtitle = stringResource(R.string.flags_super_placeholder_body),
        funny = funny,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * If the Settings toggle hides the DT tab while it is selected, falls back to Cyan —
 * otherwise the body would keep rendering a tab that no longer exists in the row.
 * Extracted so the guard's branches don't count against [FlagsRoute]'s complexity budget.
 */
@Composable
private fun DtTabFallbackEffect(
    showDtTab: Boolean,
    selectedTab: FlagTab,
    onFallback: () -> Unit,
) {
    LaunchedEffect(showDtTab, selectedTab) {
        if (!showDtTab && selectedTab == FlagTab.Dt) onFallback()
    }
}

/**
 * #6 — fires the DT (MultiMP) scan once the DT tab becomes the selected one. The fetch only starts
 * when DT is selected AND the Settings toggle still shows it ([showDtTab]) — a stale
 * `selectedTab == Dt` left behind while the toggle is off (before [DtTabFallbackEffect] swaps back
 * to Cyan) must never scan the inbox.
 *
 * Keyed on the tab, [showDtTab], AND [authSessionKey] (the authenticated pseudo). The session key is
 * load-bearing: an account switch resets the DT state back to Loading without changing
 * `selectedTab`, so a Unit/tab-only key would never re-fire and the screen would stay stuck on the
 * spinner while sitting on the DT tab (Codex review). The ViewModel guards the actual fetch to once
 * per session, so a benign re-key (returning to DT) reuses the loaded list.
 *
 * Extracted so its branch stays out of [FlagsRoute]'s cyclomatic-complexity budget.
 */
@Composable
private fun DtTabOpenEffect(
    selectedTab: FlagTab,
    showDtTab: Boolean,
    authSessionKey: String?,
    onDtTabOpened: () -> Unit,
) {
    LaunchedEffect(selectedTab, showDtTab, authSessionKey) {
        if (selectedTab == FlagTab.Dt && showDtTab) onDtTabOpened()
    }
}

/**
 * #6 — body of the opt-in « DT » tab: the UNION of the user's MultiMP conversations on inbox PAGE 1
 * (`cat=prive`, filtered on [PrivateMessageSummary.isMultiRecipient]) and the orphan MPStorage
 * entries absent from that page, deduplicated by `threadId`. Inbox rows render the rich
 * [DtListItem.InboxBacked] (subject, unread dot, « reprise p.N » badge) ; orphans render the
 * placeholder [DtListItem.StorageOnly] (« Conversation #threadId », neutral dot). A discreet
 * scan-note footer states the page-1 limit. Tapping a row opens the existing `PrivateMessageThread`
 * route via [AuthenticatedActions.onOpenMultiMp].
 *
 * The MPStorage enrichment is best-effort: a missing / unreadable / failed storage read leaves the
 * list intact, just without badges (cf. [FlagsViewModel.loadDt]). Only an inbox load failure
 * reaches [DtListUiState.Error], which offers a reconnect/retry CTA like the flag list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DtListBody(
    state: DtListUiState,
    isRefreshing: Boolean,
    actions: AuthenticatedActions,
    funny: Boolean,
) {
    // #546 directive XaTriX — pull-to-refresh (swipe down) on the DT list, like the flag tabs. Wraps
    // the whole body (every branch) so the gesture has a target even on the listless states (#229).
    // #603 — « amorce seule » (XaTriX): pull cue while dragging, nothing during the refresh; the top
    // FlagsLoadingBar (driven by dtIsRefreshing / DtListUiState.Loading) is the single loading cue.
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = actions.onRefreshDt,
        state = pullState,
        indicator = {
            FlagsPullAmorce(pullState, isRefreshing, Modifier.align(Alignment.TopCenter))
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        DtListContent(state = state, actions = actions, funny = funny)
    }
}

/**
 * The DT body content under the [DtListBody] pull-to-refresh layer. Dispatches the [DtListUiState]
 * to its branch. The listless states ([DtListUiState.Loading]/[Empty]/[NoUnread]/[Error]) are made
 * vertically scrollable so the surrounding `PullToRefreshBox` keeps a target (#229).
 */
@Composable
private fun DtListContent(state: DtListUiState, actions: AuthenticatedActions, funny: Boolean) {
    when (state) {
        DtListUiState.Loading -> Box(
            // #603 — central spinner retired (cf. FlagListBody); the top FlagsLoadingBar covers the DT
            // initial load too. Empty but scrollable so the PullToRefreshBox keeps a target (#229).
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )

        // #662 — visual empty state (icon/smiley + message) for the DT tab, like the flag tabs.
        DtListUiState.Empty -> ScrollableFlagsEmptyState(
            iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_mail,
            title = stringResource(R.string.flags_dt_empty_title),
            subtitle = stringResource(R.string.flags_dt_empty_subtitle),
            funny = funny,
        )

        // #546 — the union is non-empty but « non-lus uniquement » hid every row: a dedicated message
        // (not the « aucune conversation » empty copy). Re-tapping the DT tab reveals « +lus ».
        DtListUiState.NoUnread -> ScrollableFlagsEmptyState(
            iconRes = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_mail,
            title = stringResource(R.string.flags_dt_no_unread),
            // #662 (demande XaTriX) — pas de sous-texte : le caveat de balayage vit dans la description du
            // réglage « Section DT » (settings), plus dans la vue. La découvrabilité « +lus » reste #661.
            subtitle = null,
            funny = funny,
        )

        is DtListUiState.Error -> DtErrorBody(cause = state.cause, actions = actions)

        is DtListUiState.Content -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // DT now renders through the SAME row primitive + divider as the Cyan/Red/Favorite lists
            // (no Card, no contentPadding/spacing) so the four lists stay visually identical and a
            // row change propagates to all (arbitrage XaTriX 2026-06-19). The list is the union of
            // inbox PAGE 1 MultiMP rows + orphan MPStorage entries (#6) — dispatched per variant.
            items(
                items = state.items,
                key = { it.threadId },
                contentType = { CONTENT_TYPE_ROW },
            ) { item ->
                DtRow(item = item, onOpenMultiMp = actions.onOpenMultiMp)
                FlagItemDivider()
            }
            // #662 (demande XaTriX) — le caveat de balayage (« seule la page 1 est listée ») a quitté la
            // vue : il vit désormais dans la description du réglage « Section DT » (settings), au niveau de
            // l'activation. Plus de footer scan-note ici.
        }
    }
}

/**
 * Dispatches a [DtListItem] to its row composable (#6): an [DtListItem.InboxBacked] renders the rich
 * inbox row, a [DtListItem.StorageOnly] the placeholder orphan row. Both open via [onOpenMultiMp] on
 * the page the « reprise p.N » badge advertises (clamped ≥ 1).
 */
@Composable
private fun DtRow(item: DtListItem, onOpenMultiMp: (threadId: Int, page: Int, wasUnread: Boolean) -> Unit) {
    when (item) {
        is DtListItem.InboxBacked -> DtConversationRow(
            item = item,
            // Open on the page the badge ADVERTISES: the MPStorage resume page when present, else the
            // conversation's last inbox page (#430). Tapping must land where the « reprise p.N » badge
            // says it will — opening lastPage while showing « reprise p.N » was a badge/action
            // contradiction (Codex review). Clamp ≥ 1 so a bogus stored 0/negative page never
            // produces an invalid route argument. `wasUnread` mirrors the Messages tab so the host
            // decrements the MP badge on first read (HFR has no server read flag, so a stuck-high
            // badge never self-corrects on re-fetch).
            onClick = {
                val target = (item.resumePage ?: item.conversation.lastPage).coerceAtLeast(1)
                onOpenMultiMp(item.threadId, target, dtRowWasUnread(item))
            },
        )

        is DtListItem.StorageOnly -> DtStorageOnlyRow(
            item = item,
            // No lastPage for an orphan: open on the MPStorage resume page (clamped ≥ 1), else page 1.
            // The read/unread state of an orphan is unknown off inbox PAGE 1, so wasUnread = false
            // (dtRowWasUnread) — never speculatively decrement the badge for an unknown state.
            onClick = { onOpenMultiMp(item.threadId, (item.resumePage ?: 1).coerceAtLeast(1), dtRowWasUnread(item)) },
        )
    }
}

/**
 * #6 / badge — whether a DT row was UNREAD when tapped, used by the host to decrement the MP unread
 * badge on first read (mirror of the Messages tab's onOpenThread `wasUnread`). An [DtListItem.InboxBacked]
 * carries the live inbox read state ([PrivateMessageSummary.hasUnread]); an orphan [DtListItem.StorageOnly]
 * has no known read state off inbox PAGE 1, so it reports `false` (never speculatively decrement).
 */
internal fun dtRowWasUnread(item: DtListItem): Boolean = when (item) {
    is DtListItem.InboxBacked -> item.conversation.hasUnread
    is DtListItem.StorageOnly -> false
}


/**
 * Error body for the DT list, mirroring the flag-list failure branch: a session-expired cause
 * offers a reconnect CTA, every other cause a retry. The MPStorage read never lands here (it is
 * best-effort) — only the inbox primary load.
 */
@Composable
private fun DtErrorBody(cause: Throwable, actions: AuthenticatedActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sessionExpired = cause is SessionExpiredException
        Text(
            text = stringResource(
                if (sessionExpired) {
                    R.string.flags_session_expired
                } else {
                    classifyHfrError(cause).sharedLabelResOrNull() ?: R.string.flags_dt_error
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (sessionExpired) {
            TextButton(onClick = actions.onLoginRequested) {
                Text(stringResource(R.string.flags_login_cta))
            }
        } else {
            TextButton(onClick = actions.onRefreshDt) {
                Text(stringResource(R.string.flags_retry))
            }
        }
    }
}

/**
 * One DT conversation row: the « Interlocuteurs multiples » label, the conversation subject, an
 * unread dot ([PrivateMessageSummary.hasUnread]), and — when present — the discreet « reprise p.N »
 * MPStorage badge. The badge is a reading POSITION, never a read/unread state (#361/ADR-013).
 */
@Composable
private fun DtConversationRow(item: DtListItem.InboxBacked, onClick: () -> Unit) {
    val conversation = item.conversation
    // DT renders through the shared [ForumListRow]: the inbox dot is the leading slot, the subject is
    // the title (unread → SemiBold), and the « Interlocuteurs multiples » caption + the « reprise p.N »
    // MPStorage badge become the two-segment metadata line (badge in `end`, never truncated — Codex).
    // The badge is a reading POSITION, never a read/unread state (#361/ADR-013).
    val multiLabel = stringResource(R.string.flags_dt_multi_recipient)
    val resumeBadge = item.resumePage?.let { stringResource(R.string.flags_dt_resume_badge, it) }.orEmpty()
    // a11y — the dot is decorative, so the row carries the full announcement for TalkBack: read state
    // + subject, then « Interlocuteurs multiples » and the resume page when present (Codex review).
    val rowStateDescription = stringResource(
        if (conversation.hasUnread) R.string.flags_dt_row_unread else R.string.flags_dt_row_read,
        conversation.subject,
    )
    val contentDescription = buildString {
        append(rowStateDescription)
        append(". ")
        append(multiLabel)
        if (resumeBadge.isNotEmpty()) {
            append(". ")
            append(resumeBadge)
        }
    }
    ForumListRow(
        title = conversation.subject,
        metadata = FlagMetadata(start = multiLabel, end = resumeBadge),
        onClick = onClick,
        emphasized = conversation.hasUnread,
        leading = { DtUnreadDot(unread = conversation.hasUnread) },
        contentDescription = contentDescription,
    )
}

/**
 * One ORPHAN DT row (#6): a MPStorage `mpFlags` entry absent from inbox PAGE 1. No subject and no
 * read/unread state are available, so the title is a localized placeholder « Conversation #threadId »
 * and the leading dot is the neutral (hollow) variant whose a11y reads « état inconnu », never
 * « Lu ». The « reprise p.N » badge ([DtListItem.StorageOnly.resumePage]) is shown when present.
 */
@Composable
private fun DtStorageOnlyRow(item: DtListItem.StorageOnly, onClick: () -> Unit) {
    val title = stringResource(R.string.flags_dt_storage_only_title, item.threadId)
    val multiLabel = stringResource(R.string.flags_dt_multi_recipient)
    val resumeBadge = item.resumePage?.let { stringResource(R.string.flags_dt_resume_badge, it) }.orEmpty()
    // a11y — the read/unread state is unknown off-inbox, so the row announces « état inconnu » (never
    // « Lu »/« Non lu ») followed by the placeholder title, « Interlocuteurs multiples » and the badge.
    // stringResource resolved here (it is @Composable, unusable inside the plain buildString lambda).
    val stateUnknown = stringResource(R.string.flags_dt_row_state_unknown, title)
    val contentDescription = buildString {
        append(stateUnknown)
        append(". ")
        append(multiLabel)
        if (resumeBadge.isNotEmpty()) {
            append(". ")
            append(resumeBadge)
        }
    }
    ForumListRow(
        title = title,
        metadata = FlagMetadata(start = multiLabel, end = resumeBadge),
        onClick = onClick,
        emphasized = false,
        leading = { DtUnreadDot(unread = false) },
        contentDescription = contentDescription,
    )
}

/**
 * Inbox unread marker for a DT row: a filled primary dot when unread, a hollow outline ring when
 * read. Mirrors the Messages tab's `ReadStateDot` without depending on `feature/messages`; the a11y
 * state is carried by the row text, so this stays decorative.
 */
@Composable
private fun DtUnreadDot(unread: Boolean) {
    if (unread) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(12.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/**
 * Read-only state bundle for [AuthenticatedBody], grouped so the composable stays under the
 * detekt parameter-count threshold and mirrors the [AuthenticatedActions] callback bundle.
 */
private data class FlagsBodyState(
    val selectedTab: FlagTab,
    val flagsState: FlagsListUiState?,
    /** Whether the Cyan tab currently shows read topics (« +lus » suffix), #317. */
    val cyanShowsRead: Boolean,
    /** Any refresh in flight (manual OR auto) — drives the top linear bar (single loading cue). */
    val isRefreshing: Boolean,
    val removeFlagState: RemoveFlagState,
    /** Whether the opt-in « DT » tab is shown (Settings toggle). */
    val showDtTab: Boolean,
    /** #6 — the DT (MultiMP) tab list state, fetched on tab open, FILTERED by « non-lus uniquement ». */
    val dtListState: DtListUiState,
    /** #546 — whether the DT tab currently shows read conversations (« +lus » suffix). */
    val dtShowsRead: Boolean,
    /** #546 — toggled around the DT pull-to-refresh round-trip (anchors the indicator). */
    val dtIsRefreshing: Boolean,
    /** #603 PR2 — active client-side search query; empty = no filtering. */
    val searchQuery: String = "",
    /** #603 PR6 — GLOBAL marker shape for the rows (from FlagsViewSettings). */
    val markerStyle: MarkerStyle = MarkerStyle.STRIPE,
    /** #603 — GLOBAL category band style for the grouped view (from FlagsViewSettings). */
    val categoryBandStyle: CategoryBandStyle = CategoryBandStyle.MINIMAL,
    /** #662 — « états vides humoristiques » opt-in: smiley empty state instead of the sober icon. */
    val funnyEmptyState: Boolean = false,
    /**
     * #662 (Codex) — whether « masquer les catégories sans non-lu » is active for the current tab.
     * When true, an empty grouped list means « no category with unread », NOT « no topic », so the
     * empty state uses a distinct factual wording instead of the per-tab « aucun … ».
     */
    val hideReadActive: Boolean = false,
)

private data class AuthenticatedActions(
    val onSelectTab: (FlagTab) -> Unit,
    val onOpenFlag: (Flag) -> Unit,
    val onRefresh: () -> Unit,
    val onLoginRequested: () -> Unit,
    /** #603 PR5 — long-press opens the rich actions sheet ([FlagActionsSheet]); removal moved inside it. */
    val onLongPressFlag: (Flag) -> Unit,
    /** #414 — tap on a category band opens that category's topic listing. */
    val onOpenCategory: (Int) -> Unit,
    /** #6 — open a DT conversation (threadId, last inbox page, unread-on-open for the badge). */
    val onOpenMultiMp: (threadId: Int, page: Int, wasUnread: Boolean) -> Unit = { _, _, _ -> },
    /** #6 — explicit user reload of the DT list (retry). */
    val onRefreshDt: () -> Unit = {},
)

/**
 * Callback bundle for [FlagsViewSettingsSheet] (#309 + #317), grouped so the sheet stays under the
 * detekt parameter-count threshold. The layout `*Change` writes route through the ViewModel (which
 * decides global vs per-type scope); [onUnreadOnlyChange] is always per-type (#317); [onDismiss]
 * closes the sheet.
 */
private data class FlagsViewSettingsActions(
    val onPerTabOverrideChange: (Boolean) -> Unit,
    val onGroupByCategoryChange: (Boolean) -> Unit,
    val onHideReadCategoriesChange: (Boolean) -> Unit,
    val onUnreadOnlyChange: (Boolean) -> Unit,
    val onMarkerStyleChange: (MarkerStyle) -> Unit,
    val onMarkerBorderChange: (Boolean) -> Unit,
    val onSingleLineTitleChange: (Boolean) -> Unit,
    val onCategoryBandStyleChange: (CategoryBandStyle) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Builds the two-segment footer of a drapeau row ([FlagMetadata]). #603 refonte (ADR-017): `start`
 * is the last poster (the only segment allowed to ellipsise) and `end` is the last-reply timestamp,
 * formatted web-style (`01-05-2026 à 17:07`) by [formatLastReplyTimestamp], end-aligned and never
 * truncated by [FlagItem]. The page position (« p.X/Y ») left the footer: the pages-à-lire count is
 * now the trailing [fr.forumhfr.redface2.core.ui.PagesToReadPill] of the row. Blank when REST omits
 * a field.
 */
private fun flagRowMetadata(flag: Flag): FlagMetadata =
    FlagMetadata(start = flag.lastReplyAuthor, end = formatLastReplyTimestamp(flag.lastReplyAt))
