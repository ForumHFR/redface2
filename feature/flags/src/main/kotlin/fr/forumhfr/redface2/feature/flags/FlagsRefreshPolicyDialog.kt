package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/** Tester-only settings; the main Settings screen deliberately exposes no refresh strategy. */
@Composable
internal fun FlagsRefreshPolicyDialog(
    policy: FlagsRefreshPolicy,
    saveFailed: Boolean,
    onDismiss: () -> Unit,
    onSave: (FlagsRefreshPolicy) -> Unit,
) {
    var draft by remember(policy) { mutableStateOf(policy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_flags_experimental_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.diagnostics_flags_experimental_help))
                Column(Modifier.selectableGroup()) {
                    FlagsRefreshStrategy.entries.forEach { strategy ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = draft.strategy == strategy,
                                    role = Role.RadioButton,
                                    onClick = { draft = draft.copy(strategy = strategy) },
                                )
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = draft.strategy == strategy, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(strategy.labelResource()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(strategy.descriptionResource()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                FlagsConcurrencySetting(draft, onChange = { draft = draft.copy(concurrency = it) })
                val retryLabel = stringResource(R.string.diagnostics_flags_retry)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(retryLabel, Modifier.weight(1f))
                    Switch(checked = draft.applicationRetry,
                        modifier = Modifier.semantics { contentDescription = retryLabel },
                        onCheckedChange = { draft = draft.copy(applicationRetry = it) })
                }
                if (saveFailed) Text(stringResource(R.string.diagnostics_flags_save_failed),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) {
            Text(stringResource(R.string.diagnostics_flags_apply))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diagnostics_flags_cancel)) } },
    )
}

@Composable
private fun FlagsConcurrencySetting(policy: FlagsRefreshPolicy, onChange: (Int) -> Unit) {
    val enabled = policy.effectiveConcurrency != null
    val decreaseLabel = stringResource(R.string.diagnostics_flags_decrease)
    val increaseLabel = stringResource(R.string.diagnostics_flags_increase)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = enabled && policy.concurrency > FlagsRefreshPolicy.MIN_CONCURRENCY,
                onClick = { onChange(policy.concurrency - 1) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = decreaseLabel },
            ) {
                RedfaceVectorIcon(CoreUiR.drawable.ic_remove)
            }
            Text(
                stringResource(R.string.diagnostics_flags_n, policy.concurrency),
                modifier = Modifier.weight(1f).semantics { if (!enabled) disabled() },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            IconButton(
                enabled = enabled && policy.concurrency < FlagsRefreshPolicy.MAX_CONCURRENCY,
                onClick = { onChange(policy.concurrency + 1) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = increaseLabel },
            ) {
                RedfaceVectorIcon(CoreUiR.drawable.ic_add)
            }
        }
        val effective = policy.effectiveConcurrency
        Text(if (effective == null) stringResource(R.string.diagnostics_flags_unbounded)
            else stringResource(R.string.diagnostics_flags_effective_n, effective))
        if (!enabled) {
            Text(
                stringResource(R.string.diagnostics_flags_n_ignored),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun FlagsRefreshStrategy.labelResource(): Int = when (this) {
    FlagsRefreshStrategy.BASELINE -> R.string.diagnostics_flags_baseline
    FlagsRefreshStrategy.LEGACY -> R.string.diagnostics_flags_legacy
    FlagsRefreshStrategy.GLOBAL_ONLY -> R.string.diagnostics_flags_global_only
    FlagsRefreshStrategy.GLOBAL_FIRST -> R.string.diagnostics_flags_global_first
}

private fun FlagsRefreshStrategy.descriptionResource(): Int = when (this) {
    FlagsRefreshStrategy.BASELINE -> R.string.diagnostics_flags_baseline_help
    FlagsRefreshStrategy.LEGACY -> R.string.diagnostics_flags_legacy_help
    FlagsRefreshStrategy.GLOBAL_ONLY -> R.string.diagnostics_flags_global_only_help
    FlagsRefreshStrategy.GLOBAL_FIRST -> R.string.diagnostics_flags_global_first_help
}
