package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Observes an unconsumed plain tap anywhere in the topic list so its post selection owners can be
 * reset (#1391). The Initial pass sees taps handled by descendants (links, images and actions), but
 * this observer never consumes a change, so their existing click/long-click behavior is untouched.
 */
internal fun Modifier.releasePostSelectionOnTap(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            var isTap = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    isTap = false
                    break
                }
                if (event.changes.any { it.id != down.id && it.pressed }) isTap = false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) isTap = false
                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                    isTap = false
                }
                if (change.changedToUpIgnoreConsumed()) {
                    if (isTap) onTap()
                    break
                }
            }
        }
    }
