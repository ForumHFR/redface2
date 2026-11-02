package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import fr.forumhfr.redface2.core.ui.feedback.showAcknowledgement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Consumption is independent of display: clearing the request must not cancel its own snackbar. */
@Composable
internal fun FlagsSubmitAcknowledgementEffect(
    request: Long,
    host: SnackbarHostState,
    message: String,
    onConsumed: (Long) -> Unit,
    sessionKey: String? = null,
) {
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    val currentMessage by rememberUpdatedState(message)
    val currentRequest by rememberUpdatedState(request)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(host, sessionKey, foreground) {
        if (!foreground) return@LaunchedEffect
        coroutineScope {
            val feedback = FlagsAcknowledgementPresenter(this, host)
            snapshotFlow { currentRequest to currentMessage }.collect { (id, text) ->
                if (id > 0) feedback.present(id, text) { currentOnConsumed(id) }
            }
        }
    }
}

internal suspend fun SnackbarHostState.showSubmitAcknowledgement(message: String) {
    currentSnackbarData?.dismiss()
    showAcknowledgement(message)
}

private class FlagsAcknowledgementPresenter(
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
) {
    private var lastId = 0L
    private var showing: Job? = null
    private var pending: (suspend () -> Unit)? = null

    fun present(id: Long, message: String, onPresented: () -> Unit) {
        if (id == lastId) return
        lastId = id
        pending = {
            host.currentSnackbarData?.dismiss()
            host.showAcknowledgement(message, onPresented = onPresented)
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
