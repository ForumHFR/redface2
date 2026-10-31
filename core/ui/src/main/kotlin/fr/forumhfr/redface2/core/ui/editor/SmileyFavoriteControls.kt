package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
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
import kotlinx.coroutines.delay

/** Local and HFR eligibility are independent; insertion remains available during either mutation. */
@Composable
internal fun SmileyFavoriteTarget(
    smiley: EditorSmiley,
    state: SmileyPickerState.Open?,
    onClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val onDetailChange = state?.onDetailChange
    val detailLabel = stringResource(R.string.editor_smiley_detail_show)
    val actions = buildList {
        if (onDetailChange != null) {
            add(SmileyAccessibilityAction(detailLabel) { onDetailChange(smiley) })
        }
        state?.localFavoriteAction(smiley)?.let { action ->
            val label = when (action) {
                SmileyFavoriteAction.ADD -> stringResource(R.string.editor_smiley_local_favorite_add)
                SmileyFavoriteAction.REMOVE -> stringResource(R.string.editor_smiley_local_favorite_remove)
            }
            add(SmileyAccessibilityAction(label) { state?.onLocalFavoriteChange?.invoke(smiley) })
        }
        state?.favoriteAction(smiley)?.let { action ->
            val label = when (action) {
                SmileyFavoriteAction.ADD -> stringResource(R.string.editor_smiley_favorite_add)
                SmileyFavoriteAction.REMOVE -> stringResource(R.string.editor_smiley_favorite_remove)
            }
            add(SmileyAccessibilityAction(label) { state?.onFavoriteChange?.invoke(smiley) })
        }
    }
    val pending = state?.pendingFavoriteToken == smiley.token
    val progressLabel = stringResource(R.string.editor_smiley_favorite_pending)
    val progress = Modifier.semantics { if (pending) stateDescription = progressLabel }
    val gestures = if (onDetailChange == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { onDetailChange(smiley) },
            onLongClickLabel = detailLabel,
        )
    }
    Box {
        content(
            gestures.semantics {
                if (actions.isNotEmpty()) {
                    customActions = actions.map { action ->
                        CustomAccessibilityAction(action.label) { action.invoke(); true }
                    }
                }
            }.then(progress),
        )
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
    }
}

private data class SmileyAccessibilityAction(val label: String, val invoke: () -> Unit)

/**
 * A brief Standard/Wiki overlay, excluded from measurement and keyed per favorite gesture.
 * Calls [onExpired] after the accessibility-recommended short snackbar timeout.
 * The sheet retains expiration for this opening so tab changes cannot revive an expired overlay.
 */
@Composable
internal fun SmileyFavoriteOverlay(
    feedback: SmileyFavoriteFeedback,
    onExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(feedback, accessibilityManager) {
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            4000L, containsIcons = false, containsText = true, containsControls = false,
        ) ?: 4000L
        delay(timeout)
        onExpired()
    }
    Box(modifier = modifier.padding(8.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            SmileyFavoriteStatus(feedback, Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** All three tabs expose the same polite announcement for the current opening. */
@Composable
internal fun SmileyFavoriteStatus(feedback: SmileyFavoriteFeedback, modifier: Modifier = Modifier) {
    Text(
        text = smileyFavoriteMessage(feedback),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun smileyFavoriteMessage(feedback: SmileyFavoriteFeedback): String = when (feedback) {
    SmileyFavoriteFeedback.Added -> stringResource(R.string.editor_smiley_favorite_added)
    SmileyFavoriteFeedback.Removed -> stringResource(R.string.editor_smiley_favorite_removed)
    SmileyFavoriteFeedback.AlreadyPresent -> stringResource(R.string.editor_smiley_favorite_already_present)
    SmileyFavoriteFeedback.NotInFavorites -> stringResource(R.string.editor_smiley_favorite_not_in_favorites)
    is SmileyFavoriteFeedback.Full -> stringResource(R.string.editor_smiley_favorite_full, feedback.limit)
    SmileyFavoriteFeedback.NotFound -> stringResource(R.string.editor_smiley_favorite_not_found)
    SmileyFavoriteFeedback.ListChanged -> stringResource(R.string.editor_smiley_favorite_list_changed)
    SmileyFavoriteFeedback.NotAuthenticated -> stringResource(R.string.editor_smiley_favorite_session_expired)
    SmileyFavoriteFeedback.TransportFailure -> stringResource(R.string.editor_smiley_favorite_network_error)
    SmileyFavoriteFeedback.UnknownResponse -> stringResource(R.string.editor_smiley_favorite_unknown_response)
    SmileyFavoriteFeedback.LocalAdded, SmileyFavoriteFeedback.LocalRemoved,
    SmileyFavoriteFeedback.LocalLimitReached, SmileyFavoriteFeedback.LocalStorageFailure ->
        stringResource(localFavoriteMessageResource(feedback))
}

private fun localFavoriteMessageResource(feedback: SmileyFavoriteFeedback): Int = when (feedback) {
    SmileyFavoriteFeedback.LocalAdded -> R.string.editor_smiley_local_favorite_added
    SmileyFavoriteFeedback.LocalRemoved -> R.string.editor_smiley_local_favorite_removed
    SmileyFavoriteFeedback.LocalLimitReached -> R.string.editor_smiley_local_favorite_limit
    else -> R.string.editor_smiley_local_favorite_error
}
