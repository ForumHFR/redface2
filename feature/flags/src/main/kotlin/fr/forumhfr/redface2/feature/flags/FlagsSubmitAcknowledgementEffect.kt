package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch

/**
 * #1301 — turns the host's acknowledgement counter into exactly ONE bounded snackbar on the flags
 * list.
 *
 * « Poster un message » on the long-press sheet (#15) opens the editor ON TOP OF this list, so the
 * pop reveals the list and not a topic entry. The #895 étape 4 post-submit outcome stays guarded on
 * that topic entry — armed from here it would sit in its slot and fire on a LATER, unrelated open
 * of the topic — so the reader used to come back with no sign at all that the message went through.
 * This is that sign: an acknowledgement, with no refresh and no navigation behind it.
 *
 * Same one-shot handshake as `QuickConfigRequestEffect` (#603): the counter is consumed on the
 * first composition that sees it, which resets it upstream, so a re-mount of the list (coming back
 * from a topic later in the session) can never replay an acknowledgement already given.
 *
 * @param request the host's monotonic counter ; `0` means nothing is owed.
 */
@Composable
internal fun FlagsSubmitAcknowledgementEffect(
    request: Int,
    snackbarHostState: SnackbarHostState,
    message: String,
    onConsumed: () -> Unit,
) {
    val screenScope = rememberCoroutineScope()
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(request) {
        if (request > 0) {
            onConsumed()
            // Shown from the SCREEN's scope, not this effect's: [onConsumed] resets the counter,
            // which restarts this very effect and would cancel — hence dismiss — a snackbar shown
            // from here the instant it appeared.
            screenScope.launch { snackbarHostState.showSubmitAcknowledgement(currentMessage) }
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
