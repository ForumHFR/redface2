package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

/**
 * #494 — « Proxy » sub-page. Extracts the proxy form verbatim from the former root catalogue (same
 * intents, same errors, same Save gate). Each settings sub-page binds its own [SettingsViewModel]
 * instance (a distinct nav entry = a distinct `ViewModelStore`), which is sound because DataStore is
 * the single source of truth and every screen rehydrates from its `Flow` (same trade-off documented
 * on `ProfileFullRoute`). This is the only settings screen with focusable text fields, so it KEEPS
 * the sophisticated keyboard inset (ime ∪ navigationBars) that #624 relies on.
 */
@Composable
fun SettingsProxyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_proxy_title),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Applied so the keyboard inset shrinks the scroll viewport: a focused proxy field
                // then stays above the IME (cf. #624). union() keeps navBar clearance when closed.
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.ime)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    onCheckedChange = { viewModel.submit(SettingsIntent.ProxyEnabledChanged(it)) },
                )
            }
            OutlinedTextField(
                value = state.proxyHost,
                onValueChange = { viewModel.submit(SettingsIntent.ProxyHostChanged(it)) },
                enabled = state.proxyEnabled,
                singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_host)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.proxyPort,
                onValueChange = { viewModel.submit(SettingsIntent.ProxyPortChanged(it)) },
                enabled = state.proxyEnabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.settings_proxy_port)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.proxyUsername,
                onValueChange = { viewModel.submit(SettingsIntent.ProxyUsernameChanged(it)) },
                enabled = state.proxyEnabled,
                singleLine = true,
                label = { Text(stringResource(R.string.settings_proxy_username)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.proxyPassword,
                onValueChange = { viewModel.submit(SettingsIntent.ProxyPasswordChanged(it)) },
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
                onClick = { viewModel.submit(SettingsIntent.SaveProxyClicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_proxy_save))
            }
        }
    }
}
