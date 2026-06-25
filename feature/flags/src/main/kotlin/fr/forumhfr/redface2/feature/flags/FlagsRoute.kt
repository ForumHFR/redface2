package fr.forumhfr.redface2.feature.flags

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.ui.FlagItem
import fr.forumhfr.redface2.core.ui.FlagItemDivider
import fr.forumhfr.redface2.core.ui.FlagItemLongPress
import fr.forumhfr.redface2.core.ui.FlagMetadata
import fr.forumhfr.redface2.core.ui.ForumListRow
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull
import fr.forumhfr.redface2.core.ui.formatLastReplyTimestamp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.icon.categoryIcon
import fr.forumhfr.redface2.core.ui.theme.FlagPalette
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
fun FlagsRoute(
    onOpenFlag: (Flag) -> Unit,
    onLoginRequested: () -> Unit,
    onOpenCategory: (Int) -> Unit = {},
    // #6 — open a DT (MultiMP) conversation : the host pushes the existing PrivateMessageThread
    // route. `page` is the conversation's last inbox page (web parity, #430). `wasUnread` lets the
    // host decrement the MP unread badge on first read, mirroring the Messages tab's onOpenThread
    // (badge regression: the DT path never reported it, so the badge stayed stuck high).
    onOpenMultiMp: (threadId: Int, page: Int, wasUnread: Boolean) -> Unit = { _, _, _ -> },
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

    // « +lus » suffix on the Cyan tab: shown when the Cyan tab is selected and CYAN's « non-lus
    // uniquement » filter is off (read participated topics are visible). The ViewModel derives this
    // from [cyanUnreadOnly] (CYAN-specific, optimistic, eager `true`) so the suffix never flashes on
    // a cold start or a tab switch before DataStore re-resolves the selected tab (#317).
    val cyanShowsRead by viewModel.cyanShowsReadShortcut.collectAsStateWithLifecycle()

    // Opt-in « DT » tab (Settings toggle). Its MultiMP list is fetched on tab open (#6).
    val showDtTab by viewModel.showDtTab.collectAsStateWithLifecycle()
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
    val flagsListState = rememberLazyListState()
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
    val canConfigureView = authState is AuthState.Authenticated && selectedTab.flagType != null

    // If the screen stops being configurable while the sheet is open (session expired, or the user
    // lands on the Super tab), clear the flag so the sheet can't silently reappear on the next
    // configurable tab/re-auth.
    LaunchedEffect(canConfigureView) {
        if (!canConfigureView) showViewSettingsSheet = false
    }

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
                    ),
                    onSelectTab = viewModel::selectTab,
                    onQueryChange = { searchQuery = it },
                    onSearchActiveChange = { searchActive = it },
                    onOpenViewSettings = if (canConfigureView) {
                        { showViewSettingsSheet = true }
                    } else {
                        null
                    },
                    accountMenu = { topBarActions?.invoke() },
                )

                // Render nothing while authState is null (cookie jar warming up). Same
                // anti-flicker convention as PR #91; defaulting to "Anonymous" here would
                // bring the cold-start "Se connecter" flash back.
                authState?.let { state ->
                    when (state) {
                        AuthState.Anonymous -> AnonymousBody(onLoginRequested)
                        is AuthState.Authenticated -> AuthenticatedBody(
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
                                onRequestRemoveFlag = viewModel::requestRemoveFlag,
                                onOpenCategory = onOpenCategory,
                                onOpenMultiMp = onOpenMultiMp,
                                onRefreshDt = viewModel::refreshDt,
                            ),
                            listState = flagsListState,
                        )
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
                onDismiss = { showViewSettingsSheet = false },
            ),
        )
    }

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

// #603 PR2 — flag color of the current tab for the app-bar indicator glyph; the Super/DT placeholders
// have no flag color so they fall back to a neutral on-surface tint.
@Composable
private fun flagTabColor(tab: FlagTab): Color = when (tab.flagType) {
    FlagType.CYAN -> FlagPalette.Cyan
    FlagType.RED -> FlagPalette.Red
    FlagType.FAVORITE -> FlagPalette.Favorite
    null -> MaterialTheme.colorScheme.onSurfaceVariant
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
                    neutral,
                ),
            )
        }
        add(FlagTabEntry(FlagTab.Super, stringResource(R.string.flags_tab_super), neutral))
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
    // #385 — hoisted by FlagsRoute so the filter-flip effect can reset the scroll. One state
    // shared by the grouped and flat lists (only one is composed at a time).
    listState: LazyListState,
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
    val swipeHandlers = remember(haptics) {
        FlagsTabSwipeHandlers(
            haptics = haptics,
            onSelectTab = { index ->
                updatedTabs.value.getOrNull(index)
                    ?.let { tab -> updatedActions.value.onSelectTab(tab) }
            },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Without weight(1f) the box would not claim the remaining vertical space inside
            // the parent Column, breaking the gesture areas on short lists.
            .weight(1f)
            .flagsTabSwipe(
                currentIndex = { updatedSelectedIndex.value },
                tabCount = { updatedTabs.value.size },
                dragOffset = dragOffset,
                handlers = swipeHandlers,
            ),
    ) {
        when (selectedTab) {
            // Super has no backend, no fetch, no pull-to-refresh (cf. its KDoc).
            FlagTab.Super -> SuperPlaceholderBody()
            // #6 — DT is a real list now: the user's MultiMP conversations, enriched best-effort
            // with the MPStorage reading positions.
            FlagTab.Dt -> DtListBody(
                state = state.dtListState,
                isRefreshing = state.dtIsRefreshing,
                actions = actions,
            )
            else -> FlagListBody(state = state, actions = actions, listState = listState)
        }
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
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val current = state.flagsState) {
            null, FlagsListUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

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
                // the pull-to-refresh still has a target (#229).
                val content = current.content.filteredBy(state.searchQuery)
                if (state.searchQuery.isNotBlank() && content.isEmpty()) {
                    NoFlagsSearchResults(query = state.searchQuery)
                } else {
                    when (content) {
                        is FlagsContent.Grouped -> CategorySectionedFlagList(
                            sections = content.sections,
                            selectedTab = selectedTab,
                            removalInFlight = state.removeFlagState is RemoveFlagState.Removing,
                            actions = actions,
                            listState = listState,
                        )

                        is FlagsContent.Flat -> FlatFlagList(
                            flags = content.flags,
                            selectedTab = selectedTab,
                            removalInFlight = state.removeFlagState is RemoveFlagState.Removing,
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

// #6 — the DT scan-note footer is a distinct, non-row item (not a clickable ForumListRow), so it
// keeps its own recycling pool and never reuses a row slot.
private const val CONTENT_TYPE_DT_FOOTER = "dt_scan_note"

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
private fun CategorySectionedFlagList(
    sections: List<FlagCategorySection>,
    selectedTab: FlagTab,
    removalInFlight: Boolean,
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
        // A single placeholder item keeps the body informative and the pull gesture anchored.
        if (sections.isEmpty()) {
            item(key = "grouped-empty", contentType = CONTENT_TYPE_EMPTY) {
                Text(
                    text = stringResource(R.string.flags_no_unread_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
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
                        onClick = { actions.onOpenFlag(flag) },
                        onRequestRemove = { actions.onRequestRemoveFlag(flag) },
                    )
                    FlagItemDivider()
                }
            }
        }
    }
}

/**
 * Opaque category separator band for the grouped list (#179). Uses `surfaceVariant` /
 * `onSurfaceVariant` from the theme (no hardcoded color) so it reads as a sticky header over
 * the scrolling rows without bleed-through.
 *
 * #414 — the whole band is tappable and opens the category's topic listing (RF1 parity); the
 * trailing « › » glyph (same vector-text pattern as the page FABs, #283) is the affordance.
 * Foundation [clickable] does not enforce the 48dp Material touch-target minimum, hence the
 * explicit [heightIn].
 */
@Composable
private fun CategoryHeaderBand(catId: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.flags_category_open_label)) {
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        // #603 — Material Symbols glyph per category (spike 4, ADR-017); decorative (the label carries
        // the name), so no contentDescription. Generic forum glyph for unknown categories.
        RedfaceVectorIcon(
            resId = categoryIcon(catId),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 20.dp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun FlatFlagList(
    flags: List<Flag>,
    selectedTab: FlagTab,
    removalInFlight: Boolean,
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
                Text(
                    text = stringResource(flatEmptyLabel(selectedTab)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
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
                    onClick = { actions.onOpenFlag(flag) },
                    onRequestRemove = { actions.onRequestRemoveFlag(flag) },
                )
                FlagItemDivider()
            }
        }
    }
}

/**
 * Empty-list wording per tab for the FLAT view (#179 follow-up). Mirrors [emptySectionLabel] but
 * without the « catégorie » noun, which would be misleading in a flat list. Cyan keeps the « aucun
 * nouveau message » parity wording.
 */
private fun flatEmptyLabel(tab: FlagTab): Int = when (tab) {
    FlagTab.Cyan -> R.string.flags_list_empty_cyan
    else -> R.string.flags_list_empty
}

/**
 * One removable flag row. The « Retirer » affordance went swipe-to-dismiss (#99) → **long-press**
 * (#457): the horizontal swipe now changes the flag tab, and a row-level `SwipeToDismissBox`
 * would consume the horizontal drag first and steal the tab gesture. The long-press only
 * *raises* the existing confirmation dialog via [onRequestRemove] — the actual removal still
 * happens after the user confirms (the repository then evicts the item from the cache, which
 * recomposes the list away). Arbitrage XaTriX (#457): the swipe-to-remove may come back later
 * in another form.
 *
 * The long-press being invisible, the row keeps the TalkBack/switch-access `customActions`
 * entry (#99) in addition to the `onLongClickLabel` semantics. While a removal is in flight
 * ([removalInFlight]) the long-press is a no-op — the ViewModel also guards re-entry;
 * `Removing` is only the brief confirm→result window.
 */
@Composable
private fun RemovableFlagItem(
    flag: Flag,
    metadata: FlagMetadata,
    removalInFlight: Boolean,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val removeLabel = stringResource(R.string.flags_remove_action)

    FlagItem(
        flag = flag,
        metadata = metadata,
        onClick = onClick,
        longPress = FlagItemLongPress(label = removeLabel) {
            if (!removalInFlight) onRequestRemove()
        },
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(removeLabel) {
                        onRequestRemove()
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
private fun SuperPlaceholderBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.flags_super_placeholder_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.flags_super_placeholder_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun DtListBody(state: DtListUiState, isRefreshing: Boolean, actions: AuthenticatedActions) {
    // #546 directive XaTriX — pull-to-refresh (swipe down) on the DT list, like the flag tabs. Wraps
    // the whole body (every branch) so the indicator stays anchored over the existing content during
    // the refresh round-trip and the gesture has a target even on the listless states (#229 pattern).
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = actions.onRefreshDt,
        modifier = Modifier.fillMaxSize(),
    ) {
        DtListContent(state = state, actions = actions)
    }
}

/**
 * The DT body content under the [DtListBody] pull-to-refresh layer. Dispatches the [DtListUiState]
 * to its branch. The listless states ([DtListUiState.Loading]/[Empty]/[NoUnread]/[Error]) are made
 * vertically scrollable so the surrounding `PullToRefreshBox` keeps a target (#229).
 */
@Composable
private fun DtListContent(state: DtListUiState, actions: AuthenticatedActions) {
    when (state) {
        DtListUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        DtListUiState.Empty -> DtMessageBody(text = stringResource(R.string.flags_dt_empty))

        // #546 — the union is non-empty but « non-lus uniquement » hid every row: a dedicated message
        // (not the « aucune conversation » empty copy). Re-tapping the DT tab reveals « +lus ».
        DtListUiState.NoUnread -> DtNoUnreadBody()

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
            // Scan-note footer (#6): the list only covers inbox PAGE 1 + what the DT sync knows;
            // older inbox pages are deliberately not swept. A discreet, non-clickable trailing item.
            item(key = "dt-scan-note", contentType = CONTENT_TYPE_DT_FOOTER) {
                Text(
                    text = stringResource(R.string.flags_dt_scan_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
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
 * #546 directive XaTriX — body for the « filtered to no unread » state ([DtListUiState.NoUnread]):
 * the union holds conversations but none is unread. Shows a discreet « aucune conversation non lue »
 * message and keeps the scan-note footer visible (the user can re-tap the DT tab for « +lus »).
 * Scrollable so the surrounding `PullToRefreshBox` keeps a swipe target (#229).
 */
@Composable
private fun DtNoUnreadBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.flags_dt_no_unread),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.flags_dt_scan_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single-line message body (DT empty state), sharing the surface background. Vertically scrollable
 * so the surrounding [PullToRefreshBox] keeps a swipe target on this listless state (#229) — a
 * non-scrollable body would give the pull-to-refresh gesture nothing to anchor on.
 */
@Composable
private fun DtMessageBody(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
)

private data class AuthenticatedActions(
    val onSelectTab: (FlagTab) -> Unit,
    val onOpenFlag: (Flag) -> Unit,
    val onRefresh: () -> Unit,
    val onLoginRequested: () -> Unit,
    val onRequestRemoveFlag: (Flag) -> Unit,
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
