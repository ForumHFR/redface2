package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import fr.forumhfr.redface2.core.model.editor.ImagePickerEvent
import fr.forumhfr.redface2.core.ui.editor.rememberEditorImagePicker
import fr.forumhfr.redface2.core.ui.editor.UploadProgressLabel
import fr.forumhfr.redface2.core.ui.editor.bannerText
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerSheet
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerState
import fr.forumhfr.redface2.core.ui.editor.editorControlsMaxHeight
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

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
    // #803 pattern — the actual pop. Invoked only on CloseCommitted (after the ViewModel flushed
    // the draft), never directly by the chrome: both the system back and the header arrow route
    // through PrivateMessageComposeViewModel.onCloseRequested first.
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
                // #803 pattern — the pop happens only AFTER the ViewModel flushed the draft.
                PrivateMessageComposeEffect.CloseCommitted -> onBack()
            }
        }
    }
    // #803 pattern (state-hygiene audit 2026-07-05) — every close path (system back below, header
    // arrow via onCloseRequested in the content wiring) routes through the ViewModel so the
    // pending autosave debounce is flushed BEFORE the pop (trading the predictive-back preview
    // for never losing the last < 750 ms of typing — same trade-off as PostEditorScreen).
    BackHandler { viewModel.onCloseRequested() }
    PrivateMessageComposeContent(
        state = state,
        onBack = viewModel::onCloseRequested,
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
        onDraftRestore = viewModel::onDraftRestoreRequested,
        onDraftDiscard = viewModel::onDraftDiscardRequested,
        onImagePickerEvent = viewModel::onImagePickerEvent,
        onUploadErrorDismissed = viewModel::onUploadErrorDismissed,
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
    onDraftRestore: () -> Unit,
    onDraftDiscard: () -> Unit,
    // #459 — image upload wiring (photo picker launcher lives in the body composable).
    onImagePickerEvent: (ImagePickerEvent) -> Unit,
    onUploadErrorDismissed: () -> Unit,
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
                        onDraftRestore = onDraftRestore,
                        onDraftDiscard = onDraftDiscard,
                        onImagePickerEvent = onImagePickerEvent,
                        onUploadErrorDismissed = onUploadErrorDismissed,
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
    onDraftRestore: () -> Unit,
    onDraftDiscard: () -> Unit,
    onImagePickerEvent: (ImagePickerEvent) -> Unit,
    onUploadErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launchImagePicker = rememberEditorImagePicker(state.imagePickerMode, onImagePickerEvent)
    // #447/#1406 — the draft must be bounded so BTF2 owns selection scrolling. The
    // header keeps an internal capped scroll: unlike the pre-#434 weighted layout, recipients,
    // subject and toolbar cannot squeeze the draft to zero when the IME opens.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val controlsMaxHeight = editorControlsMaxHeight(
            available = maxHeight,
            fieldMin = COMPOSE_DRAFT_MIN_HEIGHT,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // #447/#555/#1406 — metadata, preview controls and every banner share one bounded
            // scrollable zone. None of that non-weighted chrome can consume the draft viewport.
            Column(
                modifier = Modifier
                    .heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ComposeEditorControls(
                    state = state,
                    onRecipientsChanged = onRecipientsChanged,
                    onSubjectChanged = onSubjectChanged,
                    onToolbarAction = onToolbarAction,
                    onImageUploadRequested = launchImagePicker,
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
                    BbcodePreview(
                        content = state.preview,
                        modifier = Modifier.fillMaxWidth(),
                        mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED,
                    )
                }

                if (state.restorableDraft != null ||
                    state.restorableSubject != null ||
                    state.restorableRecipients != null
                ) {
                    MessageDraftRestoreBanner(onRestore = onDraftRestore, onDiscard = onDraftDiscard)
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

                // #459 — dismissible upload-error banner (shared :core:ui wording).
                state.uploadError?.let { error ->
                    Text(
                        text = error.bannerText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onUploadErrorDismissed) {
                        Text(text = stringResource(R.string.messages_reply_error_dismiss))
                    }
                }
            }

            BbcodeTextField(
                value = state.draft,
                onValueChange = onContentChanged,
                label = stringResource(R.string.messages_reply_field_label),
                placeholder = stringResource(R.string.messages_reply_field_placeholder),
                modifier = Modifier.weight(1f),
                // #459 — lock editing during a batch (caret must not move between two insertions).
                readOnly = state.isUploading,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList") // Hoisted editor controls: each callback maps to one field/action.
private fun ComposeEditorControls(
    state: PrivateMessageComposeUiState,
    onRecipientsChanged: (String) -> Unit,
    onSubjectChanged: (String) -> Unit,
    onToolbarAction: (BbcodeAction) -> Unit,
    onImageUploadRequested: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.recipients,
            onValueChange = onRecipientsChanged,
            singleLine = true,
            enabled = !state.isSubmitting,
            // #606 — clearer label + a one-liner that explains a CSV makes a group conversation.
            label = { Text(stringResource(R.string.messages_compose_recipients_label_v2)) },
            placeholder = { Text(stringResource(R.string.messages_compose_recipients_placeholder)) },
            supportingText = { Text(stringResource(R.string.messages_compose_recipients_help)) },
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
        BbcodeToolbar(
            onAction = onToolbarAction,
            // #459 — upload wiring, same affordance as the topic-side editors.
            onImageUploadRequested = onImageUploadRequested,
            uploading = state.isUploading,
        )
        // #459 — « n/N » batch counter while a multi-image upload is in flight.
        UploadProgressLabel(state.uploadProgress)
    }
}

// #434/#447 — reserve a real draft viewport under the capped, scrollable compose controls.
private val COMPOSE_DRAFT_MIN_HEIGHT = 160.dp
