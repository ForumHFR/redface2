package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * #1301 — turns the host's pending acknowledgement id into one bounded snackbar on the flags
 * list.
 *
 * « Poster un message » on the long-press sheet (#15) opens the editor ON TOP OF this list, so the
 * pop reveals the list and not a topic entry. The #895 étape 4 post-submit outcome stays guarded on
 * that topic entry — armed from here it would sit in its slot and fire on a LATER, unrelated open
 * of the topic — so the reader used to come back with no sign at all that the message went through.
 * This is that sign: an acknowledgement, with no refresh and no navigation behind it.
 *
 * Unlike a disposable tap request, the id stays pending while [showAcknowledgement] is suspended.
 * A recreation cancels that attempt without consuming the id, and the restored composition retries
 * it. Completion clears the exact id handled, so an older snackbar cannot erase a newer handoff.
 *
 * @param request the host's pending acknowledgement id; `0` means nothing is owed.
 */
@Composable
internal fun FlagsSubmitAcknowledgementEffect(
    request: Long,
    onConsumed: (Long) -> Unit,
    showAcknowledgement: suspend () -> Unit,
) {
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    val currentShowAcknowledgement by rememberUpdatedState(showAcknowledgement)
    LaunchedEffect(request) {
        if (request > 0L) {
            currentShowAcknowledgement()
            currentOnConsumed(request)
        }
    }
}

/**
 * #1301 — the acknowledgement itself. [SnackbarDuration.Short] is deliberate: nothing on this
 * screen refreshes after the submit, so an Indefinite acknowledgement would have no terminal
 * condition. It preempts whatever the host is showing (the #99 flag-removal feedback shares it)
 * because it answers the reader's LAST action and must not wait in line behind an older one —
 * Material serialises `showSnackbar` calls behind a single mutex.
 */
internal suspend fun SnackbarHostState.showSubmitAcknowledgement(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message = message, duration = SnackbarDuration.Short)
}
