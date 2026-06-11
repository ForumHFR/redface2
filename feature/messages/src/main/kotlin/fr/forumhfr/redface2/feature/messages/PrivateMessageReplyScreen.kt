package fr.forumhfr.redface2.feature.messages

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
            }
        }
    }
    PrivateMessageReplyContent(
        state = state,
        onBack = onBack,
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
        modifier = modifier,
    )
}

@Composable
@Suppress("LongParameterList") // One callback per editor action — each is wired to a distinct VM method.
private fun PrivateMessageReplyContent(
    state: PrivateMessageReplyUiState,
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
    modifier: Modifier = Modifier,
) {
    var optionsSheetOpen by remember { mutableStateOf(false) }
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
    modifier: Modifier = Modifier,
) {
    // No outer scroll : the draft field is weighted so it stretches down to the bar (same
    // extensible-field design as the post editor) ; long content scrolls in the field's own
    // fillViewport column (#275/#410) and inside the preview pane.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BbcodeToolbar(onAction = onToolbarAction)

        BbcodeTextField(
            value = state.draft,
            onValueChange = onContentChanged,
            label = stringResource(R.string.messages_reply_field_label),
            placeholder = stringResource(R.string.messages_reply_field_placeholder),
            modifier = Modifier.weight(1f),
            // #275/#410 — grow-with-content field in its own scrollable viewport so the
            // cursor stays visible under the IME (typing AND refocus after the preview).
            fillViewport = true,
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
                BbcodePreview(content = state.preview)
            }
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
    }
}
