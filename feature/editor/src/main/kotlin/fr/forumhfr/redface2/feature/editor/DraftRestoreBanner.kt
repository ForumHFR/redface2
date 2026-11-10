package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Restore decisions shared by the post and topic panels. */
@Composable
internal fun DraftRestoreBanner(
    onRestore: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier, restoreEnabled: Boolean = true,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_draft_restore_message), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRestore, enabled = restoreEnabled, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.editor_draft_restore))
            }
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.editor_draft_discard))
            }
        }
    }
}
