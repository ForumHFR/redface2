package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** The cell keeps its insertion semantics; only eligible, idle cells expose a favorite gesture. */
@Composable
internal fun SmileyFavoriteTarget(
    smiley: EditorSmiley,
    state: SmileyPickerState.Open?,
    onClick: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val action = state?.favoriteAction(smiley)
    val pending = state?.pendingFavoriteToken == smiley.token
    val progressLabel = stringResource(R.string.editor_smiley_favorite_pending)
    Box {
        if (action == null) {
            content(Modifier.clickable(onClick = onClick).semantics { if (pending) stateDescription = progressLabel })
        } else {
            SmileyFavoriteGesture(
                smiley = smiley,
                action = action,
                onClick = onClick,
                onFavoriteChange = { state?.onFavoriteChange?.invoke(smiley) },
                content = content,
            )
        }
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun SmileyFavoriteGesture(
    smiley: EditorSmiley,
    action: SmileyFavoriteAction,
    onClick: () -> Unit,
    onFavoriteChange: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val label = when (action) {
        SmileyFavoriteAction.ADD -> stringResource(R.string.editor_smiley_favorite_add)
        SmileyFavoriteAction.REMOVE -> stringResource(R.string.editor_smiley_favorite_remove)
    }
    var menuOpen by remember(smiley.token) { mutableStateOf(false) }
    // Ineligible cells leave this composition entirely; a changed action also closes the menu.
    LaunchedEffect(action) { menuOpen = false }
    content(
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { menuOpen = true },
            onLongClickLabel = label,
        ).semantics {
            customActions = listOf(CustomAccessibilityAction(label) { onFavoriteChange(); true })
        },
    )
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(label) },
            onClick = { menuOpen = false; onFavoriteChange() },
        )
    }
}

/**
 * A brief Wiki overlay, excluded from content measurement by the caller and keyed per favorite gesture.
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

/** Both tabs expose the same polite announcement for the current opening. */
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
}
