package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import fr.forumhfr.redface2.core.domain.editor.ArmToken
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitActions
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitButton
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitLabels
import fr.forumhfr.redface2.core.ui.editor.ArmedSubmitState

/** Historical chrome shared with TopicForm; no new post chrome is mounted here. */
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

internal data class EditorSubmitState(
    val canSubmit: Boolean,
    val isSubmitting: Boolean,
    val isLoadingForm: Boolean,
    val confirmArmed: Boolean,
    val armToken: ArmToken? = null,
    val controlsEnabled: Boolean = true,
)

internal data class EditorSubmitActions(
    val onSubmit: (Long) -> Unit,
    val onConfirmSubmit: () -> Unit,
    val onDisarmConfirm: () -> Unit,
    val onOpenOptions: () -> Unit,
    val onOpenSmileys: (() -> Unit)? = null,
)

@Composable
internal fun EditorSubmitBar(state: EditorSubmitState, actions: EditorSubmitActions) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!state.confirmArmed) {
                    FilledTonalButton(enabled = state.controlsEnabled, onClick = actions.onOpenOptions) {
                        Text(text = stringResource(R.string.editor_actions_options))
                    }
                    actions.onOpenSmileys?.let { openSmileys ->
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(enabled = state.controlsEnabled, onClick = openSmileys) {
                            Text(text = stringResource(R.string.editor_smiley_open))
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.isLoadingForm) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                ArmedSubmitButton(
                    state = ArmedSubmitState(armed = state.confirmArmed, armToken = state.armToken,
                        enabled = state.canSubmit, showProgress = state.isSubmitting),
                    labels = ArmedSubmitLabels(stringResource(R.string.editor_submit),
                        stringResource(R.string.editor_submit_confirm)),
                    actions = ArmedSubmitActions({}, actions.onConfirmSubmit,
                        actions.onDisarmConfirm, actions.onSubmit),
                )
            }
        }
    }
}
