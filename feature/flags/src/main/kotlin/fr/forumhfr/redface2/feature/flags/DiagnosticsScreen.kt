package fr.forumhfr.redface2.feature.flags

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Alpha-only in-app log viewer. Mirrors what lands in logcat without requiring `adb`.
 * Backed by [DiagnosticsLog] (200-entry ring buffer, in-memory only — process death
 * resets the trail by design).
 */
@Composable
fun DiagnosticsScreen(
    onClose: () -> Unit,
    versionName: String,
    versionCode: Int,
    channel: String,
) {
    DiagnosticsScreen(onClose, versionName, versionCode, channel, hiltViewModel())
}

@Composable
internal fun DiagnosticsScreen(
    onClose: () -> Unit,
    versionName: String,
    versionCode: Int,
    channel: String,
    viewModel: DiagnosticsViewModel,
) {
    val activity = LocalActivity.current
    DisposableEffect(viewModel, activity) {
        onDispose {
            // Same Nav3 lifecycle rule as ProfileRoute: rotation retains the active request.
            if (activity?.isChangingConfigurations != true) viewModel.cancelProbe()
        }
    }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val crashReportClear by viewModel.crashReportClear.collectAsStateWithLifecycle()
    val cooldownSeconds by viewModel.cooldownSeconds.collectAsStateWithLifecycle()
    val probeBlocked by viewModel.probeBlocked.collectAsStateWithLifecycle()
    val probeState by viewModel.probeState.collectAsStateWithLifecycle()
    val policy by viewModel.policy.collectAsStateWithLifecycle()
    var showPolicy by remember { mutableStateOf(false) }
    if (showPolicy) FlagsRefreshPolicyDialog(policy, viewModel.policySaveFailed,
        onDismiss = { showPolicy = false },
        onSave = { viewModel.savePolicy(it) { showPolicy = false } })
    val close = { viewModel.cancelProbe(); onClose() }
    BackHandler(onBack = close)
    val listState = rememberLazyListState()

    // Auto-scroll to the latest entry as soon as the buffer grows. Useful while a
    // login attempt is in flight so the tester sees the entries arrive live without
    // dragging the list.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_title, entries.size, DiagnosticsLog.CAPACITY),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            copyDiagnosticsToClipboard(
                                context, entries, timeFormat,
                                DiagnosticsEnvironment(
                                    versionName, versionCode, channel,
                                    Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL,
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.diagnostics_copy), softWrap = false) }
                    TextButton(onClick = { viewModel.clear() }) {
                        Text(stringResource(R.string.diagnostics_clear), softWrap = false)
                    }
                    TextButton(onClick = close) { Text(stringResource(R.string.diagnostics_close), softWrap = false) }
                }
            }
            Text(
                text = stringResource(R.string.diagnostics_copy_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            CrashReportClearStatus(crashReportClear.status, viewModel::retryCrashReportClear)
            NetworkProbePanel(
                probeState, cooldownSeconds, viewModel::startProbe, viewModel::cancelProbe,
                hfrBlocked = probeBlocked,
            )
            TextButton(
                onClick = { showPolicy = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.diagnostics_flags_experimental))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        DiagnosticsRow(entry = entry, timestampLabel = timeFormat.format(Date(entry.timestampMillis)))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/** Tells whether the retained crash report really left the device, and offers a retry if not. */
@Composable
private fun CrashReportClearStatus(state: DiagnosticsLog.CrashReportClear, onRetry: () -> Unit) {
    val message = when (state) {
        DiagnosticsLog.CrashReportClear.IDLE -> return
        DiagnosticsLog.CrashReportClear.CLEARING -> R.string.diagnostics_clear_pending
        DiagnosticsLog.CrashReportClear.CLEARED -> R.string.diagnostics_clear_done
        DiagnosticsLog.CrashReportClear.FAILED -> R.string.diagnostics_clear_failed
    }
    val failed = state == DiagnosticsLog.CrashReportClear.FAILED
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        if (failed) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.diagnostics_clear_retry), softWrap = false)
            }
        }
    }
}

/**
 * Copy the allowed diagnostics to the clipboard as plain text. Each entry starts with
 * `HH:mm:ss.SSS  L  TAG  message`. A crash entry can span multiple lines for its stack trace.
 *
 * Android 13+ shows the system "copied to clipboard" overlay automatically; on older
 * devices we surface a Toast so the tester gets immediate feedback.
 */
private fun copyDiagnosticsToClipboard(
    context: Context,
    entries: List<DiagnosticsLog.Entry>,
    timeFormat: SimpleDateFormat,
    environment: DiagnosticsEnvironment,
) {
    val payload = formatDiagnosticsExport(entries, timeFormat, environment)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 diagnostics", payload))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.diagnostics_network_copied), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DiagnosticsRow(entry: DiagnosticsLog.Entry, timestampLabel: String) {
    val (levelLabel, levelColor) = when (entry.level) {
        DiagnosticsLog.Level.INFO -> "I" to MaterialTheme.colorScheme.primary
        DiagnosticsLog.Level.DEBUG -> "D" to MaterialTheme.colorScheme.secondary
        DiagnosticsLog.Level.WARN -> "W" to MaterialTheme.colorScheme.tertiary
        DiagnosticsLog.Level.ERROR -> "E" to MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = levelLabel,
                color = levelColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = timestampLabel,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = entry.tag,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
