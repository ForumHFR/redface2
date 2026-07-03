package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.model.write.QuotedPostPreview
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon

/**
 * Vague 4 (#604) lot 1 — the quick-reply bottom sheet: a plain text field, Send, and a
 * full-screen escalation affordance. Deliberately NO toolbar, smileys, upload or preview
 * (cadrage Codex: those stay full-screen until lots 2-4). Local UI affordance — not a nav
 * route ; the ViewModel is scoped to the topic's nav entry, so the field survives an
 * accidental dismiss, and the #405 draft row survives everything else.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun QuickReplySheet(
    request: QuickReplyRequest,
    onDismiss: () -> Unit,
    onEscalate: (quoteNumreponses: List<Int>) -> Unit,
    onSubmitted: (targetPage: Int?, scrollTo: Int?) -> Unit,
    // #604 lot 2 — « Citer » opens the sheet with this card pre-armed (1-citation session).
    initialQuote: QuotedPostPreview? = null,
) {
    val viewModel = hiltViewModel<QuickReplyViewModel, QuickReplyViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        // Re-seed the field from the #405 row at EACH opening — the VM outlives the sheet and
        // its cached text can be stale after a full-screen edit of the same draft (gate #788).
        viewModel.onSheetOpened(initialQuote)
        viewModel.effects.collect { effect ->
            when (effect) {
                is QuickReplyEffect.SubmitSucceeded -> onSubmitted(effect.targetPage, effect.scrollTo)
                is QuickReplyEffect.EscalateToFullEditor -> onEscalate(effect.quoteNumreponses)
            }
        }
    }
    val fullScreenLabel = stringResource(R.string.quick_reply_fullscreen)
    val focusRequester = remember { FocusRequester() }
    // Gate #788 — the sheet must not dismiss while a POST is in flight: the effect collector
    // lives here, so tearing the sheet down mid-submit would drop SubmitSucceeded (no topic
    // refresh) or replay it at the next opening. `submitting` is read through
    // rememberUpdatedState because both guards below are remembered once.
    val submitting = rememberUpdatedState(state.isSubmitting)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target -> target != SheetValue.Hidden || !submitting.value },
    )
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            if (!submitting.value) {
                viewModel.onDismissed()
                onDismiss()
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.quick_reply_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = viewModel::onEscalateRequested,
                    // Gate #788 — no escalation while a POST is in flight (submit vs navigation race).
                    enabled = !state.isSubmitting,
                    modifier = Modifier.semantics { contentDescription = fullScreenLabel },
                ) {
                    RedfaceVectorIcon(
                        resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_open_in_new,
                    )
                }
            }
            state.quotes.forEachIndexed { index, quote ->
                QuoteCard(
                    quote = quote,
                    controls = QuoteCardControls(
                        canMoveUp = index > 0,
                        canMoveDown = index < state.quotes.lastIndex,
                        enabled = !state.isSubmitting,
                        onMoveUp = { viewModel.onQuoteMoved(quote.numreponse, delta = -1) },
                        onMoveDown = { viewModel.onQuoteMoved(quote.numreponse, delta = 1) },
                        onRemove = { viewModel.onQuoteRemoved(quote.numreponse) },
                    ),
                )
            }
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                enabled = !state.isSubmitting,
                placeholder = { Text(stringResource(R.string.quick_reply_hint)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            state.submitError?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                }
                TextButton(
                    onClick = viewModel::onSubmitClicked,
                    enabled = state.canSubmit,
                ) {
                    Text(stringResource(R.string.quick_reply_send))
                }
            }
        }
    }
    // The field grabs the focus once per sheet opening — the IME rises with the sheet.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    if (state.confirmVisible) {
        AlertDialog(
            onDismissRequest = viewModel::onSubmitConfirmDismissed,
            title = { Text(stringResource(R.string.quick_reply_confirm_title)) },
            confirmButton = {
                TextButton(onClick = viewModel::onSubmitConfirmed) {
                    Text(stringResource(R.string.quick_reply_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onSubmitConfirmDismissed) {
                    Text(stringResource(R.string.quick_reply_confirm_cancel))
                }
            },
        )
    }
}

/** The same wording as the full editor's error banner, module-local copies (feature/topic res). */
internal fun QuickReplySubmitError.messageRes(): Int = when (this) {
    QuickReplySubmitError.Network -> R.string.quick_reply_error_network
    QuickReplySubmitError.SessionExpired -> R.string.quick_reply_error_session_expired
    is QuickReplySubmitError.Hfr -> when (reason) {
        ReplyFailureReason.EmptyMessage -> R.string.quick_reply_error_empty
        ReplyFailureReason.InvalidHashCheck -> R.string.quick_reply_error_invalid_hash
        ReplyFailureReason.AntiFlood -> R.string.quick_reply_error_anti_flood
        ReplyFailureReason.TopicLocked -> R.string.quick_reply_error_topic_locked
        ReplyFailureReason.LoginRequired -> R.string.quick_reply_error_login_required
        ReplyFailureReason.Unknown -> R.string.quick_reply_error_unknown
    }
}

/**
 * #604 lot 2 — one quote card: « ❝ author — excerpt » on a single line, with reorder and remove
 * affordances. Reordering is up/down buttons by design (cadrage : a11y first, drag deferred) ;
 * first/last are disabled instead of hidden so TalkBack users hear a stable layout.
 */
/** Reorder/remove affordances of one card, bundled for detekt's parameter budget. */
private data class QuoteCardControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val enabled: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun QuoteCard(
    quote: QuotedPostPreview,
    controls: QuoteCardControls,
) {
    val moveUpLabel = stringResource(R.string.quick_reply_quote_move_up, quote.author)
    val moveDownLabel = stringResource(R.string.quick_reply_quote_move_down, quote.author)
    val removeLabel = stringResource(R.string.quick_reply_quote_remove, quote.author)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.quick_reply_quote_line,
                    quote.author,
                    quote.excerpt.ifBlank { stringResource(R.string.quick_reply_quote_no_text) },
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            QuoteCardAction(
                enabled = controls.enabled && controls.canMoveUp,
                label = moveUpLabel,
                glyph = "↑",
                onClick = controls.onMoveUp,
            )
            QuoteCardAction(
                enabled = controls.enabled && controls.canMoveDown,
                label = moveDownLabel,
                glyph = "↓",
                onClick = controls.onMoveDown,
            )
            QuoteCardAction(
                enabled = controls.enabled,
                label = removeLabel,
                glyph = "✕",
                onClick = controls.onRemove,
            )
        }
    }
}

@Composable
private fun QuoteCardAction(
    enabled: Boolean,
    label: String,
    glyph: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium)
    }
}
