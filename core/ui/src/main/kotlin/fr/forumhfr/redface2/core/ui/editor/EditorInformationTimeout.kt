package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

/**
 * Opt-in retirement of the displayed information delivery only; ordinary ED-44 events have no timer.
 * Replacement, masking by a higher-rank event or early gesture acknowledgement cancels this effect.
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
    LaunchedEffect(event?.key, recommended) {
        if (event != null && recommended != null) {
            delay(recommended)
            acknowledge.value(event.key)
        }
    }
}
