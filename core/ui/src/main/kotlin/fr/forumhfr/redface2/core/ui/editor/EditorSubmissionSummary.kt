package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/** ED-133/170, D44: the two fixed summaries share typography, icon and spacing across surfaces. */
@Composable
fun EditorSubmissionSummary(error: EditorError, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RedfaceVectorIcon(
            resId = R.drawable.ic_editor_submission_warning,
            tint = MaterialTheme.colorScheme.error,
            size = 16.dp,
        )
        Text(
            text = error.summary.resolve(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}
