package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Phase 2B-A placeholder for the topic-level form (subject + cat/subcat + content +
 * poll). The route exists so navigation intent is fixed today, but the real form
 * lands in Phase 2D / 2E with #148 (edit FP) and #149 (create topic).
 */
@Composable
fun TopicFormScreen(
    mode: TopicFormMode,
    @Suppress("UNUSED_PARAMETER") cat: Int?,
    @Suppress("UNUSED_PARAMETER") subcat: Int?,
    @Suppress("UNUSED_PARAMETER") topicId: Int?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(mode.titleResId),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.editor_topic_form_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TopicFormMode.titleResId: Int
    get() = when (this) {
        TopicFormMode.New -> R.string.editor_topic_new_title
        TopicFormMode.EditFirstPost -> R.string.editor_topic_edit_first_post_title
    }
