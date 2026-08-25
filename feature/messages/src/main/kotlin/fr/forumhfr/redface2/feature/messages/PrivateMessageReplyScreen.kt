package fr.forumhfr.redface2.feature.messages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.MAX_IMAGES_PER_UPLOAD
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
import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy

/**
 * Reply editor for a private-message conversation (#301). Reuses the shared `:core:ui` BBCode
 * toolbar / text field / preview and a send button pinned above the IME (same window-insets pattern
 * as the post editor, which requires `windowSoftInputMode=adjustNothing`). Submission goes through
 * [PrivateMessageReplyViewModel]; a successful send raises [PrivateMessageReplyEffect.SubmitSucceeded]
 * which the navigation host turns into a back navigation + a forced conversation reload.
 */
@Composable
fun PrivateMessageReplyScreen(
    request: PrivateMessageReplyRequest,
    onSubmitSucceeded: (threadId: Int, page: Int) -> Unit,
    // #803 pattern — the actual pop. Invoked only on CloseCommitted (after the ViewModel flushed
    // the draft), never directly by the chrome: both the system back and the header arrow route
    // through PrivateMessageReplyViewModel.onCloseRequested first.
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<PrivateMessageReplyViewModel, PrivateMessageReplyViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PrivateMessageReplyEffect.SubmitSucceeded ->
                    onSubmitSucceeded(effect.threadId, effect.page)
                // #803 pattern — the pop happens only AFTER the ViewModel flushed the draft.
                PrivateMessageReplyEffect.CloseCommitted -> onBack()
            }
        }
    }
    // #803 pattern (state-hygiene audit 2026-07-05) — every close path (system back below, header
    // arrow via onCloseRequested in the content wiring) routes through the ViewModel so the
    // pending autosave debounce is flushed BEFORE the pop (trading the predictive-back preview
    // for never losing the last < 750 ms of typing — same trade-off as PostEditorScreen).
    BackHandler { viewModel.onCloseRequested() }
    PrivateMessageReplyContent(
        state = state,
        // #618 — auto-open the recipient-manager sheet when entered from the Participants sheet.
        autoOpenRecipientManager = request.openRecipientManager,
        onBack = viewModel::onCloseRequested,
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
        onImagesPicked = viewModel::onImagesPicked,
        onUploadErrorDismissed = viewModel::onUploadErrorDismissed,
        onAddRecipient = viewModel::onAddRecipient,
        onRemoveRecipient = viewModel::onRemoveRecipient,
        smileyPicker = viewModel.smileyPicker,
        onSmileySelected = viewModel::onSmileySelected,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList") // One callback per editor action — each is wired to a distinct VM method.
private fun PrivateMessageReplyContent(
    state: PrivateMessageReplyUiState,
    autoOpenRecipientManager: Boolean,
    onBack: () -> Unit,
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
    onImagesPicked: (List<String>) -> Unit,
    onUploadErrorDismissed: () -> Unit,
    onAddRecipient: (String) -> Unit,
    onRemoveRecipient: (String) -> Unit,
    smileyPicker: SmileyPickerController,
    onSmileySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsSheetOpen by remember { mutableStateOf(false) }
    // #618 — the « Gérer les destinataires » bottom sheet (Bug 1: the member editor no longer stacks
    // inline above the composer). Opened by the compact summary button below, or auto-opened on entry
    // from the conversation's Participants sheet.
    var recipientManagerOpen by remember { mutableStateOf(false) }
    // One-shot auto-open (Codex framing): fire once the form has loaded AND the owner-only editor is
    // available, never before (canManageRecipients arrives after the async form GET). `autoOpened`
    // survives recomposition / config changes so the sheet is never re-opened after the user closes it.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoOpenRecipientManager, state.canManageRecipients) {
        if (autoOpenRecipientManager && state.canManageRecipients && !autoOpened) {
            autoOpened = true
            recipientManagerOpen = true
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            MessageEditorHeader(title = stringResource(R.string.messages_reply_title), onBack = onBack)
            when {
                state.formError -> MessageFormErrorState(onRetry = onRetryFormLoad)
                state.isLoadingForm && !state.formAvailable -> MessageFormLoadingState()
                else -> {
                    ReplyEditorBody(
                        state = state,
                        onContentChanged = onContentChanged,
                        onToolbarAction = onToolbarAction,
                        onTogglePreview = onTogglePreview,
                        onErrorDismissed = onErrorDismissed,
                        onDraftRestore = onDraftRestore,
                        onDraftDiscard = onDraftDiscard,
                        onImagesPicked = onImagesPicked,
                        onUploadErrorDismissed = onUploadErrorDismissed,
                        onManageRecipients = { recipientManagerOpen = true },
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
        // HFR per-message option toggles, moved behind the bar's « Options » trigger —
        // same surface as the post editor / topic form (shared EditorOptionsSheet).
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
        // #618 — recipient manager bottom sheet (Bug 1: was an inline editor stacked above the body).
        // Owner-only; the compact summary button can only arm it when canManageRecipients is true.
        if (recipientManagerOpen && state.canManageRecipients) {
            RecipientManagerSheet(
                recipients = state.recipients,
                enabled = !state.isSubmitting,
                onAddRecipient = onAddRecipient,
                onRemoveRecipient = onRemoveRecipient,
                onDismiss = { recipientManagerOpen = false },
            )
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
@Suppress("LongParameterList") // Editor body mirrors the post editor surface; each callback is distinct.
private fun ReplyEditorBody(
    state: PrivateMessageReplyUiState,
    onContentChanged: (TextFieldValue) -> Unit,
    onToolbarAction: (BbcodeAction) -> Unit,
    onTogglePreview: () -> Unit,
    onErrorDismissed: () -> Unit,
    onDraftRestore: () -> Unit,
    onDraftDiscard: () -> Unit,
    onImagesPicked: (List<String>) -> Unit,
    onUploadErrorDismissed: () -> Unit,
    onManageRecipients: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // #459 — modern photo picker (no runtime permission), same contract as the topic-side editors.
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_UPLOAD),
    ) { uris ->
        if (uris.isNotEmpty()) onImagesPicked(uris.map { it.toString() })
    }
    // No outer scroll : the draft field is weighted so it stretches down to the bar (same
    // extensible-field design as the post editor) ; long content scrolls in the field's own
    // fillViewport column (#275/#410) and inside the preview pane.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // #618 (Bug 1) — owner-only COMPACT summary, replacing the inline member editor that used to
        // stack above the body and crowd the composer (unscrollable for a 29+-member DT). A tap opens
        // the dedicated RecipientManagerSheet; the body + send bar stay reachable. Hidden for a simple
        // participant / one-to-one MP (canManageRecipients is false).
        if (state.canManageRecipients) {
            MessageRecipientsSummary(
                count = state.recipients.size,
                onManage = onManageRecipients,
            )
            HorizontalDivider()
        }

        BbcodeToolbar(
            onAction = onToolbarAction,
            // #459 — upload wiring, same affordance as the topic-side editors.
            onImageUploadRequested = {
                pickImagesLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            uploading = state.isUploading,
        )
        // #459 — « n/N » batch counter while a multi-image upload is in flight.
        UploadProgressLabel(state.uploadProgress)

        BbcodeTextField(
            value = state.draft,
            onValueChange = onContentChanged,
            label = stringResource(R.string.messages_reply_field_label),
            placeholder = stringResource(R.string.messages_reply_field_placeholder),
            modifier = Modifier.weight(1f),
            // #275/#410 — grow-with-content field in its own scrollable viewport so the
            // cursor stays visible under the IME (typing AND refocus after the preview).
            fillViewport = true,
            // #459 — lock editing during a batch (caret must not move between two insertions).
            readOnly = state.isUploading,
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
            // Shares the stretch with the field (50/50) and scrolls internally.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                BbcodePreview(
                    content = state.preview,
                    mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED,
                )
            }
        }

        if (state.restorableDraft != null) {
            MessageDraftRestoreBanner(onRestore = onDraftRestore, onDiscard = onDraftDiscard)
        }

        state.submitError?.let { error ->
            Text(
                text = stringResource(error.bannerResId),
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
}
