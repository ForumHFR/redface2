package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem

/**
 * #494 — « Maintenance » sub-page. Extracts the cache actions (topics + #314 images), the
 * « Ignorer le cache topic » toggle and the two confirm dialogs from the former root catalogue, and
 * adds navigation rows to Diagnostics and (gated on [SettingsState.showDtSection]) the MPStorage
 * inspector. Binds its own [SettingsViewModel] instance (DataStore is the source of truth — same
 * trade-off documented on `SettingsProxyScreen`).
 */
@Composable
@Suppress("LongParameterList") // state-hoisted Composable: each nav callback has a distinct call-site.
fun SettingsMaintenanceScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMpStorageInspector: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_maintenance_title),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_clear_topic_cache_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isClearingTopicCache) {
                MaintenanceProgress(R.string.settings_clear_topic_cache_in_progress)
            }
            when (state.topicCacheClearResult) {
                TopicCacheClearResult.Success -> MaintenanceResult(
                    messageRes = R.string.settings_clear_topic_cache_success,
                    isError = false,
                )
                TopicCacheClearResult.Failure -> MaintenanceResult(
                    messageRes = R.string.settings_clear_topic_cache_failure,
                    isError = true,
                )
                null -> Unit
            }
            OutlinedButton(
                enabled = state.canClearTopicCache,
                onClick = { viewModel.submit(SettingsIntent.ClearTopicCacheClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_clear_topic_cache_button))
            }

            // #314 — « Vider le cache des images », same confirm → progress → inline-result flow.
            Text(
                text = stringResource(R.string.settings_clear_image_cache_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isClearingImageCache) {
                MaintenanceProgress(R.string.settings_clear_image_cache_in_progress)
            }
            when (state.imageCacheClearResult) {
                ImageCacheClearResult.Success -> MaintenanceResult(
                    messageRes = R.string.settings_clear_image_cache_success,
                    isError = false,
                )
                ImageCacheClearResult.Failure -> MaintenanceResult(
                    messageRes = R.string.settings_clear_image_cache_failure,
                    isError = true,
                )
                null -> Unit
            }
            OutlinedButton(
                enabled = state.canClearImageCache,
                onClick = { viewModel.submit(SettingsIntent.ClearImageCacheClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_clear_image_cache_button))
            }

            IgnoreTopicCacheRow(state = state, onIntent = viewModel::submit)

            HorizontalDivider()
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_about_diagnostics),
                description = stringResource(R.string.settings_about_diagnostics_description),
                onClick = onOpenDiagnostics,
                trailingContent = { ChevronTrailing() },
            )
            // #6 — read-only MPStorage inspector (debug). Gated on the DT section toggle.
            if (state.showDtSection) {
                RedfaceSettingsListItem(
                    title = stringResource(R.string.settings_mpstorage_inspector_title),
                    description = stringResource(R.string.settings_mpstorage_inspector_description),
                    onClick = onOpenMpStorageInspector,
                    trailingContent = { ChevronTrailing() },
                )
            }
        }
    }

    if (state.showClearTopicCacheConfirm) {
        MaintenanceConfirmDialog(
            titleRes = R.string.settings_clear_topic_cache_confirm_title,
            bodyRes = R.string.settings_clear_topic_cache_confirm_body,
            actionRes = R.string.settings_clear_topic_cache_confirm_action,
            cancelRes = R.string.settings_clear_topic_cache_confirm_cancel,
            onConfirm = { viewModel.submit(SettingsIntent.ClearTopicCacheConfirmed) },
            onDismiss = { viewModel.submit(SettingsIntent.ClearTopicCacheDismissed) },
        )
    }
    if (state.showClearImageCacheConfirm) {
        MaintenanceConfirmDialog(
            titleRes = R.string.settings_clear_image_cache_confirm_title,
            bodyRes = R.string.settings_clear_image_cache_confirm_body,
            actionRes = R.string.settings_clear_image_cache_confirm_action,
            cancelRes = R.string.settings_clear_image_cache_confirm_cancel,
            onConfirm = { viewModel.submit(SettingsIntent.ClearImageCacheConfirmed) },
            onDismiss = { viewModel.submit(SettingsIntent.ClearImageCacheDismissed) },
        )
    }
}

@Composable
private fun MaintenanceProgress(messageRes: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MaintenanceResult(messageRes: Int, isError: Boolean) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun IgnoreTopicCacheRow(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_ignore_topic_cache_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_ignore_topic_cache_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.ignoreTopicCache,
            enabled = state.canToggleIgnoreTopicCache,
            onCheckedChange = { onIntent(SettingsIntent.IgnoreTopicCacheChanged(it)) },
        )
    }
    if (state.ignoreTopicCacheError) {
        PreferencePersistError(R.string.settings_ignore_topic_cache_persist_failed)
    }
}

@Composable
@Suppress("LongParameterList") // generic confirm dialog: 4 string-res + 2 callbacks, all distinct.
private fun MaintenanceConfirmDialog(
    titleRes: Int,
    bodyRes: Int,
    actionRes: Int,
    cancelRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(actionRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(cancelRes)) }
        },
    )
}

/** Shared chevron trailing glyph for navigation rows (local drawable, material-icons forbidden). */
@Composable
internal fun ChevronTrailing() {
    Icon(
        painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
