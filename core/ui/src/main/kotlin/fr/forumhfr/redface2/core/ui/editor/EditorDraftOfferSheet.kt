package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftOffer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.sheet.settledSheetWindowInsets

/**
 * Shared full-screen editors' draft offer; quick reply keeps its own chrome (R4).
 * Keep mounted with [isOpen] = EditorHost.draftOfferOpen and [onDismiss] = EditorHost.closeDraftOffer.
 * [offerAvailable] is the live offer's presence, independent of its open state: losing it closes
 * the sheet without treating dismissal as a decision.
 *
 * Dismissal (swipe, Back, outside tap or close button) is never a draft decision. The host owns the
 * exactly-once deferred focus/selection return through its usual consumeReturn/returnToBody effect.
 * Restore and single-offer discard callbacks own their guarded intent and closing. Do not also
 * render this offer through EditorHeaderPanel/EditorEventDetails: those panels have their own focus-return effect.
 *
 * ED-41 — a non-empty [offers] replaces the single-offer body by one row per draft, each decided
 * through [onRestoreOffer]/[onDiscardOffer] with its offer id; both default to the single-offer
 * callbacks for hosts that only ever hold one. A listed discard keeps the sheet open until the
 * confirmed state has no offer left; keep the offer available while its write is pending or failed.
 * [canDecideOffer] applies the session's per-version guard, including a Restore awaiting a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // Two host states, independent decisions/guards and optional localized content.
fun EditorDraftOfferSheet(
    isOpen: Boolean,
    offerAvailable: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    offers: List<DraftOffer> = emptyList(),
    onRestoreOffer: (String) -> Unit = { onRestore() },
    onDiscardOffer: (String) -> Unit = { onDiscard() },
    restoreEnabled: Boolean = true,
    discardEnabled: Boolean = true,
    title: String = stringResource(R.string.editor_draft_offer_title),
    message: String = stringResource(R.string.editor_draft_offer_message),
    canDecideOffer: (String) -> Boolean = { true },
) {
    val dismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(isOpen, offerAvailable) {
        if (isOpen && !offerAvailable) dismiss.value()
    }
    if (!isOpen || !offerAvailable) return

    // Open fully expanded, including compact height; scroll rather than truncate large text.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Tagbug DEV #2805926: ↺ is tapped over the keyboard; size by its destination, not by its retraction.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = modifier.testTag("editor-draft-offer-sheet"),
        contentWindowInsets = { settledSheetWindowInsets() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                EditorActionIcon(R.drawable.ic_close, stringResource(R.string.editor_draft_offer_close), onDismiss)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (offers.isEmpty()) EditorDraftOfferDecisions(onRestore, onDiscard, restoreEnabled, discardEnabled)
            else EditorDraftOfferList(offers, onRestoreOffer, onDiscardOffer) { id ->
                val enabled = canDecideOffer(id)
                EditorDraftOfferDecisionState(restoreEnabled && enabled, discardEnabled && enabled)
            }
        }
    }
}

/**
 * ED-40: offered versions, most recent first, each with its own date, a two-line excerpt and its
 * decisions. Also used inline on the MP form-failure surface, which has no chrome to open a sheet.
 * The excerpt is recomputed from the offer and never saved (ED-81).
 * [decisions] combines the host's global and per-offer guards for each row.
 */
@Composable
fun EditorDraftOfferList(
    offers: List<DraftOffer>,
    onRestore: (String) -> Unit,
    onDiscard: (String) -> Unit,
    decisions: (String) -> EditorDraftOfferDecisionState = { EditorDraftOfferDecisionState() },
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    offers.sortedWith(compareByDescending<DraftOffer> { it.draft.updatedAt }.thenByDescending { it.sourceVersion })
        .forEach { offer ->
            Column(Modifier.fillMaxWidth().testTag("editor-draft-offer-${offer.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val date = editorDraftOfferDate(offer.draft.updatedAt, now).resolve()
                val excerpt = editorDraftOfferExcerpt(offer.draft.body, context)
                Text(date, style = MaterialTheme.typography.labelLarge)
                // A header-only offer (title or recipients, blank body) has no excerpt line.
                if (excerpt.isNotEmpty()) Text(excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                val decisionState = decisions(offer.id)
                EditorDraftOfferDecisions({ onRestore(offer.id) }, { onDiscard(offer.id) },
                    decisionState.restoreEnabled, decisionState.discardEnabled,
                    listOf(date, excerpt).filter(String::isNotEmpty).joinToString())
            }
        }
}

@Composable
private fun EditorDraftOfferDecisions(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    restoreEnabled: Boolean,
    discardEnabled: Boolean,
    description: String? = null,
) {
    val restoreDescription = description?.let { stringResource(R.string.editor_draft_restore_description, it) }
    val discardDescription = description?.let { stringResource(R.string.editor_draft_discard_description, it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRestore, enabled = restoreEnabled, modifier = Modifier.heightIn(min = 48.dp).semantics {
            if (restoreDescription != null) contentDescription = restoreDescription
        }) {
            Text(stringResource(R.string.editor_draft_offer_restore))
        }
        TextButton(onClick = onDiscard, enabled = discardEnabled, modifier = Modifier.heightIn(min = 48.dp).semantics {
            if (discardDescription != null) contentDescription = discardDescription
        }) {
            Text(stringResource(R.string.editor_draft_offer_discard))
        }
    }
}
