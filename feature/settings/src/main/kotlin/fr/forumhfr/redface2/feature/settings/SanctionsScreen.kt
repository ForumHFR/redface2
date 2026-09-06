package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.profile.Sanction
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull

@Composable
fun SanctionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SanctionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SanctionsContent(
        state = state,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

@Composable
internal fun SanctionsContent(
    state: SanctionsUiState,
    onIntent: (SanctionsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val pseudo = when (state) {
        is SanctionsUiState.Empty -> state.pseudo
        is SanctionsUiState.Loaded -> state.pseudo
        else -> null
    }
    RedfaceSettingsScaffold(
        title = stringResource(R.string.sanctions_title),
        subtitle = pseudo,
        onBack = onBack,
        modifier = modifier,
        topBarActions = topBarActions,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
            when (state) {
                SanctionsUiState.Loading -> SanctionsProgress()
                SanctionsUiState.SignInRequired ->
                    SanctionsMessageContent(stringResource(R.string.sanctions_sign_in_required))
                is SanctionsUiState.Empty -> SanctionsMessageContent(stringResource(R.string.sanctions_empty))
                is SanctionsUiState.Loaded -> SanctionsList(state.sanctions)
                is SanctionsUiState.Error -> SanctionsErrorContent(state.kind, onIntent)
            }
        }
    }
}

@Composable
private fun SanctionsProgress() {
    val label = stringResource(R.string.sanctions_loading)
    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
}

@Composable
private fun SanctionsMessageContent(text: String) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SanctionsErrorContent(kind: HfrErrorKind, onIntent: (SanctionsIntent) -> Unit) {
    val message = kind.sharedLabelResOrNull() ?: R.string.sanctions_error
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onIntent(SanctionsIntent.Retry) }) {
            Text(stringResource(R.string.sanctions_retry))
        }
    }
}

@Composable
private fun SanctionsList(sanctions: List<Sanction>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // HFR exposes no sanction identifier; positional identity also accepts identical rows.
        items(sanctions) { sanction -> SanctionCard(sanction) }
    }
}

@Composable
private fun SanctionCard(sanction: Sanction) {
    Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sanction.kind,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                SanctionStatus(ongoing = sanction.liftedAt == null)
            }
            Text(
                stringResource(R.string.sanctions_category, sanction.category),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(sanctionPeriod(sanction), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.sanctions_moderator, sanction.moderator),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sanction.reason.isNotBlank()) {
                Text(sanction.reason, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SanctionStatus(ongoing: Boolean) {
    Text(
        text = stringResource(if (ongoing) R.string.sanctions_ongoing else R.string.sanctions_lifted),
        style = MaterialTheme.typography.labelLarge,
        color = if (ongoing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun sanctionPeriod(sanction: Sanction): String = sanction.liftedAt?.let { liftedAt ->
    stringResource(R.string.sanctions_period, sanction.issuedAt, liftedAt)
} ?: stringResource(R.string.sanctions_since, sanction.issuedAt)
