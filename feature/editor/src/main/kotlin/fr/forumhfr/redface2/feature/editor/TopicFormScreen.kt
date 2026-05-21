package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar

/**
 * Phase 2D #148 — topic-level form screen. Live for [TopicFormMode.EditFirstPost] ;
 * [TopicFormMode.New] still renders the legacy placeholder (Phase 2E #149).
 *
 * Surface for EditFirstPost :
 * - Subject field (writable).
 * - Subcategory : current label + read-only note ; a future iteration may add
 *   a dropdown built from `state.subcategoryChoices`.
 * - BBCode toolbar + draft field + optional preview.
 * - Per-post options (signature / smileys / email) identical to the post-level editor.
 * - Poll : if `state.pollPresent`, a sober note that mutation is not in this version.
 * - Submit button + error banner.
 */
@Composable
fun TopicFormScreen(
    request: TopicFormRequest,
    onSubmitSucceeded: (targetPage: Int?, scrollTo: Int?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TopicFormViewModel = hiltViewModel<TopicFormViewModel, TopicFormViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TopicFormEffect.SubmitSucceeded ->
                    onSubmitSucceeded(effect.targetPage, effect.scrollTo)
            }
        }
    }

    TopicFormContent(
        state = state,
        onIntent = viewModel::submit,
        modifier = modifier,
    )
}

@Composable
internal fun TopicFormContent(
    state: TopicFormState,
    onIntent: (TopicFormIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (state.mode != TopicFormMode.EditFirstPost) {
            // Phase 2E #149 placeholder — same copy as before, the route still
            // exists so navigation intent stays fixed.
            TopicFormPlaceholder(state.mode)
            return@Surface
        }
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
                text = stringResource(R.string.editor_topic_edit_first_post_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = state.subject,
                onValueChange = { onIntent(TopicFormIntent.SubjectChanged(it)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSubmitting,
                label = { Text(stringResource(R.string.editor_topic_subject_label)) },
            )
            // Subcategory : show the currently selected label. The full dropdown
            // is intentionally deferred — Phase 2D MVP forwards whatever HFR
            // pre-selected, leaving the user the option to re-categorise later.
            val selectedChoice = state.subcategoryChoices.firstOrNull { it.id == state.selectedSubcat }
            if (selectedChoice != null) {
                Text(
                    text = stringResource(R.string.editor_topic_subcat_current, selectedChoice.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BbcodeToolbar(
                onAction = { onIntent(TopicFormIntent.ToolbarActionClicked(it)) },
            )
            BbcodeTextField(
                value = state.draft,
                onValueChange = { onIntent(TopicFormIntent.ContentChanged(it)) },
                label = stringResource(R.string.editor_field_label),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onIntent(TopicFormIntent.TogglePreview) }) {
                Text(
                    text = if (state.isPreviewVisible) {
                        stringResource(R.string.editor_preview_hide)
                    } else {
                        stringResource(R.string.editor_preview_show)
                    },
                )
            }
            if (state.isPreviewVisible) {
                BbcodePreview(content = state.preview, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            TopicFormOptionsBlock(
                signatureEnabled = state.signatureEnabled,
                smileyDisabled = state.smileyDisabled,
                emailNotificationEnabled = state.emailNotificationEnabled,
                enabled = !state.isSubmitting && !state.isLoadingForm,
                onSignatureChanged = { onIntent(TopicFormIntent.ToggleSignature(it)) },
                onSmileyDisabledChanged = { onIntent(TopicFormIntent.ToggleSmileyDisabled(it)) },
                onEmailNotificationChanged = { onIntent(TopicFormIntent.ToggleEmailNotification(it)) },
            )
            if (state.pollPresent && !state.pollEditable) {
                // Honest copy : the topic has a poll, but Phase 2D #148 does
                // not edit poll fields — they are preserved verbatim on POST.
                Text(
                    text = stringResource(R.string.editor_topic_poll_readonly_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.submitError?.let { error ->
                Text(
                    text = stringResource(error.bannerResId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onIntent(TopicFormIntent.ErrorDismissed) }) {
                    Text(text = stringResource(R.string.editor_error_dismiss))
                }
            }
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
                    onClick = { onIntent(TopicFormIntent.SubmitClicked) },
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
        }
    }
}

@Composable
private fun TopicFormPlaceholder(mode: TopicFormMode) {
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

@Composable
@Suppress("LongParameterList") // state-hoisted Composable
private fun TopicFormOptionsBlock(
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
        OptionToggleRow(
            label = stringResource(R.string.editor_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggleRow(
            label = stringResource(R.string.editor_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggleRow(
            label = stringResource(R.string.editor_option_email_notification),
            checked = emailNotificationEnabled,
            enabled = enabled,
            onCheckedChange = onEmailNotificationChanged,
        )
    }
}

@Composable
private fun OptionToggleRow(
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

private val TopicFormMode.titleResId: Int
    get() = when (this) {
        TopicFormMode.New -> R.string.editor_topic_new_title
        TopicFormMode.EditFirstPost -> R.string.editor_topic_edit_first_post_title
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
