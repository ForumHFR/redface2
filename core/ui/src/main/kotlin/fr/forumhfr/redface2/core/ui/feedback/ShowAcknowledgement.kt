package fr.forumhfr.redface2.core.ui.feedback

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Claims an event only after its own visuals acquired the Material host, including quick actions. */
suspend fun SnackbarHostState.showAcknowledgement(
    message: String,
    actionLabel: String? = null,
    onPresented: () -> Unit = {},
): SnackbarResult = coroutineScope {
    val visuals = AcknowledgementVisuals(message, actionLabel)
    val result = async(start = CoroutineStart.UNDISPATCHED) { showSnackbar(visuals) }
    var presented = false
    val observation = launch(start = CoroutineStart.UNDISPATCHED) {
        snapshotFlow { currentSnackbarData?.visuals }.first { it === visuals }
        presented = true
        onPresented()
    }
    try {
        val outcome = result.await()
        if (!presented) onPresented()
        outcome
    } finally {
        observation.cancel()
    }
}

private class AcknowledgementVisuals(
    override val message: String,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction = actionLabel != null
    override val duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long
}
