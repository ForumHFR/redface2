package fr.forumhfr.redface2.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared by the sheet and the full page; loading and empty responses take no space. */
@Composable
internal fun ProfileConfigurationSection(
    configuration: ProfileUiState.Configuration,
    onRetry: () -> Unit,
    collapsed: Boolean = false,
) {
    // Most members have no configuration: do not grow and shrink the card for an empty response.
    if (configuration is ProfileUiState.Configuration.Loading) return
    if (configuration is ProfileUiState.Configuration.Loaded && configuration.lines.isEmpty()) return
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.profile_configuration_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        when (configuration) {
            ProfileUiState.Configuration.Loading -> Unit
            ProfileUiState.Configuration.Error -> ConfigurationError(onRetry)
            is ProfileUiState.Configuration.Loaded -> ConfigurationText(configuration.lines, collapsed)
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
            style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun ConfigurationText(lines: List<String>, collapsed: Boolean) {
    var expanded by rememberSaveable(lines) { mutableStateOf(false) }
    var overflows by remember(lines) { mutableStateOf(false) }
    val isFolded = collapsed && !expanded
    // Four rendered lines bound the preview even when a single hardware reference wraps.
    // The configuration follows the actions; expanding it scrolls with the rest of the card.
    SelectionContainer {
        Text(
            text = lines.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = if (isFolded) CONFIGURATION_PREVIEW_LINES else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (isFolded) overflows = it.hasVisualOverflow },
        )
    }
    if (isFolded && (lines.size > CONFIGURATION_PREVIEW_LINES || overflows)) {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.profile_configuration_show_all))
        }
    }
}

private const val CONFIGURATION_PREVIEW_LINES = 4
