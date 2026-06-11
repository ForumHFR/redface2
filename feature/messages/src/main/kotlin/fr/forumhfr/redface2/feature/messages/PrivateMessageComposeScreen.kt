package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState

/**
 * New-conversation composer (#301 follow-up). Same chrome as the reply editor — shared header,
 * IME-pinned submit bar, options sheet, BBCode toolbar/field/preview — plus the two routing
 * fields HFR's standalone composer requires : recipients (`dest`, comma-separated for a MultiMP)
 * and subject (`sujet`, 70 chars max). A successful send raises
 * [PrivateMessageComposeEffect.SubmitSucceeded] ; the host pops back to the MP list and refreshes
 * it (the created thread id is unknown — cf. the effect's KDoc).
 */
@Composable
fun PrivateMessageComposeScreen(
    onSubmitSucceeded: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialRecipient: String? = null,
) {
    val viewModel = hiltViewModel<PrivateMessageComposeViewModel, PrivateMessageComposeViewModel.Factory>(
        creationCallback = { factory -> factory.create(initialRecipient) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PrivateMessageComposeEffect.SubmitSucceeded -> onSubmitSucceeded()
            }
        }
    }
    PrivateMessageComposeContent(
        state = state,
        onBack = onBack,
        onRecipientsChanged = viewModel::onRecipientsChanged,
        onSubjectChanged = viewModel::onSubjectChanged,
        onContentChanged = viewModel::onContentChanged,
        onToolbarAction = viewModel::onToolbarAction,
        onTogglePreview = viewModel::onTogglePreview,
        onToggleSignature = viewModel::onToggleSignature,
        onToggleSmileyDisabled = viewModel::onToggleSmileyDisabled,
        onToggleEmailNotification = viewModel::onToggleEmailNotification,
        onErrorDismissed = viewModel::onErrorDismissed,
        onSubmit = viewModel::onSubmit,
        onSubmitConfirmed = viewModel::onSubmitConfirmed,
        onSubmitConfirmationDismissed = viewModel::onSubmitConfirmationDismissed,
        onRetryFormLoad = viewModel::retryFormLoad,
        smileyPicker = viewModel.smileyPicker,
        onSmileySelected = viewModel::onSmileySelected,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList") // One callback per editor action — each is wired to a distinct VM method.
private fun PrivateMessageComposeContent(
    state: PrivateMessageComposeUiState,
    onBack: () -> Unit,
    onRecipientsChanged: (String) -> Unit,
    onSubjectChanged: (String) -> Unit,
    onContentChanged: (TextFieldValue) -> Unit,
    onToolbarAction: (BbcodeAction) -> Unit,
    onTogglePreview: () -> Unit,
    onToggleSignature: (Boolean) -> Unit,
    onToggleSmileyDisabled: (Boolean) -> Unit,
    onToggleEmailNotification: (Boolean) -> Unit,
    onErrorDismissed: () -> Unit,
    onSubmit: () -> Unit,
    onSubmitConfirmed: () -> Unit,
    onSubmitConfirmationDismissed: () -> Unit,
    onRetryFormLoad: () -> Unit,
    smileyPicker: SmileyPickerController,
    onSmileySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsSheetOpen by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            MessageEditorHeader(
                title = stringResource(R.string.messages_compose_title),
                onBack = onBack,
            )
            when {
                state.formError -> MessageFormErrorState(
                    onRetry = onRetryFormLoad,
                    message = stringResource(R.string.messages_compose_form_error),
                )
                state.isLoadingForm && !state.formAvailable -> MessageFormLoadingState()
                else -> {
                    ComposeEditorBody(
                        state = state,
                        onRecipientsChanged = onRecipientsChanged,
                        onSubjectChanged = onSubjectChanged,
                        onContentChanged = onContentChanged,
                        onToolbarAction = onToolbarAction,
                        onTogglePreview = onTogglePreview,
                        onErrorDismissed = onErrorDismissed,
                        modifier = Modifier.weight(1f),
                    )
                    MessageSubmitBar(
                        canSubmit = state.canSubmit,
                        isSubmitting = state.isSubmitting,
                        confirmArmed = state.showSubmitConfirmation,
                        onSubmit = onSubmit,
                        onConfirmSubmit = onSubmitConfirmed,
                        onDisarmConfirm = onSubmitConfirmationDismissed,
                        onOpenOptions = { optionsSheetOpen = true },
                        onOpenSmileys = smileyPicker::open,
                    )
                }
            }
        }
        if (optionsSheetOpen) {
            EditorOptionsSheet(onDismiss = { optionsSheetOpen = false }) {
                MessageEditorOptions(
                    signatureEnabled = state.signatureEnabled,
                    smileyDisabled = state.smileyDisabled,
                    emailNotificationEnabled = state.emailNotificationEnabled,
                    enabled = !state.isSubmitting && !state.isLoadingForm,
                    onSignatureChanged = onToggleSignature,
                    onSmileyDisabledChanged = onToggleSmileyDisabled,
                    onEmailNotificationChanged = onToggleEmailNotification,
                )
            }
        }
        // #387 — smiley picker sheet (Standard + Wiki), same component as the post editors.
        val pickerState by smileyPicker.state.collectAsStateWithLifecycle()
        (pickerState as? SmileyPickerState.Open)?.let { open ->
            SmileyPickerSheet(
                state = open,
                onDismiss = smileyPicker::dismiss,
                onQueryChange = smileyPicker::onQueryChanged,
                onSmileyClicked = onSmileySelected,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList") // Editor body mirrors the reply surface ; each callback is distinct.
private fun ComposeEditorBody(
    state: PrivateMessageComposeUiState,
    onRecipientsChanged: (String) -> Unit,
    onSubjectChanged: (String) -> Unit,
    onContentChanged: (TextFieldValue) -> Unit,
    onToolbarAction: (BbcodeAction) -> Unit,
    onTogglePreview: () -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // #275/#410 follow-up (dev v118 feedback, screen 520813) — compose is the ONE editor whose
    // fixed header (recipients + subject + toolbar, ~300dp) could squeeze the weighted draft
    // field to ~zero once the IME opened, with no outer scroll to bring it back into view: the
    // reply editor's « no outer scroll, fillViewport field » design assumes the field owns most
    // of the body. Header-heavy editors use the OTHER documented branch of the BbcodeTextField
    // contract (cf. TopicFormScreen): the WHOLE body scrolls and the field keeps the default
    // grow-with-content mode — cursor bring-into-view and IME re-anchoring route through this
    // outer column (fillViewport here would nest two unbounded same-direction scrollables).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.recipients,
            onValueChange = onRecipientsChanged,
            singleLine = true,
            enabled = !state.isSubmitting,
            label = { Text(stringResource(R.string.messages_compose_recipients_label)) },
            placeholder = { Text(stringResource(R.string.messages_compose_recipients_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.subject,
            onValueChange = onSubjectChanged,
            singleLine = true,
            enabled = !state.isSubmitting,
            label = { Text(stringResource(R.string.messages_compose_subject_label)) },
            supportingText = {
                Text(
                    text = stringResource(
                        R.string.messages_compose_subject_counter,
                        state.subject.length,
                        PrivateMessageComposeUiState.SUBJECT_MAX_LENGTH,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        BbcodeToolbar(onAction = onToolbarAction)

        BbcodeTextField(
            value = state.draft,
            onValueChange = onContentChanged,
            label = stringResource(R.string.messages_reply_field_label),
            placeholder = stringResource(R.string.messages_reply_field_placeholder),
            // Default grow-with-content mode inside the outer scroll (see the column comment):
            // a min height keeps a real tap-target/typing area even with an empty draft.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPOSE_DRAFT_MIN_HEIGHT),
        )

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            TextButton(onClick = onTogglePreview) {
                Text(
                    text = stringResource(
                        if (state.isPreviewVisible) {
                            R.string.messages_reply_preview_hide
                        } else {
                            R.string.messages_reply_preview_show
                        },
                    ),
                )
            }
        }

        if (state.isPreviewVisible) {
            HorizontalDivider()
            // Plain block inside the outer scroll (a nested same-direction verticalScroll is a
            // Compose error) — same shape as TopicFormScreen's preview.
            BbcodePreview(content = state.preview, modifier = Modifier.fillMaxWidth())
        }

        state.submitError?.let { error ->
            Text(
                // Unexpected gets composer wording (« vérifiez votre liste de messages ») —
                // the shared banner says « vérifiez la conversation », which has no meaning
                // before the conversation exists (Codex review of #404).
                text = stringResource(
                    if (error == PrivateMessageReplyError.Unexpected) {
                        R.string.messages_compose_error_unexpected
                    } else {
                        error.bannerResId
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onErrorDismissed) {
                Text(text = stringResource(R.string.messages_reply_error_dismiss))
            }
        }
    }
}

// #275/#410 follow-up — minimum draft area inside the scrollable compose body: with an empty
// draft the outlined field still offers a real tap/typing target under the header fields.
private val COMPOSE_DRAFT_MIN_HEIGHT = 160.dp
