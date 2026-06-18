package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry

/**
 * #509 — « Utilisateurs masqués » sub-page: lists the blacklisted users, lets the reader add a pseudo
 * (text field + button) or remove one per row. The list is the source of truth for the masking applied
 * in topics ([fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository]). Local-only; no MPStorage
 * sync (#6 deferred).
 */
@Composable
fun SettingsBlacklistScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsBlacklistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsBlacklistContent(
        state = state,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

@Composable
internal fun SettingsBlacklistContent(
    state: SettingsBlacklistState,
    onIntent: (SettingsBlacklistIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_blacklist_title),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        // One LazyColumn for the whole page (intro + add form as header items) so the editable list
        // never nests inside a verticalScroll — and the keyboard inset shrinks the viewport so the add
        // field stays above the IME (same stance as SettingsProxyScreen).
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.ime)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_blacklist_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.newPseudo,
                        onValueChange = { onIntent(SettingsBlacklistIntent.PseudoChanged(it)) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_blacklist_add_label)) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onIntent(SettingsBlacklistIntent.AddClicked) },
                        enabled = state.canAdd,
                    ) {
                        Text(stringResource(R.string.settings_blacklist_add_button))
                    }
                }
            }
            if (state.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_blacklist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items = state.entries, key = { it.canonical }) { entry ->
                    BlacklistEntryRow(
                        entry = entry,
                        onRemove = { onIntent(SettingsBlacklistIntent.RemoveClicked(entry)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BlacklistEntryRow(entry: BlacklistEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.display,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.settings_blacklist_remove_button))
        }
    }
}
