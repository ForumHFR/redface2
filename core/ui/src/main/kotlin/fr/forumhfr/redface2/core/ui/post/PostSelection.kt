package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Observes selection-release gestures on a reading list without consuming descendants' events.
 * A single-pointer press that reaches the long-press timeout calls [onLongPressObserved], allowing
 * the host to mark text selection as plausible. A later plain tap calls [onTap]; the host decides
 * whether that armed state warrants recreating its visible selection owners (#1391).
 *
 * A tap is strictly an up before the timeout, without touch slop or a second pressed pointer. The
 * timeout is evaluated on the up event too, so an up exactly on the boundary is never a tap.
 */
fun Modifier.releasePostSelectionOnTap(
    onLongPressObserved: () -> Unit,
    onTap: () -> Unit,
): Modifier = pointerInput(onLongPressObserved, onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var isTap = true
        var isTracking = true
        var longPressReported = false
        while (isTracking) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                isTap = false
                isTracking = false
            } else {
                if (event.changes.any { it.id != down.id && it.pressed }) isTap = false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) isTap = false
                val timedOut = change.uptimeMillis - down.uptimeMillis >=
                    viewConfiguration.longPressTimeoutMillis
                if (isTap && timedOut && !longPressReported) {
                    longPressReported = true
                    onLongPressObserved()
                }
                if (timedOut) isTap = false
                if (change.changedToUpIgnoreConsumed()) {
                    if (isTap) onTap()
                    isTracking = false
                }
            }
        }
    }
}
