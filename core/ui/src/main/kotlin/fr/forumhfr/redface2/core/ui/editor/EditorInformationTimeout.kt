package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Opt-in retirement of the displayed information delivery only; ordinary ED-44 events have no timer.
 * Replacement, masking by a higher-rank event or early gesture acknowledgement cancels this effect.
 * Only RESUMED time counts; resuming or recreating the composition starts a full delay again.
 * The callback acknowledges the exact delivery, without a business intent or focus/IME change.
 */
@Composable
internal fun EditorInformationTimeout(event: EditorEvent?, onTimeout: (EventKey) -> Unit) {
    val base = (event?.content as? EditorEventContent.Information)?.timeoutMillis
    val manager = LocalAccessibilityManager.current
    val recommended = base?.let {
        manager?.calculateRecommendedTimeoutMillis(it, containsText = true, containsControls = true)
            ?.coerceAtLeast(it) ?: it
    }
    val acknowledge = rememberUpdatedState(onTimeout)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(event?.key, recommended, lifecycle) {
        if (event != null && recommended != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(recommended)
                acknowledge.value(event.key)
            }
        }
    }
}
