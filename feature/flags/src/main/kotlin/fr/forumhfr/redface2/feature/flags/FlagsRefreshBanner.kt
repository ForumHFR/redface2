package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.model.FlagFailureKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val FlagsListUiState.Success.hasRefreshBanner: Boolean
    get() = refreshStatus == FlagRefreshStatus.PARTIAL || refreshStatus == FlagRefreshStatus.FAILED

/** Snapshot state, not a consumable effect: filters and Room reloads cannot dismiss this banner. */
@Composable
internal fun FlagsRefreshBanner(
    state: FlagsListUiState.Success,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    if (!state.hasRefreshBanner) return
    val failures = state.coverage.categories.filter { it.failure != null }
    val sessionExpired = failures.any { it.failure == FlagFailureKind.SESSION }
    val message = refreshBannerMessage(state, sessionExpired)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            state.refreshIssue?.takeIf {
                state.refreshStatus == FlagRefreshStatus.PARTIAL && !sessionExpired && it.stickyCategories.isNotEmpty()
            }?.let { issue ->
                val count = issue.stickyCategories.size
                // Room retains category IDs only; omit names until every label is available.
                val stickyMessage = if (issue.stickyCategories.any { it.name.isNullOrBlank() }) {
                    pluralStringResource(R.plurals.flags_missing_stickies_unnamed, count, count)
                } else {
                    pluralStringResource(R.plurals.flags_missing_stickies, count, issue.stickyCategoryLabels { "" })
                }
                Text(
                    stickyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = if (sessionExpired) onLogin else onRetry) {
                Text(stringResource(if (sessionExpired) R.string.flags_login_cta else R.string.flags_retry))
            }
        }
    }
}

@Composable
private fun refreshBannerMessage(state: FlagsListUiState.Success, sessionExpired: Boolean): String {
    val failures = state.coverage.categories.filter { it.failure != null }
    return when {
        sessionExpired -> stringResource(R.string.flags_session_expired)
        state.coverage.isGlobalObservation -> stringResource(R.string.flags_global_limited)
        failures.any { it.failure == FlagFailureKind.SCHEMA } -> stringResource(R.string.flags_invalid_response)
        state.refreshStatus == FlagRefreshStatus.FAILED -> {
            val time = state.cachedAt?.let {
                DateTimeFormatter.ofPattern("dd/MM à HH:mm").withZone(ZoneId.systemDefault()).format(it)
            }
            if (time == null) stringResource(R.string.flags_update_failed)
            else stringResource(R.string.flags_cached_update_failed, time)
        }
        else -> {
            val count = failures.map { it.cat }.distinct().size
            pluralStringResource(R.plurals.flags_partially_updated, count, count)
        }
    }
}
