package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorSubmissionSummary
import fr.forumhfr.redface2.core.ui.editor.EditorUnsavedCloseDialog
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsCallbacks
import fr.forumhfr.redface2.core.ui.editor.QuoteCardsColumn
import fr.forumhfr.redface2.core.ui.editor.bannerText
import fr.forumhfr.redface2.core.ui.editor.hasSubmissionSummary
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Vague 4 (#604) lot 1 — the quick-reply bottom sheet: a plain text field, Send, and a
 * full-screen escalation affordance. Deliberately NO toolbar, smileys, upload or preview
 * (cadrage Codex: those stay full-screen until lots 2-4). Local UI affordance — not a nav
 * route ; the ViewModel is scoped to the topic's nav entry, so the field survives an
 * accidental dismiss, and the #405 draft row survives everything else.
 */
@Suppress("LongParameterList") // Request, four outcome callbacks and the two opening seeds of the topic surface.
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun QuickReplySheet(
    request: QuickReplyRequest,
    onDismiss: () -> Unit,
    // #604 lot 3 — the escalation hands the armed cards over as full previews : the editor
    // renders the same cards (mockup P3) and needs author + excerpt, which only the topic
    // surface can snapshot. Riding the callback (→ the :app handoff), never the route.
    onEscalate: (quotes: List<QuoteSelection>) -> Unit,
    onSubmitted: (targetPage: Int?, scrollTo: Int?, quotedNumreponses: List<Int>) -> Unit,
    // #604 lots 2-3 — the cards this opening pre-arms : one for « Citer », the whole basket
    // for « Citer N » under the full-screen threshold (empty from the reply FAB).
    initialQuotes: List<QuoteSelection> = emptyList(),
    legacyQuoteRequestDropped: Boolean = false,
    // ED-88 (#1616) — the inline citations of this opening are durable in the draft row.
    onQuotesDelivered: () -> Unit = {},
) {
    val viewModel = hiltViewModel<QuickReplyViewModel, QuickReplyViewModel.Factory>(
        creationCallback = { factory -> factory.create(request) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Keyed on the VM ALONE — one `onSheetOpened` delivery per sheet composition. Keying on
    // `initialQuotes` too would restart the effect on any launch mutation and re-seed the field
    // from a possibly-stale row mid-typing (#805 cadrage, réserve n°1). A deliberate re-cite is
    // a new launch, hence a new composition, hence a fresh delivery — GUARANTEED structurally :
    // every `quickReplyFor` setter in TopicScreen (FAB, « Citer », « Citer N ») sits behind this
    // modal's scrim, so a new launch can only happen after a dismiss nulled the previous one
    // (gate Codex, finding 2). Re-assigning the launch over an OPEN sheet would break delivery.
    var openingDelivered by rememberSaveable { mutableStateOf(false) }
    val currentOnQuotesDelivered by rememberUpdatedState(onQuotesDelivered)
    LaunchedEffect(viewModel) {
        // Re-seed the field from the #405 row at EACH opening — the VM outlives the sheet and
        // its cached text can be stale after a full-screen edit of the same draft (gate #788).
        // The VM outlives the sheet (topic-scoped) : hand it the page of THIS opening (#1243 suite).
        viewModel.onSheetAttached(
            request.page, initialQuotes, restored = openingDelivered,
            legacyQuoteRequestDropped = legacyQuoteRequestDropped,
        )
        openingDelivered = true
        // Attachment advances the opening id only for a new sheet; both flows filter stale signals.
        // Collect delivery before navigation: restoration retains its delivery before its close.
        launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.quotesDelivered.collect { currentOnQuotesDelivered() }
        }
        viewModel.effects.collect { effect ->
            when (effect) {
                is QuickReplyEffect.SubmitSucceeded ->
                    onSubmitted(effect.targetPage, effect.scrollTo, effect.quotedNumreponses)
                is QuickReplyEffect.EscalateToFullEditor -> onEscalate(effect.quotes)
                // ED-10 (#1450) — the ONLY place a plain close unmounts the sheet.
                is QuickReplyEffect.CloseCommitted -> onDismiss()
            }
        }
    }
    val timeoutMillis = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        4_000L, containsIcons = false, containsText = true, containsControls = true,
    ) ?: 4_000L
    val armToken = (state.submission as? EditorSubmissionState.Armed)?.token
    val fullScreenLabel = stringResource(R.string.quick_reply_fullscreen)
    val focusRequester = remember { FocusRequester() }
    // ED-161 / gate #788 — retain the sheet through the POST and accepted-success cleanup:
    // its effect collector must survive until SubmitSucceeded, without replay at the next opening.
    // `submitting` is read through
    // rememberUpdatedState because both guards below are remembered once.
    val submitting = rememberUpdatedState(state.submission.blocksBodyMutation || state.isDiscardingDraft)
    val closing = rememberUpdatedState(state.isClosing)
    // #854 — no half-height stop : this is a TYPING surface (autofocused field, IME quasi
    // permanent), the M3 PartiallyExpanded state only ever appears when a short display makes
    // the content taller than half the screen — and then a back/swipe must dismiss in ONE step,
    // not park the sheet at mid-height (thibw : « 3× retour pour revenir au topic »).
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // M3 also calls show() on recreation: a pending close stays hidden until « Rester ».
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden) !submitting.value else !closing.value
        },
    )
    val sheetScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val imeHeightDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
    val bodyLineHeight = MaterialTheme.typography.bodyLarge.lineHeight
    val fieldLineHeightDp = if (bodyLineHeight.isSp) {
        with(density) { bodyLineHeight.toDp().value }
    } else {
        QUICK_REPLY_FIELD_FALLBACK_LINE_HEIGHT_DP
    }
    val fieldMaxLines = quickReplyFieldMaxLines(
        windowHeightDp = screenHeightDp,
        imeHeightDp = imeHeightDp,
        lineHeightDp = fieldLineHeightDp,
    )
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            // ED-10 (#1450) — closing is a REQUEST. material3 calls this once the sheet has slid
            // away ; it stays composed (hidden) while the ViewModel awaits the last draft write,
            // and only CloseCommitted unmounts it. material3 also calls this after a vetoed back
            // (settleToDismiss ignores the veto's outcome) : bring the sheet back rather than
            // leave an invisible modal over the topic.
            if (submitting.value) sheetScope.launch { sheetState.show() } else viewModel.onDismissed()
        },
    ) {
        // These owners belong to the sheet's dialog window, not the topic behind it.
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(state.isClosing) {
            if (state.isClosing) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                sheetState.hide()
            } else {
                // M3 can also start show() after measuring its anchors. Either animation may
                // cancel the other; observe the resulting expansion before focusing the field.
                launch { sheetState.show() }
                snapshotFlow { sheetState.currentValue }.first { it == SheetValue.Expanded }
                focusRequester.requestFocus()
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // #855 — only the FIELDS scroll ; « Envoyer » is pinned OUTSIDE the scroll, always
            // visible above the IME. Before this, the whole column scrolled (#604 lot 4a) and on
            // a short display the send button silently sat below the keyboard fold (thibw).
            // weight(fill = false) : take AT MOST the space above the pinned row, never stretch
            // the sheet taller than its natural content on a roomy display.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickReplyHeader(state, fullScreenLabel, onEscalate = viewModel::onEscalateRequested)
                // #604 lot 4a — shared column : cards + live-region announcements + post-removal
                // focus (always composed ; renders nothing visible without cards).
                // #808 — the cards block is CAPPED and scrolls internally so a heavy selection can
                // never push the field and « Envoyer » under the IME fold : the field is the priority
                // surface. Same pattern as the editor's EditorQuoteCards (192dp) and
                // RecipientManagerSheet. Unconditional cap (no isImeVisible gate) : the field
                // autofocuses on open so the IME is quasi-permanent here, and the cap also covers
                // landscape ; nested same-direction scroll has a working precedent in
                // RecipientManagerSheet.
                QuoteCardsColumn(
                    quotes = state.quotes,
                    enabled = !state.isSubmitting,
                    callbacks = QuoteCardsCallbacks(
                        onMoveUp = { numreponse -> viewModel.onQuoteMoved(numreponse, delta = -1) },
                        onMoveDown = { numreponse -> viewModel.onQuoteMoved(numreponse, delta = 1) },
                        onRemove = viewModel::onQuoteRemoved,
                    ),
                    modifier = Modifier
                        .heightIn(max = QUICK_REPLY_MAX_CARDS_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .testTag("quick_reply_quote_cards"),
                )
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChanged,
                    readOnly = state.submission.blocksBodyMutation || state.isDiscardingDraft || state.isClosing,
                    // #807 — same #237 contract as BbcodeTextField : Compose capitalises NOTHING by
                    // default, the IME needs the explicit autoCap hint (surface regression, v220).
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    placeholder = { Text(stringResource(R.string.quick_reply_hint)) },
                    minLines = QUICK_REPLY_FIELD_MIN_LINES,
                    maxLines = fieldMaxLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                QuickReplyStatus(
                    state,
                    QuickReplyQuoteCallbacks(
                        onReload = viewModel::onQuoteReloadRequested,
                        onIgnore = viewModel::onPendingQuoteIgnored,
                        onResume = viewModel::onDormantQuoteResumed,
                        onRestoreDraft = viewModel::onDraftRestoreRequested,
                        onDiscardDraft = viewModel::onDraftDiscardRequested,
                        onDismissLegacy = viewModel::onLegacyQuoteNoticeDismissed,
                    ),
                )
            }
            QuickReplyActions(state, onSubmit = { viewModel.onSubmitClicked(timeoutMillis) })
        }
    }
    QuickReplyConfirmationDialog(
        visible = state.confirmVisible,
        onConfirm = { viewModel.onSubmitConfirmed(armToken) },
        onDismiss = viewModel::onSubmitConfirmDismissed,
    )
    QuickReplyUnsavedCloseDialog(
        visible = state.unsavedCloseVisible,
        onStay = { viewModel.onUnsavedCloseDecided(leave = false) },
        onLeave = { viewModel.onUnsavedCloseDecided(leave = true) },
    )
}

/**
 * ED-10 on the sheet (#1450, option A chosen by XaTriX on 13/11) : the editors' own
 * « Brouillon non enregistré » dialog, shown over the topic while the refused sheet is hidden ;
 * « Rester » brings the sheet back and saves again, « Quitter sans enregistrer » lets it go.
 */
@Composable
internal fun QuickReplyUnsavedCloseDialog(visible: Boolean, onStay: () -> Unit, onLeave: () -> Unit) {
    if (visible) EditorUnsavedCloseDialog(onStay = onStay, onLeave = onLeave)
}

@Composable
internal fun QuickReplyHeader(state: QuickReplyUiState, fullScreenLabel: String, onEscalate: () -> Unit) {
    val offerReason = stringResource(R.string.quick_reply_fullscreen_pending_draft)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.quick_reply_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onEscalate,
            // Gate #788 — no escalation while a POST is in flight (submit vs navigation
            // race) ; #805 — nor while a [quotemsg] insert is being fetched.
            enabled = !state.isSubmitting && !state.isPreparingQuotes && !state.isDiscardingDraft &&
                !state.quoteStorageUnavailable && state.restorableDraft == null,
            modifier = Modifier.semantics {
                contentDescription = fullScreenLabel
                if (state.restorableDraft != null) stateDescription = offerReason
            },
        ) {
            RedfaceVectorIcon(resId = fr.forumhfr.redface2.core.ui.R.drawable.ic_ms_open_in_new)
        }
    }
}

internal data class QuickReplyQuoteCallbacks(
    val onReload: () -> Unit,
    val onIgnore: () -> Unit,
    val onResume: () -> Unit,
    val onRestoreDraft: () -> Unit,
    val onDiscardDraft: () -> Unit,
    val onDismissLegacy: () -> Unit,
)

@Suppress("CyclomaticComplexMethod") // One flat `if` per independent status row of the sheet.
@Composable
internal fun QuickReplyStatus(state: QuickReplyUiState, callbacks: QuickReplyQuoteCallbacks) {
    if (state.legacyQuoteRequestDropped) {
        Text(stringResource(R.string.quick_reply_legacy_quote_dropped), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = callbacks.onDismissLegacy) {
            Text(stringResource(R.string.quick_reply_notice_dismiss))
        }
    }
    if (state.restorableDraft != null) {
        Text(stringResource(R.string.quick_reply_draft_found), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = callbacks.onRestoreDraft, enabled = !state.isDiscardingDraft) {
                Text(stringResource(R.string.quick_reply_draft_restore))
            }
            TextButton(onClick = callbacks.onDiscardDraft, enabled = !state.isDiscardingDraft) {
                Text(stringResource(R.string.quick_reply_draft_ignore))
            }
        }
    }
    if (state.pendingQuotes.isNotEmpty() && !state.isPreparingQuotes) {
        Text(stringResource(R.string.quick_reply_quote_pending), style = MaterialTheme.typography.bodySmall)
    }
    // #805 cards OFF — the [quotemsg] fetch runs at opening : typing stays enabled (the
    // insert concatenates onto the live field), only send/escalate wait for it.
    if (state.isPreparingQuotes) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.quick_reply_preparing_quote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.submitError?.let { error -> QuickReplyErrorBanner(error) }
    if (state.hasDormantQuote) {
        TextButton(onClick = callbacks.onResume, enabled = !state.isPreparingQuotes) {
            Text(stringResource(R.string.quick_reply_resume_quote))
        }
    }
    val quoteNeedsReload = state.pendingQuotes.isNotEmpty() && state.restorableDraft == null && !state.hasDormantQuote
    if (!state.isLoadingDraft && (state.quoteStorageUnavailable || quoteNeedsReload)) {
        TextButton(onClick = callbacks.onReload, enabled = !state.isPreparingQuotes && !state.isDiscardingDraft) {
            Text(stringResource(fr.forumhfr.redface2.core.ui.R.string.editor_action_reload))
        }
    }
    if (state.canIgnorePendingQuote) {
        TextButton(onClick = callbacks.onIgnore) {
            Text(stringResource(fr.forumhfr.redface2.core.ui.R.string.editor_action_ignore_quote))
        }
    }
}

@Composable
private fun QuickReplyActions(state: QuickReplyUiState, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isSubmitting) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        }
        TextButton(onClick = onSubmit, enabled = state.canSubmit) {
            Text(stringResource(R.string.quick_reply_send))
        }
    }
}

@Composable
private fun QuickReplyConfirmationDialog(visible: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.quick_reply_confirm_title)) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.quick_reply_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.quick_reply_confirm_cancel))
                }
            },
        )
    }
}

/** The sheet's submission summary and verification detail, in the existing fields column. */
@Composable
internal fun QuickReplyErrorBanner(error: EditorError) {
    if (error.hasSubmissionSummary) EditorSubmissionSummary(error)
    Text(
        text = error.bannerText(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

// #604 lot 3 — QuoteCard / QuoteCardControls promoted to `:core:ui` (core.ui.editor.QuoteCards):
// the full-screen editor renders the same cards (mockup P3), one rendering for both surfaces.

/**
 * #808 — cap of the quote-cards block inside the sheet : two full one-line cards (2 x 48dp
 * touch-target rows + 8dp spacing) plus the hint of a third, so the field and « Envoyer » always
 * stay reachable with the IME up. The editor's sibling cap is 192dp (~4 cards) — the sheet is a
 * tighter surface, it gets the smaller budget. `internal` : QuickReplyQuoteCardsCapTest mounts
 * the capped block with this exact value to pin the budget.
 */
internal val QUICK_REPLY_MAX_CARDS_HEIGHT = 112.dp
