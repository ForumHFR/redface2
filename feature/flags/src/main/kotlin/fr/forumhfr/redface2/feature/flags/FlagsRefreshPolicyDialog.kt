package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.semantics
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshStrategy

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
        title = { Text(stringResource(R.string.diagnostics_flags_experimental)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.diagnostics_flags_experimental_help))
                Column(Modifier.selectableGroup()) {
                    FlagsRefreshStrategy.entries.forEach { strategy ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = draft.strategy == strategy,
                                role = Role.RadioButton,
                                onClick = { draft = draft.copy(strategy = strategy) },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = draft.strategy == strategy, onClick = null)
                            Text(stringResource(strategy.labelResource()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(stringResource(draft.strategy.descriptionResource()), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = draft.concurrency > FlagsRefreshPolicy.MIN_CONCURRENCY,
                        onClick = { draft = draft.copy(concurrency = draft.concurrency - 1) }) {
                        Text(stringResource(R.string.diagnostics_flags_decrease))
                    }
                    Text(stringResource(R.string.diagnostics_flags_n, draft.concurrency))
                    TextButton(enabled = draft.concurrency < FlagsRefreshPolicy.MAX_CONCURRENCY,
                        onClick = { draft = draft.copy(concurrency = draft.concurrency + 1) }) {
                        Text(stringResource(R.string.diagnostics_flags_increase))
                    }
                }
                val effective = draft.effectiveConcurrency
                Text(if (effective == null) stringResource(R.string.diagnostics_flags_unbounded)
                    else stringResource(R.string.diagnostics_flags_effective_n, effective))
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
