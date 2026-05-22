package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar

/**
 * Post-level editor screen. Phase 2C (#145) adds a Submit button that posts the
 * reply via [PostEditorViewModel.submit]. Successful submissions raise a one-shot
 * [PostEditorEffect.SubmitSucceeded] which the navigation host translates into a
 * back navigation + topic refresh.
 */
@Composable
fun PostEditorScreen(
    request: PostEditorRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostEditorViewModel = hiltViewModel<PostEditorViewModel, PostEditorViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PostEditorEffect.SubmitSucceeded ->
                    onSubmitSucceeded(effect.targetPage, effect.scrollTo)
            }
        }
    }
    PostEditorContent(
        state = state,
        onIntent = remember(viewModel) { { intent: PostEditorIntent -> viewModel.submit(intent) } },
        modifier = modifier,
    )
}

@Composable
private fun PostEditorContent(
    state: PostEditorState,
    onIntent: (PostEditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openSmileyPickerDescription = stringResource(R.string.editor_smiley_open_description)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(state.mode.titleResId),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            BbcodeToolbar(
                onAction = { action -> onIntent(PostEditorIntent.ToolbarActionClicked(action)) },
            )

            // Phase 2F-B (#11) — quick access to the smiley picker. Lives next to the BBCode
            // toolbar rather than inside it because smileys are point-insertions, not wrappers,
            // and the underlying `BbcodeAction` model is wrap-only.
            TextButton(
                onClick = { onIntent(PostEditorIntent.SmileyPickerOpened) },
                modifier = Modifier.semantics {
                    contentDescription = openSmileyPickerDescription
                },
            ) {
                Text(text = stringResource(R.string.editor_smiley_open))
            }

            BbcodeTextField(
                value = state.draft,
                onValueChange = { value -> onIntent(PostEditorIntent.ContentChanged(value)) },
                label = stringResource(R.string.editor_field_label),
                placeholder = stringResource(R.string.editor_field_placeholder),
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                TextButton(onClick = { onIntent(PostEditorIntent.TogglePreview) }) {
                    Text(
                        text = stringResource(
                            if (state.isPreviewVisible) R.string.editor_preview_hide else R.string.editor_preview_show,
                        ),
                    )
                }
            }

            if (state.isPreviewVisible) {
                HorizontalDivider()
                BbcodePreview(content = state.preview)
            }

            state.submitError?.let { error ->
                Text(
                    text = stringResource(error.bannerResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onIntent(PostEditorIntent.ErrorDismissed) }) {
                    Text(text = stringResource(R.string.editor_error_dismiss))
                }
            }

            if (state.mode == PostEditorMode.Reply || state.mode == PostEditorMode.Edit) {
                // HFR per-post option toggles. Defaults come from `ReplyForm.options`
                // (the `checked` attribute of each HTML checkbox HFR rendered for
                // this user / topic). The repository only adds the matching POST
                // field when the toggle is on — mirroring how a browser submits.
                // Phase 2D (#147) — Edit shares the same options surface as Reply:
                // both toggles live in `PostEditorState` and the matching
                // repository reads them at submit time, so the UI stays identical.
                PostEditorOptions(
                    signatureEnabled = state.signatureEnabled,
                    smileyDisabled = state.smileyDisabled,
                    emailNotificationEnabled = state.emailNotificationEnabled,
                    enabled = !state.isSubmitting && !state.isLoadingForm,
                    onSignatureChanged = { onIntent(PostEditorIntent.ToggleSignature(it)) },
                    onSmileyDisabledChanged = { onIntent(PostEditorIntent.ToggleSmileyDisabled(it)) },
                    onEmailNotificationChanged = { onIntent(PostEditorIntent.ToggleEmailNotification(it)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.isLoadingForm) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    Button(
                        enabled = state.canSubmit,
                        onClick = { onIntent(PostEditorIntent.SubmitClicked) },
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(text = stringResource(R.string.editor_submit))
                        }
                    }
                }
            } else {
                // Defensive fallback for future post-level modes. Reply (#145),
                // Quote (#146) and Edit (#147) all go through the branch above ;
                // topic-level create/edit flows are handled by TopicFormScreen.
                Text(
                    text = stringResource(R.string.editor_submit_disabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Phase 2F-B (#11) — bottom-sheet smiley picker. Rendered as a sibling of the
        // Column so the sheet can scrim the editor without being constrained by the
        // verticalScroll above. Visibility is owned by the ViewModel via
        // `SmileyPickerState` ; dismissal goes through the dedicated intent so the
        // ViewModel can cancel any in-flight wiki search at the same time.
        val picker = state.smileyPicker
        if (picker is SmileyPickerState.Open) {
            SmileyPickerSheet(
                state = picker,
                onDismiss = { onIntent(PostEditorIntent.SmileyPickerDismissed) },
                onQueryChange = { query -> onIntent(PostEditorIntent.SmileySearchQueryChanged(query)) },
                onSmileyClicked = { token -> onIntent(PostEditorIntent.SmileySelected(token)) },
            )
        }
    }
}

@Composable
@Suppress("LongParameterList") // 3 toggles + 3 callbacks + enabled — each call-site distinct.
private fun PostEditorOptions(
    signatureEnabled: Boolean,
    smileyDisabled: Boolean,
    emailNotificationEnabled: Boolean,
    enabled: Boolean,
    onSignatureChanged: (Boolean) -> Unit,
    onSmileyDisabledChanged: (Boolean) -> Unit,
    onEmailNotificationChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_options_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggle(
            label = stringResource(R.string.editor_option_email_notification),
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
    }
}

@Composable
private fun OptionToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 16.dp),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private val PostEditorMode.titleResId: Int
    get() = when (this) {
        PostEditorMode.Reply -> R.string.editor_post_reply_title
        PostEditorMode.Edit -> R.string.editor_post_edit_title
    }

private val SubmitError.bannerResId: Int
    get() = when (this) {
        is SubmitError.Hfr -> when (reason) {
            ReplyFailureReason.EmptyMessage -> R.string.editor_error_empty
            ReplyFailureReason.InvalidHashCheck -> R.string.editor_error_invalid_hash
            ReplyFailureReason.AntiFlood -> R.string.editor_error_anti_flood
            ReplyFailureReason.TopicLocked -> R.string.editor_error_topic_locked
            ReplyFailureReason.LoginRequired -> R.string.editor_error_login_required
            ReplyFailureReason.Unknown -> R.string.editor_error_unknown
        }
        SubmitError.Network -> R.string.editor_error_network
        SubmitError.SessionExpired -> R.string.editor_error_session_expired
        SubmitError.MissingSubcat -> R.string.editor_error_missing_subcat
    }
