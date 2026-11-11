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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * Shared full-screen editors' draft offer; quick reply keeps its own chrome (R4).
 * Keep mounted with [isOpen] = EditorHost.draftOfferOpen and [onDismiss] = EditorHost.closeDraftOffer.
 * [offerAvailable] is the live offer's presence, independent of its open state: losing it closes
 * either entry, including an event detail whose delivery has already been retired.
 *
 * Dismissal (swipe, Back, outside tap or close button) is never a draft decision. The host owns the
 * exactly-once deferred focus/selection return through its usual consumeReturn/returnToBody effect.
 * Restore/discard callbacks own their guarded intent and closing. Do not also render this offer
 * through EditorHeaderPanel/EditorEventDetails: those panels have their own focus-return effect.
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
    pendingQuote: String? = null,
    restoreEnabled: Boolean = true,
    discardEnabled: Boolean = true,
    title: String = stringResource(R.string.editor_draft_offer_title),
    message: String = stringResource(R.string.editor_draft_offer_message),
) {
    val dismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(isOpen, offerAvailable) {
        if (isOpen && !offerAvailable) dismiss.value()
    }
    if (!isOpen || !offerAvailable) return

    // Open fully expanded, including compact height; scroll rather than truncate large text.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        modifier = modifier.testTag("editor-draft-offer-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                EditorActionIcon(R.drawable.ic_close, stringResource(R.string.editor_draft_offer_close), onDismiss)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (pendingQuote != null) Text(pendingQuote, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore, enabled = restoreEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.editor_draft_offer_restore))
                }
                TextButton(onClick = onDiscard, enabled = discardEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.editor_draft_offer_discard))
                }
            }
        }
    }
}
