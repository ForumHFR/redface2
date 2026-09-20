package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * #1301 — turns the host's acknowledgement counter into exactly ONE bounded snackbar on the flags
 * list.
 *
 * « Poster un message » on the long-press sheet (#15) opens the editor ON TOP OF this list, so the
 * pop reveals the list and not a topic entry. The #895 étape 4 post-submit outcome stays guarded on
 * that topic entry — armed from here it would sit in its slot and fire on a LATER, unrelated open
 * of the topic — so the reader used to come back with no sign at all that the message went through.
 * This is that sign : an acknowledgement, with no refresh and no navigation behind it.
 *
 * [SnackbarDuration.Short] is deliberate : nothing on this screen refreshes after the submit, so an
 * Indefinite acknowledgement would have no terminal condition. It preempts whatever the host is
 * showing (the #99 flag-removal feedback shares it) because it answers the reader's LAST action and
 * must not wait in line behind an older one.
 *
 * The counter is mirrored locally BEFORE [onConsumed] resets it upstream : showing a snackbar
 * suspends for as long as it stays on screen, and an effect keyed on the host value would be
 * cancelled — dismissing the snackbar — the moment the slot was cleared. Resetting it right away is
 * what keeps a later re-mount of the list (coming back from a topic) from replaying it.
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
    var armed by remember { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        if (request > 0) {
            armed = request
            onConsumed()
        }
    }
    LaunchedEffect(armed) {
        if (armed > 0) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            armed = 0
        }
    }
}
