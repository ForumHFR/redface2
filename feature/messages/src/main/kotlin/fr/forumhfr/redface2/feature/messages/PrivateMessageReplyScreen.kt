package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.BbcodeToolbar
import fr.forumhfr.redface2.core.ui.editor.EditorOptionsSheet
import kotlinx.coroutines.delay

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
            ReplyHeader(onBack = onBack)
            when {
                state.formError -> FormErrorState(onRetry = onRetryFormLoad)
                state.isLoadingForm && !state.formAvailable -> FormLoadingState()
                else -> {
                    ReplyEditorBody(
                        state = state,
                        onContentChanged = onContentChanged,
                        onToolbarAction = onToolbarAction,
                        onTogglePreview = onTogglePreview,
                        onErrorDismissed = onErrorDismissed,
                        modifier = Modifier.weight(1f),
                    )
                    ReplySubmitBar(
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
                ReplyOptions(
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
private fun ReplyHeader(onBack: () -> Unit) {
    val backLabel = stringResource(R.string.messages_back)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backLabel },
        ) {
            // dp-sized vector instead of a text « ← » glyph (font/baseline-dependent, cf. Codex
            // review). a11y label on the IconButton; the icon is decorative.
            Icon(
                painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = stringResource(R.string.messages_reply_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FormLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FormErrorState(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.messages_reply_form_error),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.messages_retry))
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BbcodeToolbar(onAction = onToolbarAction)

        BbcodeTextField(
            value = state.draft,
            onValueChange = onContentChanged,
            label = stringResource(R.string.messages_reply_field_label),
            placeholder = stringResource(R.string.messages_reply_field_placeholder),
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
            BbcodePreview(content = state.preview)
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

/** How long the armed « Confirmer ? » state survives without a second tap (#312 v2). */
private const val CONFIRM_DISARM_DELAY_MS = 4_000L

/**
 * Send button pinned to the bottom, lifted above the IME so the user never dismisses the keyboard to
 * reach « Envoyer ». Mirrors the post editor's submit bar (the editor's `EditorSubmitBar` is module-
 * private, so the window-insets pattern is replicated here). Requires `windowSoftInputMode=adjustNothing`.
 * #312 v2 : the confirmation preference arms the button (« Confirmer ? », tertiary colors) instead of
 * raising the old modal dialog — the SECOND tap sends, and the armed state auto-disarms after a delay.
 */
@Composable
@Suppress("LongParameterList") // Mirrors the editor bar's state + actions.
private fun ReplySubmitBar(
    canSubmit: Boolean,
    isSubmitting: Boolean,
    confirmArmed: Boolean,
    onSubmit: () -> Unit,
    onConfirmSubmit: () -> Unit,
    onDisarmConfirm: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    // Auto-disarm : an armed confirmation that is not confirmed within the delay silently
    // falls back to « Envoyer » (same ViewModel intent as dismissing the old dialog).
    // Keyed on the flag so any re-arm restarts the clock.
    LaunchedEffect(confirmArmed) {
        if (confirmArmed) {
            delay(CONFIRM_DISARM_DELAY_MS)
            onDisarmConfirm()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Single bottom inset = max(navBar, ime) via union(), so the two never stack into a
                    // phantom gap. Keyboard closed → clears the gesture nav bar; open → rides on the IME.
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.ime)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenOptions) {
                    Text(text = stringResource(R.string.messages_reply_actions_options))
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Button(
                    enabled = canSubmit,
                    onClick = if (confirmArmed) onConfirmSubmit else onSubmit,
                    colors = if (confirmArmed) {
                        // Distinct (but not destructive) palette : the second tap is a
                        // deliberate confirmation, not a warning.
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (confirmArmed) {
                                R.string.messages_reply_submit_confirm
                            } else {
                                R.string.messages_reply_submit
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList") // 3 toggles + 3 callbacks + enabled — each call-site distinct.
private fun ReplyOptions(
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
            text = stringResource(R.string.messages_reply_options_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionToggle(
            label = stringResource(R.string.messages_reply_option_signature),
            checked = signatureEnabled,
            enabled = enabled,
            onCheckedChange = onSignatureChanged,
        )
        OptionToggle(
            label = stringResource(R.string.messages_reply_option_smiley_disabled),
            checked = smileyDisabled,
            enabled = enabled,
            onCheckedChange = onSmileyDisabledChanged,
        )
        OptionToggle(
            label = stringResource(R.string.messages_reply_option_email_notification),
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
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private val PrivateMessageReplyError.bannerResId: Int
    get() = when (this) {
        PrivateMessageReplyError.Empty -> R.string.messages_reply_error_empty
        PrivateMessageReplyError.InvalidHashCheck -> R.string.messages_reply_error_invalid_hash
        PrivateMessageReplyError.AntiFlood -> R.string.messages_reply_error_anti_flood
        PrivateMessageReplyError.LoginRequired -> R.string.messages_reply_error_login_required
        PrivateMessageReplyError.Network -> R.string.messages_reply_error_network
        PrivateMessageReplyError.SessionExpired -> R.string.messages_reply_error_session_expired
        PrivateMessageReplyError.Unexpected -> R.string.messages_reply_error_unexpected
    }
