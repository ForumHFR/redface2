package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeState
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeStatus

@Composable
internal fun NetworkProbePanel(
    state: NetworkProbeState,
    cooldownSeconds: Long?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.diagnostics_probe_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onStart, enabled = !state.running && cooldownSeconds == 0L) {
                Text(stringResource(R.string.diagnostics_probe_start))
            }
            if (state.running) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.diagnostics_probe_cancel)) }
            }
        }
        if (state.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.diagnostics_probe_running, state.waveSize ?: 1),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (cooldownSeconds != null && cooldownSeconds > 0) {
            Text(
                stringResource(R.string.diagnostics_probe_cooldown, cooldownSeconds),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ProbeStatus(state.status)
        state.waves.forEach { wave ->
            val median = wave.medianConnectionMs?.let { stringResource(R.string.diagnostics_probe_milliseconds, it) }
                ?: stringResource(R.string.diagnostics_probe_no_measurement)
            Text(
                stringResource(R.string.diagnostics_probe_wave, wave.size, wave.successes, wave.failures, median),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.disturbed) {
            Text(
                stringResource(R.string.diagnostics_probe_disturbed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ProbeStatus(status: NetworkProbeStatus) {
    val message = when (status) {
        NetworkProbeStatus.COMPLETE -> R.string.diagnostics_probe_complete
        NetworkProbeStatus.CANCELLED -> R.string.diagnostics_probe_cancelled
        NetworkProbeStatus.REFRESH_ACTIVE -> R.string.diagnostics_probe_refresh_active
        NetworkProbeStatus.STORAGE_ERROR -> R.string.diagnostics_probe_storage_error
        NetworkProbeStatus.FAILED -> R.string.diagnostics_probe_failed
        else -> null
    }
    if (message != null) Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
}
