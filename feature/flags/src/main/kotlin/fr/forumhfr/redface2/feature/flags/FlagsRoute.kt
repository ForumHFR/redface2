package fr.forumhfr.redface2.feature.flags

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.ui.FlagItem
import fr.forumhfr.redface2.core.ui.FlagItemDivider
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
 * - Flag removal (#99) is now a Material 3 `SwipeToDismissBox` (swipe end-to-start) instead
 *   of a trailing « Retirer » button. The swipe only *opens* the existing confirmation dialog
 *   ([requestRemoveFlag]) — it never dismisses the row on its own. See [SwipeableFlagItem] for
 *   the « confirm before network » detail (the row is reset to settled, so it stays in place
 *   until the user confirms and the repository evicts it from the cache).
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
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val viewModel: FlagsViewModel = hiltViewModel()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val flagsState by viewModel.flagsState.collectAsStateWithLifecycle()
    val showReadParticipated by viewModel.showReadParticipatedTopics.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val removeFlagState by viewModel.removeFlagState.collectAsStateWithLifecycle()
    val removeFlagEvent by viewModel.removeFlagEvent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

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
                FlagsHeader(topBarActions = topBarActions)

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
                                showReadParticipated = showReadParticipated,
                                isRefreshing = isRefreshing,
                                removeFlagState = removeFlagState,
                            ),
                            actions = AuthenticatedActions(
                                onSelectTab = viewModel::selectTab,
                                onOpenFlag = onOpenFlag,
                                onRefresh = viewModel::refresh,
                                onLoginRequested = onLoginRequested,
                                onRequestRemoveFlag = viewModel::requestRemoveFlag,
                            ),
                        )
                    }
                }
            }
        }
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

@Composable
private fun FlagsHeader(
    topBarActions: @Composable (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.flags_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Refresh moved to a PullToRefreshBox (swipe down) on the list — the header now only
        // carries the global account menu slot.
        topBarActions?.invoke()
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
) {
    val selectedTab = state.selectedTab
    val showReadParticipated = state.showReadParticipated
    val tabs = listOf(
        FlagTab.Cyan to stringResource(R.string.flags_tab_my_topics),
        FlagTab.Red to stringResource(R.string.flags_tab_read_only),
        FlagTab.Favorite to stringResource(R.string.flags_tab_favorite),
        FlagTab.Super to stringResource(R.string.flags_tab_super),
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)
    // Discreet « +lus » suffix on the Cyan label so the user knows read participated topics
    // are currently shown — re-tapping the (already selected) Cyan tab toggles it.
    val cyanReadSuffix = stringResource(R.string.flags_tab_cyan_read_shown_suffix)

    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { index, (tab, label) ->
            val displayLabel = if (tab == FlagTab.Cyan && showReadParticipated) {
                label + cyanReadSuffix
            } else {
                label
            }
            Tab(
                selected = index == selectedIndex,
                onClick = { actions.onSelectTab(tab) },
                text = { Text(displayLabel, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }

    if (selectedTab == FlagTab.Super) {
        // Placeholder — no backend, no fetch, no pull-to-refresh (cf. FlagTab.Super KDoc).
        SuperPlaceholderBody()
        return
    }

    // Pull-to-refresh (swipe down) replaces the legacy header « Actualiser » button, matching
    // feature/forum. It wraps the whole flag body so the indicator stays anchored over the
    // existing content during the refresh round-trip (Material 3 stable, cf. Context7).
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxWidth()
            // Without weight(1f) the box would not claim the remaining vertical space inside
            // the parent Column, breaking the swipe gesture area on short lists.
            .weight(1f),
    ) {
        when (val current = state.flagsState) {
            null, FlagsResult.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is FlagsResult.Failure -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val sessionExpired = current.cause is SessionExpiredException
                Text(
                    text = stringResource(
                        if (sessionExpired) R.string.flags_session_expired else R.string.flags_error,
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

            is FlagsResult.Success -> {
                if (current.flags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.flags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(0.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        // Compose `key` rejects duplicates with IllegalArgumentException, but
                        // HFR's `post=` topic id is only guaranteed unique within a category
                        // (cf. AGENTS.md), not globally. Using "cat-topicId" eliminates the
                        // latent crash if the listing ever returns the same topicId in two cats.
                        items(items = current.flags, key = { "${it.cat}-${it.topicId}" }) { flag ->
                            // Anti double-tap (#99): while a removal is in flight, swipe is
                            // disabled across the list. `removeFlagState` is Removing only between
                            // confirm and the repository result (a brief window); the modal dialog
                            // already blocks the Confirming phase and the ViewModel rejects re-entry.
                            val removalInFlight = state.removeFlagState is RemoveFlagState.Removing
                            SwipeableFlagItem(
                                flag = flag,
                                metadata = flagMetadata(flag),
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
    }
}

/**
 * One swipeable flag row (#99). Wraps [FlagItem] in a Material 3 [SwipeToDismissBox] so a swipe
 * **end-to-start** raises the existing confirmation dialog via [onRequestRemove].
 *
 * Crucial « confirm before network » detail: the swipe must **not** dismiss the row on its own.
 * The actual removal only happens after the user confirms in the dialog (the repository then
 * evicts the item from the cache, which recomposes the list away). So from the non-deprecated
 * [SwipeToDismissBox] `onDismiss` callback we both fire [onRequestRemove] **and** immediately
 * `reset()` the box back to [SwipeToDismissBoxValue.Settled] — the row snaps back whether the user
 * confirms or cancels. This is the canonical Material 3 stable pattern (cf. Context7
 * `SwipeToDismissBox` sample).
 *
 * Resetting from a separate `LaunchedEffect` keyed on `currentValue` looks equivalent but is
 * racy: `reset()` = `animateTo(Settled)` flips `currentValue` early, re-keying the effect and
 * cancelling the reset mid-animation, which leaves the row **stuck at the dismissed offset** once
 * the dialog grabs focus (reproduced on device). Resetting inside `onDismiss` avoids the race.
 *
 * We do **not** use the `confirmValueChange` veto overload of [rememberSwipeToDismissBoxState]:
 * it is deprecated in the locked Material 3 (BOM 2026.04.01) — the verified message is
 * « confirmValueChange is deprecated without replacement ». `onDismiss` + `reset()` is the
 * supported equivalent (cf. Context7 + the locked material3 classes.jar).
 *
 * Only swipe-to-start is enabled ([enableDismissFromStartToEnd] = false): a single, predictable
 * gesture, with no accidental removal on a start-to-end pan. The swipe is also the *only* removal
 * affordance, so the row carries a TalkBack/switch-access `customActions` entry mirroring it.
 * While a removal is in flight ([removalInFlight]), gestures are disabled across the list (the
 * ViewModel also guards re-entry) — `Removing` is only the brief confirm→result window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableFlagItem(
    flag: Flag,
    metadata: String,
    removalInFlight: Boolean,
    onClick: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val removeLabel = stringResource(R.string.flags_remove_action)

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        gesturesEnabled = !removalInFlight,
        // The swipe only *triggers* the confirmation — it must never dismiss the row itself.
        // Raise the dialog and snap the box back from the SAME callback (canonical M3 stable
        // pattern). Doing the reset() here instead of a LaunchedEffect(currentValue) avoids the
        // re-key race that otherwise leaves the row stuck at the dismissed offset.
        onDismiss = {
            onRequestRemove()
            scope.launch { dismissState.reset() }
        },
        backgroundContent = { SwipeRemoveBackground() },
    ) {
        FlagItem(
            flag = flag,
            metadata = metadata,
            onClick = onClick,
            // Opaque background so the destructive backdrop never bleeds through the row while
            // it is animating back to settled. Swipe is the only removal affordance now, so we
            // also expose a TalkBack/switch-access custom action mirroring it.
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
}

/**
 * Destructive M3 backdrop revealed under a flag row while swiping end-to-start (#99). Uses
 * `errorContainer` / `onErrorContainer` from the theme — no hardcoded color — and a text label
 * (« Retirer ») rather than a Material Icons glyph, because the icons-extended dependency is not
 * on the classpath in this module.
 */
@Composable
private fun SwipeRemoveBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = stringResource(R.string.flags_remove_action),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Sober M3 placeholder for the future « super favoris » [FlagTab.Super] tab. No list, no
 * network call — just an explanatory message until the feature ships.
 */
@Composable
private fun ColumnScope.SuperPlaceholderBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
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
 * Read-only state bundle for [AuthenticatedBody], grouped so the composable stays under the
 * detekt parameter-count threshold and mirrors the [AuthenticatedActions] callback bundle.
 */
private data class FlagsBodyState(
    val selectedTab: FlagTab,
    val flagsState: FlagsResult?,
    val showReadParticipated: Boolean,
    val isRefreshing: Boolean,
    val removeFlagState: RemoveFlagState,
)

private data class AuthenticatedActions(
    val onSelectTab: (FlagTab) -> Unit,
    val onOpenFlag: (Flag) -> Unit,
    val onRefresh: () -> Unit,
    val onLoginRequested: () -> Unit,
    val onRequestRemoveFlag: (Flag) -> Unit,
)

@Composable
private fun flagMetadata(flag: Flag): String =
    if (flag.lastReplyAuthor.isNotBlank()) {
        stringResource(
            R.string.flags_item_metadata_with_author,
            flag.lastReplyAuthor,
            flag.replyCount,
            flag.lastReadPage,
            flag.totalPages,
        )
    } else {
        stringResource(
            R.string.flags_item_metadata_no_author,
            flag.replyCount,
            flag.lastReadPage,
            flag.totalPages,
        )
    }
