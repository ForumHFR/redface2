package fr.forumhfr.redface2.feature.flags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: DiagnosticsLog,
) : androidx.lifecycle.ViewModel() {
    val entries = log.entries
    fun clear() = log.clear()
}

/**
 * Alpha-only in-app log viewer. Mirrors what lands in logcat without requiring `adb`.
 * Backed by [DiagnosticsLog] (200-entry ring buffer, in-memory only — process death
 * resets the trail by design).
 */
@Composable
fun DiagnosticsScreen(onClose: () -> Unit) {
    val viewModel: DiagnosticsViewModel = hiltViewModel()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to the latest entry as soon as the buffer grows. Useful while a
    // login attempt is in flight so the tester sees the entries arrive live without
    // dragging the list.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Diagnostics (${entries.size}/${DiagnosticsLog.CAPACITY})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(16.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.clear() }) { Text("Vider") }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (entries.isEmpty()) {
                Text(
                    text = "Aucun log pour l'instant. Tente un login pour voir le trail apparaître.",
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

@Composable
private fun DiagnosticsRow(entry: DiagnosticsLog.Entry, timestampLabel: String) {
    val (levelLabel, levelColor) = when (entry.level) {
        DiagnosticsLog.Level.INFO -> "I" to Color(0xFF388E3C)
        DiagnosticsLog.Level.DEBUG -> "D" to Color(0xFF1976D2)
        DiagnosticsLog.Level.WARN -> "W" to Color(0xFFF57C00)
        DiagnosticsLog.Level.ERROR -> "E" to Color(0xFFC62828)
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
