package fr.forumhfr.redface2.feature.topic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Latest success waits for the current Material timeout; warnings may interrupt it. */
internal class TopicSubmitFeedback {
    private var showing: Job? = null
    private var pending: (suspend () -> Unit)? = null

    fun present(scope: CoroutineScope, error: Boolean = false, show: suspend () -> Unit) {
        pending = show
        if (error) {
            showing?.cancel()
            showing = null
        }
        if (showing?.isActive == true) return
        showing = scope.launch {
            while (true) {
                val next = pending ?: break
                pending = null
                next()
            }
        }
    }
}
