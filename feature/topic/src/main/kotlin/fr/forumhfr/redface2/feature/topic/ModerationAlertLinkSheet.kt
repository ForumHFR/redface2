package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.error.sharedLabelResOrNull

/** Read-only information above any destination; only ViewPost or a missing alert initiates navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationAlertLinkSheet(
    state: ModerationAlertLinkState,
    onIntent: (ModerationAlertLinkIntent) -> Unit,
    topicTitle: String?,
) {
    if (state is ModerationAlertLinkState.Idle || state is ModerationAlertLinkState.NavigateToPost) return
    ModalBottomSheet(
        onDismissRequest = { onIntent(ModerationAlertLinkIntent.Dismiss) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.topic_alert_link_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            ModerationAlertLinkBody(state, onIntent)
            state.target?.let { ModerationAlertLinkViewPost(it, topicTitle, onIntent) }
            TextButton(
                onClick = { onIntent(ModerationAlertLinkIntent.Dismiss) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_alert_close))
            }
        }
    }
}

@Composable
private fun ModerationAlertLinkBody(
    state: ModerationAlertLinkState,
    onIntent: (ModerationAlertLinkIntent) -> Unit,
) {
    when (state) {
        is ModerationAlertLinkState.Loading -> {
            val label = stringResource(R.string.topic_alert_loading)
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
        }
        is ModerationAlertLinkState.Info -> {
            Text(
                state.message.ifBlank { stringResource(R.string.topic_alert_unknown) },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.treatedAt?.let { Text(stringResource(R.string.topic_alert_treated_at, it)) }
        }
        is ModerationAlertLinkState.SignInRequired -> Text(stringResource(R.string.topic_alert_link_sign_in_required))
        is ModerationAlertLinkState.Error -> {
            Text(stringResource(state.kind.sharedLabelResOrNull() ?: R.string.topic_alert_error))
            TextButton(onClick = { onIntent(ModerationAlertLinkIntent.Retry) }) {
                Text(stringResource(R.string.topic_retry))
            }
        }
        ModerationAlertLinkState.Idle, is ModerationAlertLinkState.NavigateToPost -> Unit
    }
}

@Composable
private fun ModerationAlertLinkViewPost(
    target: ModerationAlertLinkTarget,
    topicTitle: String?,
    onIntent: (ModerationAlertLinkIntent) -> Unit,
) {
    Button(
        onClick = { onIntent(ModerationAlertLinkIntent.ViewPost) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(stringResource(R.string.topic_alert_link_view_post))
            val subtitle = if (topicTitle.isNullOrBlank()) {
                stringResource(R.string.topic_alert_link_post_fallback, target.numreponse, target.post, target.page)
            } else {
                stringResource(R.string.topic_alert_link_topic_page, topicTitle, target.page)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
