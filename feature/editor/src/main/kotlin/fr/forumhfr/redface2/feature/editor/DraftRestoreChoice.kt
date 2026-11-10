package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * #405 — restore decision shared by the post and topic ↺ panels, as the private-message editors' offer.
 * The panel keeps a short title; the sentence appears once, without a line cap or ellipsis, so font scale 1.3
 * never truncates it (gate R1b T3 r1 m3). It sits above the two 48 dp actions; at a [compactHeight]
 * (EditorWindowMetrics.compactHeight) it takes the rest of their row instead, as the former banner did, so a
 * landscape panel above the keyboard still shows both actions (gate R1b minors r1 C1).
 */
@Composable
internal fun DraftRestoreChoice(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    compactHeight: Boolean,
    modifier: Modifier = Modifier,
    restoreEnabled: Boolean = true,
) {
    val offer = stringResource(R.string.editor_draft_restore_message)
    if (compactHeight) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(offer, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            // The former banner's narrower padding keeps the sentence on one line in a 760 dp landscape at 1.3.
            DraftRestoreActions(onRestore, onDiscard, restoreEnabled, CompactActionPadding)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(offer, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DraftRestoreActions(onRestore, onDiscard, restoreEnabled, ButtonDefaults.TextButtonContentPadding)
            }
        }
    }
}

/** Emits « Restaurer » then « Ignorer » into the caller's row. */
@Composable
private fun DraftRestoreActions(
    onRestore: () -> Unit, onDiscard: () -> Unit, restoreEnabled: Boolean, padding: PaddingValues,
) {
    TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp), enabled = restoreEnabled,
        contentPadding = padding) {
        Text(stringResource(R.string.editor_draft_restore))
    }
    TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp), contentPadding = padding) {
        Text(stringResource(R.string.editor_draft_discard))
    }
}

private val CompactActionPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
