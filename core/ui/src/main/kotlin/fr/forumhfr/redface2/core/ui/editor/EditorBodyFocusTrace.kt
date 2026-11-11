package fr.forumhfr.redface2.core.ui.editor

import android.os.SystemClock
import android.util.Log

/** Debug-only H diagnostics. Elapsed time is attachment-relative; no text or selection is logged. */
internal class EditorBodyFocusTrace(
    private val enabled: Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val attachedAt = if (enabled) now() else 0L
    private var previous: EditorBodyFocusAvailability? = null
    private var firstReady = false

    fun availability(value: EditorBodyFocusAvailability) {
        if (!enabled || previous == value) return
        val delay = if (value == EditorBodyFocusAvailability.Ready && !firstReady) {
            firstReady = true
            " readyAfterMs=${now() - attachedAt}"
        } else ""
        log("availability=$previous->$value$delay")
        previous = value
    }

    fun outcome(reason: String) { if (enabled) log("outcome=$reason") }

    private fun log(message: String) {
        Log.d("RF2-H", "attachment=${System.identityHashCode(this)} " +
            "elapsedMs=${now() - attachedAt} $message")
    }
}
