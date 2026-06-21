package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull

/**
 * #612 — « Participants » bottom sheet for a DT / MultiMP conversation. The full member list is
 * sourced from the owner-only `newdest` of the message.php reply form (the only place HFR exposes
 * it); the screen's [PrivateMessageThreadViewModel] fetches it lazily on open and the sheet only
 * renders the resolved [PrivateMessageThreadUiState.Roster] state.
 *
 * Scrollable by design (a DT can carry 40+ members): the list is a height-capped [LazyColumn], not
 * inline content. A non-owner (`Unavailable`) gets a sober note rather than a misleading partial
 * list of the authors who happen to have posted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParticipantRosterSheet(
    roster: PrivateMessageThreadUiState.Roster,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    // #618 — owner-only « Gérer les destinataires » entry. Closes the sheet then navigates to the
    // reply composer with the recipient-manager sheet auto-opened. Null = entry not wired (defensive).
    onManageRecipients: () -> Unit = {},
) {
    // Hidden = the sheet is closed; render nothing.
    if (roster is PrivateMessageThreadUiState.Roster.Hidden) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.messages_roster_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            when (roster) {
                // Already handled by the early return, but the `when` must be exhaustive.
                PrivateMessageThreadUiState.Roster.Hidden -> Unit
                PrivateMessageThreadUiState.Roster.Loading -> RosterLoading()
                is PrivateMessageThreadUiState.Roster.Loaded -> RosterList(
                    members = roster.members,
                    canManageRecipients = roster.canManageRecipients,
                    onManageRecipients = onManageRecipients,
                )
                PrivateMessageThreadUiState.Roster.Unavailable -> RosterUnavailable()
                is PrivateMessageThreadUiState.Roster.Error -> RosterError(roster = roster, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun RosterLoading() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.messages_roster_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RosterList(
    members: List<String>,
    canManageRecipients: Boolean,
    onManageRecipients: () -> Unit,
) {
    Text(
        text = pluralStringResource(R.plurals.messages_roster_count, members.size, members.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Height-capped + scrollable: a DT can hold 40+ members, never inline them all.
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(members, key = { it }) { pseudo ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // No avatar URL available off newdest (just the pseudo) — RedfaceUserAvatar renders
                // its initial-based placeholder, consistent with the rest of the app.
                RedfaceUserAvatar(avatarUrl = null, author = pseudo, size = 36.dp)
                Text(
                    text = pseudo,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // #618 — owner-only entry to the member editor. A participant reads the roster but cannot edit it
    // (HFR mutates members only via an owner reply), so the button is gated on canManageRecipients.
    if (canManageRecipients) {
        Button(onClick = onManageRecipients, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.messages_roster_manage))
        }
    }
}

@Composable
private fun RosterUnavailable() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.messages_roster_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.messages_roster_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RosterError(roster: PrivateMessageThreadUiState.Roster.Error, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            // #324 — the kind is a type-derived closed enum (safe per #316); ServerDown / Network
            // render the shared :core:ui label, Other keeps the generic roster message.
            text = stringResource(roster.kind.sharedLabelResOrNull() ?: R.string.messages_roster_error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.messages_retry))
        }
    }
}
