package fr.forumhfr.redface2.feature.topic

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.write.ModerationAlertOutcome

/** #293 — opens after the initial read; submission stays visible and reason focus waits for expansion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModerationAlertSheet(
    state: ModerationAlertUi,
    onIntent: (TopicIntent) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    if (state is ModerationAlertUi.Loading) return
    ModalBottomSheet(
        onDismissRequest = { onIntent(TopicIntent.DismissModerationAlert) },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.imePadding()) {
            ModerationAlertContent(state, onIntent, sheetState.currentValue, Modifier.weight(1f, fill = false))
            // The topic's existing host state stays above the modal and outside the scrolling body.
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModerationAlertContent(
    state: ModerationAlertUi,
    onIntent: (TopicIntent) -> Unit,
    sheetValue: SheetValue,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.topic_alert_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            ModerationAlertUi.Loading -> Unit
            is ModerationAlertUi.Form -> ModerationAlertForm(state, onIntent, sheetValue)
            is ModerationAlertUi.JoinPrompt -> ModerationAlertJoin(state, onIntent)
            is ModerationAlertUi.Info -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // #293 — HFR's own sentence, verbatim; the generic string is only a blank fallback.
                    Text(
                        state.message.ifBlank { stringResource(R.string.topic_alert_unknown) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.treatedAt?.let {
                        Text(
                            stringResource(R.string.topic_alert_treated_at, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is ModerationAlertUi.Result -> Text(moderationAlertResultMessage(state.outcome))
        }
        TextButton(
            onClick = { onIntent(TopicIntent.DismissModerationAlert) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(moderationAlertDismissLabel(state)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModerationAlertForm(
    state: ModerationAlertUi.Form,
    onIntent: (TopicIntent) -> Unit,
    sheetValue: SheetValue,
) {
    val focusRequester = remember { FocusRequester() }
    Text(stringResource(R.string.topic_alert_warning), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = state.reasonDraft,
        onValueChange = { onIntent(TopicIntent.UpdateModerationReason(it)) },
        label = { Text(stringResource(R.string.topic_alert_reason)) },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        minLines = REASON_MIN_LINES,
        enabled = !state.submitting,
    )
    if (state.submitting) ModerationAlertProgress()
    Button(
        onClick = { onIntent(TopicIntent.SubmitModerationAlert) },
        enabled = state.reasonDraft.isNotBlank() && !state.submitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.topic_alert_send))
    }
    LaunchedEffect(sheetValue) {
        if (sheetValue == SheetValue.Expanded && !state.submitting) focusRequester.requestFocus()
    }
}

@Composable
private fun ModerationAlertJoin(state: ModerationAlertUi.JoinPrompt, onIntent: (TopicIntent) -> Unit) {
    Text(stringResource(R.string.topic_alert_join_prompt), style = MaterialTheme.typography.bodyMedium)
    if (state.submitting) ModerationAlertProgress()
    Button(
        onClick = { onIntent(TopicIntent.JoinModerationAlert) },
        enabled = !state.submitting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.topic_alert_join))
    }
}

@Composable
private fun ModerationAlertProgress() {
    val label = stringResource(R.string.topic_alert_loading)
    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label })
}

// #293 — every outcome shows HFR's own sentence; our strings are blank-only fallbacks.
@Composable
private fun moderationAlertResultMessage(outcome: ModerationAlertOutcome): String = when (outcome) {
    is ModerationAlertOutcome.Sent -> outcome.message.ifBlank { stringResource(R.string.topic_alert_sent) }
    is ModerationAlertOutcome.Joined -> outcome.message.ifBlank { stringResource(R.string.topic_alert_joined) }
    is ModerationAlertOutcome.Rejected -> outcome.message.ifBlank { stringResource(R.string.topic_alert_error) }
}

@StringRes
private fun moderationAlertDismissLabel(state: ModerationAlertUi): Int {
    val close = when (state) {
        ModerationAlertUi.Loading -> false
        is ModerationAlertUi.Form -> state.submitting
        is ModerationAlertUi.JoinPrompt -> state.submitting
        is ModerationAlertUi.Info, is ModerationAlertUi.Result -> true
    }
    return if (close) R.string.topic_alert_close else R.string.topic_alert_cancel
}

private const val REASON_MIN_LINES = 3
