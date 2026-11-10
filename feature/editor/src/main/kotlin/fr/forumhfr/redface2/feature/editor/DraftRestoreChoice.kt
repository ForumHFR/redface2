package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * #405 — restore decision shared by the post and topic ↺ panels, as the private-message editors' offer.
 * The panel keeps a short title; the sentence appears once, without a line cap, above the two 48 dp actions,
 * so font scale 1.3 never ellipsizes it (gate R1b T3 r1 m3).
 */
@Composable
internal fun DraftRestoreChoice(
    onRestore: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier, restoreEnabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.editor_draft_restore_message), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp), enabled = restoreEnabled) {
                Text(stringResource(R.string.editor_draft_restore))
            }
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.editor_draft_discard))
            }
        }
    }
}
