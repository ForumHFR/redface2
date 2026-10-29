package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.ui.R

/** The cell keeps its insertion semantics; only eligible, idle cells expose a favorite gesture. */
@Composable
internal fun SmileyFavoriteTarget(
    smiley: EditorSmiley,
    state: SmileyPickerState.Open?,
    onClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val action = state?.favoriteAction(smiley)
    val label = when (action) {
        SmileyFavoriteAction.ADD -> stringResource(R.string.editor_smiley_favorite_add)
        SmileyFavoriteAction.REMOVE -> stringResource(R.string.editor_smiley_favorite_remove)
        null -> null
    }
    var menuOpen by remember(smiley.token) { mutableStateOf(false) }
    // Clear a previously opened menu when another cell starts a mutation.
    LaunchedEffect(action) { menuOpen = false }
    val changeFavorite = { state?.onFavoriteChange?.invoke(smiley) }
    val gesture = if (label == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { menuOpen = true },
            onLongClickLabel = label,
        ).semantics {
            customActions = listOf(CustomAccessibilityAction(label) { changeFavorite(); true })
        }
    }
    val pending = state?.pendingFavoriteToken == smiley.token
    val progressLabel = stringResource(R.string.editor_smiley_favorite_pending)
    Box {
        content(gesture.semantics { if (pending) stateDescription = progressLabel })
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
        DropdownMenu(expanded = menuOpen && label != null, onDismissRequest = { menuOpen = false }) {
            if (label != null) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; changeFavorite() },
                )
            }
        }
    }
}

/** A persistent, polite status inside the sheet is readable from either tab and after reopening. */
@Composable
internal fun SmileyFavoriteStatus(feedback: SmileyFavoriteFeedback) {
    Text(
        text = smileyFavoriteMessage(feedback),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun smileyFavoriteMessage(feedback: SmileyFavoriteFeedback): String = when (feedback) {
    SmileyFavoriteFeedback.Added -> stringResource(R.string.editor_smiley_favorite_added)
    SmileyFavoriteFeedback.Removed -> stringResource(R.string.editor_smiley_favorite_removed)
    SmileyFavoriteFeedback.AlreadyPresent -> stringResource(R.string.editor_smiley_favorite_already_present)
    is SmileyFavoriteFeedback.Full -> stringResource(R.string.editor_smiley_favorite_full, feedback.limit)
    SmileyFavoriteFeedback.NotFound -> stringResource(R.string.editor_smiley_favorite_not_found)
    SmileyFavoriteFeedback.ListChanged -> stringResource(R.string.editor_smiley_favorite_list_changed)
    SmileyFavoriteFeedback.NotAuthenticated -> stringResource(R.string.editor_smiley_favorite_session_expired)
    SmileyFavoriteFeedback.TransportFailure -> stringResource(R.string.editor_smiley_favorite_network_error)
    SmileyFavoriteFeedback.UnknownResponse -> stringResource(R.string.editor_smiley_favorite_unknown_response)
}
