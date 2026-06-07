package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onIntent = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_proxy_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_proxy_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_proxy_enabled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = state.proxyEnabled,
                            onCheckedChange = { onIntent(SettingsIntent.ProxyEnabledChanged(it)) },
                        )
                    }
                    OutlinedTextField(
                        value = state.proxyHost,
                        onValueChange = { onIntent(SettingsIntent.ProxyHostChanged(it)) },
                        enabled = state.proxyEnabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_proxy_host)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.proxyPort,
                        onValueChange = { onIntent(SettingsIntent.ProxyPortChanged(it)) },
                        enabled = state.proxyEnabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.settings_proxy_port)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.proxyUsername,
                        onValueChange = { onIntent(SettingsIntent.ProxyUsernameChanged(it)) },
                        enabled = state.proxyEnabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_proxy_username)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.proxyPassword,
                        onValueChange = { onIntent(SettingsIntent.ProxyPasswordChanged(it)) },
                        enabled = state.proxyEnabled,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.settings_proxy_password)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_proxy_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.error == SettingsError.InvalidProxy) {
                        Text(
                            text = stringResource(R.string.settings_proxy_invalid),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.error == SettingsError.PersistFailed) {
                        Text(
                            text = stringResource(R.string.settings_proxy_persist_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.saved) {
                        Text(
                            text = stringResource(R.string.settings_proxy_saved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        enabled = state.canSave,
                        onClick = { onIntent(SettingsIntent.SaveProxyClicked) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_proxy_save))
                    }
                }
            }

            ThemePreferencesCard(
                state = state,
                onIntent = onIntent,
            )

            FlagsPreferencesCard(
                state = state,
                onIntent = onIntent,
            )

            MaintenanceCard(
                state = state,
                onIntent = onIntent,
            )
        }
    }

    if (state.showClearTopicCacheConfirm) {
        ClearTopicCacheConfirmDialog(
            onConfirm = { onIntent(SettingsIntent.ClearTopicCacheConfirmed) },
            onDismiss = { onIntent(SettingsIntent.ClearTopicCacheDismissed) },
        )
    }
}

/**
 * Theme preferences (#286): a 3-way Clair / Système / Sombre selector (SYSTEM follows the OS),
 * plus an AMOLED true-black toggle that only applies when the effective theme is dark. Persisted via
 * DataStore; the selection is observed at the app root ([fr.forumhfr.redface2.navigation.RedfaceApp])
 * so a change here re-themes the whole app live.
 */
@Composable
private fun ThemePreferencesCard(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    // The AMOLED toggle is only meaningful when the app will actually render dark — forced DARK, or
    // SYSTEM while the OS is in dark mode. Computed here so the switch is disabled otherwise.
    val effectiveDark = when (state.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val options = listOf(
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_theme_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        enabled = state.canChangeThemeMode,
                        onClick = { onIntent(SettingsIntent.ThemeModeChanged(mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(label)
                    }
                }
            }
            if (state.themeModeError) {
                PreferencePersistError(R.string.settings_theme_persist_failed)
            }
            PreferenceSwitchRow(
                title = stringResource(R.string.settings_theme_amoled_title),
                description = stringResource(R.string.settings_theme_amoled_description),
                checked = state.amoledEnabled,
                enabled = state.canToggleAmoled && effectiveDark,
                onCheckedChange = { onIntent(SettingsIntent.AmoledEnabledChanged(it)) },
            )
            if (state.amoledError) {
                PreferencePersistError(R.string.settings_theme_amoled_persist_failed)
            }
        }
    }
}

/**
 * Drapeaux display preferences (#179 follow-up): grouped-vs-flat layout and the « masquer les
 * catégories sans non-lu » filter. Persisted via DataStore and observed live by the Flags screen,
 * so a flip here re-renders the list without a refetch.
 */
@Composable
private fun FlagsPreferencesCard(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_flags_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PreferenceSwitchRow(
                title = stringResource(R.string.settings_flags_group_by_category_title),
                description = stringResource(R.string.settings_flags_group_by_category_description),
                checked = state.flagsGroupByCategory,
                enabled = state.canToggleFlagsGroupByCategory,
                onCheckedChange = { onIntent(SettingsIntent.FlagsGroupByCategoryChanged(it)) },
            )
            if (state.flagsGroupByCategoryError) {
                PreferencePersistError(R.string.settings_flags_group_by_category_persist_failed)
            }
            PreferenceSwitchRow(
                title = stringResource(R.string.settings_flags_hide_read_categories_title),
                description = stringResource(R.string.settings_flags_hide_read_categories_description),
                checked = state.flagsHideReadCategories,
                enabled = state.canToggleFlagsHideReadCategories,
                onCheckedChange = { onIntent(SettingsIntent.FlagsHideReadCategoriesChanged(it)) },
            )
            if (state.flagsHideReadCategoriesError) {
                PreferencePersistError(R.string.settings_flags_hide_read_categories_persist_failed)
            }
            PreferenceSwitchRow(
                title = stringResource(R.string.settings_flags_per_tab_override_title),
                description = stringResource(R.string.settings_flags_per_tab_override_description),
                checked = state.flagsPerTabOverride,
                enabled = state.canToggleFlagsPerTabOverride,
                onCheckedChange = { onIntent(SettingsIntent.FlagsPerTabOverrideChanged(it)) },
            )
            if (state.flagsPerTabOverrideError) {
                PreferencePersistError(R.string.settings_flags_per_tab_override_persist_failed)
            }
        }
    }
}

/**
 * One label + description + Material 3 [Switch] row. Generic so the two Drapeaux preference rows
 * share the layout; the persist-failure message is rendered by the caller via
 * [PreferencePersistError] so this stays a layout-only composable.
 */
@Composable
private fun PreferenceSwitchRow(
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
            modifier = Modifier.weight(1f),
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

/** Inline persist-failure message for a [PreferenceSwitchRow], shown below the row. */
@Composable
private fun PreferencePersistError(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun MaintenanceCard(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_maintenance_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_clear_topic_cache_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isClearingTopicCache) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.settings_clear_topic_cache_in_progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state.topicCacheClearResult) {
                TopicCacheClearResult.Success -> Text(
                    text = stringResource(R.string.settings_clear_topic_cache_success),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TopicCacheClearResult.Failure -> Text(
                    text = stringResource(R.string.settings_clear_topic_cache_failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            OutlinedButton(
                enabled = state.canClearTopicCache,
                onClick = { onIntent(SettingsIntent.ClearTopicCacheClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_clear_topic_cache_button))
            }

            IgnoreTopicCacheRow(
                state = state,
                onIntent = onIntent,
            )
        }
    }
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
        Text(
            text = stringResource(R.string.settings_ignore_topic_cache_persist_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ClearTopicCacheConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_topic_cache_confirm_title)) },
        text = { Text(stringResource(R.string.settings_clear_topic_cache_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_clear_topic_cache_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_clear_topic_cache_confirm_cancel))
            }
        },
    )
}
