package fr.forumhfr.redface2

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.RedfacePlaceholderScreen

@Composable
fun FlagsScreen(
    onOpenUnreadTopic: () -> Unit,
    onOpenTrackedCategory: () -> Unit,
) {
    RedfacePlaceholderScreen(
        title = stringResource(R.string.flags_title),
        body = stringResource(R.string.flags_body),
    ) {
        Button(onClick = onOpenUnreadTopic) {
            Text(text = stringResource(R.string.flags_open_unread_topic))
        }
        OutlinedButton(onClick = onOpenTrackedCategory) {
            Text(text = stringResource(R.string.flags_open_category))
        }
        // Phase 1A — version surfaced on the home placeholder so dogfood builds advertise
        // their lineage. Will move to :feature:settings (About screen) once that module
        // gets real content.
        Text(
            text = stringResource(
                R.string.app_version_footer,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
