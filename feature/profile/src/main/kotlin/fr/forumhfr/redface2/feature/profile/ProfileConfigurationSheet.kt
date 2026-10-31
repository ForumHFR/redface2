package fr.forumhfr.redface2.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A single 48 dp link, shared by the loaded card and full profile fields. */
@Composable
internal fun ProfileConfigurationLink(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text = stringResource(R.string.profile_configuration_title), maxLines = 1)
    }
}

/**
 * Composed after the host sheet. M3 1.4.0's ModalBottomSheetDialog creates and shows its own
 * ModalBottomSheetDialogWrapper (ComponentDialog), with a window and a local Back dispatcher.
 */
@Composable
internal fun ProfileConfigurationOverlay(state: ProfileUiState, onIntent: (ProfileIntent) -> Unit) {
    val configuration = state.configurationSheet ?: return
    val profile = (state.mode as? ProfileUiState.Mode.Loaded)?.profile ?: return
    ProfileConfigurationSheet(
        pseudo = profile.pseudo,
        configuration = configuration,
        onDismiss = { onIntent(ProfileIntent.DismissConfiguration) },
        onRetry = { onIntent(ProfileIntent.RetryConfiguration) },
    )
}

/** Selectable, scrollable content with the standard M3 handle and swipe-to-dismiss gestures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileConfigurationSheet(
    pseudo: String,
    configuration: ProfileUiState.ConfigurationSheet,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("profile_configuration_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.profile_configuration_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = pseudo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("profile_configuration_scroll")
                    .padding(vertical = 12.dp),
            ) {
                ConfigurationContent(configuration, onRetry)
            }
        }
    }
}

@Composable
private fun ConfigurationContent(configuration: ProfileUiState.ConfigurationSheet, onRetry: () -> Unit) {
    when (configuration) {
        ProfileUiState.ConfigurationSheet.Loading -> {
            val label = stringResource(R.string.profile_configuration_loading)
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).semantics { contentDescription = label },
            )
        }
        ProfileUiState.ConfigurationSheet.Error -> ConfigurationError(onRetry)
        is ProfileUiState.ConfigurationSheet.Loaded -> {
            if (configuration.lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_configuration_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                SelectionContainer {
                    Text(
                        text = configuration.lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigurationError(onRetry: () -> Unit) {
    val retryLabel = stringResource(R.string.profile_configuration_retry)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_configuration_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = retryLabel },
        ) {
            Text(stringResource(R.string.profile_action_retry))
        }
    }
}
