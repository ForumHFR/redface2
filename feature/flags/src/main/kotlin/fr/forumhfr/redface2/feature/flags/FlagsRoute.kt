package fr.forumhfr.redface2.feature.flags

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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

/**
 * Home tab entry point.
 *
 * Phase 2 finish UI polish (#198 / #199):
 * - The "compte / outils alpha" footer that lived in [feature/messages] now sits in a
 *   global account menu surfaced via [topBarActions] in the header row. Each main screen
 *   accepts the same slot so the affordance is consistent across Drapeaux, Forum,
 *   Recherche and Messages.
 * - The CYAN « Afficher les drapeaux cyans déjà lus » toggle is now a compact `FilterChip`
 *   under the tab row (only on CYAN), freeing vertical space for the list.
 * - The « Actualiser » button is no longer a permanent full-width button at the end of
 *   the success state — it lives in the header as a compact text button. The error state
 *   still surfaces a `Retry` affordance because it's a recovery action, not a permanent
 *   secondary control.
 */
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
            FlagsHeader(
                onRefresh = viewModel::refresh,
                refreshEnabled = authState is AuthState.Authenticated,
                topBarActions = topBarActions,
            )

            // Render nothing while authState is null (cookie jar warming up). Same
            // anti-flicker convention as PR #91; defaulting to "Anonymous" here would
            // bring the cold-start "Se connecter" flash back.
            authState?.let { state ->
                when (state) {
                    AuthState.Anonymous -> AnonymousBody(onLoginRequested)
                    is AuthState.Authenticated -> AuthenticatedBody(
                        selectedTab = selectedTab,
                        flagsState = flagsState,
                        showReadParticipated = showReadParticipated,
                        actions = AuthenticatedActions(
                            onSelectTab = viewModel::selectTab,
                            onOpenFlag = onOpenFlag,
                            onRefresh = viewModel::refresh,
                            onLoginRequested = onLoginRequested,
                            onToggleShowReadParticipated = viewModel::setShowReadParticipatedTopics,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlagsHeader(
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (refreshEnabled) {
                // Compact text button — replaces the legacy full-width "Actualiser" at the
                // end of the list in success state. Refresh stays discoverable but no
                // longer eats a row of vertical space (#199).
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.flags_refresh))
                }
            }
            topBarActions?.invoke()
        }
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

@Composable
private fun ColumnScope.AuthenticatedBody(
    selectedTab: FlagType,
    flagsState: FlagsResult?,
    showReadParticipated: Boolean,
    actions: AuthenticatedActions,
) {
    val tabs = listOf(
        FlagType.CYAN to stringResource(R.string.flags_tab_my_topics),
        FlagType.RED to stringResource(R.string.flags_tab_read_only),
        FlagType.FAVORITE to stringResource(R.string.flags_tab_favorite),
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)

    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { index, (type, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { actions.onSelectTab(type) },
                text = { Text(label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }

    if (selectedTab == FlagType.CYAN) {
        // #199 — compact FilterChip under the tab row, only on the CYAN tab where the
        // « stale read participated » polluation is meaningful. RED and FAVORITE do not
        // need the toggle (cf. FlagsViewModel `filterReadParticipatedIfNeeded` KDoc).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showReadParticipated,
                onClick = { actions.onToggleShowReadParticipated(!showReadParticipated) },
                label = { Text(stringResource(R.string.flags_show_read_participated_chip)) },
            )
        }
    }

    when (val current = flagsState) {
        null, FlagsResult.Loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is FlagsResult.Failure -> Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        .fillMaxWidth()
                        // Without weight(1f), this LazyColumn would consume all remaining
                        // vertical space inside the parent Column (FlagsRoute) and push any
                        // sibling rendered after it off-screen — reproducible on long lists
                        // (e.g. the cyan tab on the captured fixture, 127 rows). Weight is
                        // the canonical Compose pattern for "header + scrollable list" inside
                        // a Column with extra trailing content.
                        .weight(1f)
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    // Compose `key` rejects duplicates with IllegalArgumentException, but
                    // HFR's `post=` topic id is only guaranteed unique within a category
                    // (cf. AGENTS.md), not globally. Using "cat-topicId" eliminates the
                    // latent crash if the listing ever returns the same topicId in two cats.
                    items(items = current.flags, key = { "${it.cat}-${it.topicId}" }) { flag ->
                        FlagItem(
                            flag = flag,
                            metadata = flagMetadata(flag),
                            onClick = { actions.onOpenFlag(flag) },
                        )
                        FlagItemDivider()
                    }
                }
            }
        }
    }
}

private data class AuthenticatedActions(
    val onSelectTab: (FlagType) -> Unit,
    val onOpenFlag: (Flag) -> Unit,
    val onRefresh: () -> Unit,
    val onLoginRequested: () -> Unit,
    val onToggleShowReadParticipated: (Boolean) -> Unit,
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
