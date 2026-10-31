package fr.forumhfr.redface2.feature.flags

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Session/view changes cancel both the display and its locally retained Retry action. */
@Composable
@Suppress("LongParameterList") // Effect API: warning + session/view scope + snackbar host + two callbacks.
internal fun FlagsRefreshWarningEffect(
    warning: FlagsRefreshWarning?,
    authSessionKey: String?,
    selectedTab: FlagTab,
    host: SnackbarHostState,
    onConsumed: (FlagsRefreshWarning) -> Unit,
    onRetry: (FlagsRefreshWarning) -> Unit,
) {
    val resources = LocalContext.current.resources
    val currentWarning by rememberUpdatedState(warning)
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    val currentOnRetry by rememberUpdatedState(onRetry)
    LaunchedEffect(warning?.presentation, authSessionKey, selectedTab, host) {
        if (warning != null && warning.pseudo == authSessionKey && warning.tab == selectedTab) {
            // A previous composition already presented this event. Its suspended display was
            // cancelled, so finish the handoff without showing it again after recreation.
            if (warning.presentation.wasPresented) {
                currentOnConsumed(warning)
                return@LaunchedEffect
            }
            val result = host.showRefreshWarning(
                // Causes may change while this SAME presentation is suspended. Reading the latest
                // snapshot also updates the visible text, without cancelling its Retry action.
                message = {
                    val latest = currentWarning?.takeIf { it.presentation === warning.presentation } ?: warning
                    refreshWarningMessage(resources, latest.issue)
                },
                retryLabel = resources.getString(R.string.flags_retry),
                onPresented = { warning.presentation.wasPresented = true },
            )
            val latest = currentWarning?.takeIf { it.presentation === warning.presentation } ?: return@LaunchedEffect
            currentOnConsumed(latest)
            if (result == SnackbarResult.ActionPerformed) currentOnRetry(latest)
        }
    }
}

internal suspend fun SnackbarHostState.showRefreshWarning(
    message: String,
    retryLabel: String,
    onPresented: () -> Unit = {},
): SnackbarResult = showRefreshWarning({ message }, retryLabel, onPresented)

internal suspend fun SnackbarHostState.showRefreshWarning(
    message: () -> String,
    retryLabel: String,
    onPresented: () -> Unit = {},
): SnackbarResult = coroutineScope {
    val visuals = RefreshWarningVisuals(message, retryLabel)
    val result = async(start = CoroutineStart.UNDISPATCHED) { showSnackbar(visuals) }
    // showSnackbar queues behind the host mutex. Only claim presentation once THIS warning is
    // visible: a queued event must remain withdrawable by a complete snapshot, and retryable after
    // recreation if it never got its turn. Identity avoids matching an identical older message.
    var presented = false
    val presentation = launch(start = CoroutineStart.UNDISPATCHED) {
        snapshotFlow { currentSnackbarData?.visuals }.first { it === visuals }
        presented = true
        onPresented()
    }
    try {
        val outcome = result.await()
        // A quick action/dismissal can finish before snapshotFlow observes the queued snackbar.
        // Completion proves presentation too; the observer must never hold the action suspended.
        if (!presented) onPresented()
        outcome
    } finally {
        presentation.cancel()
    }
}

private class RefreshWarningVisuals(
    private val currentMessage: () -> String,
    override val actionLabel: String,
) : SnackbarVisuals {
    override val message: String get() = currentMessage()
    override val withDismissAction = true
    override val duration = SnackbarDuration.Long
}

internal fun FlagRefreshIssue.stickyCategoryLabels(fallback: (Int) -> String): String {
    val labels = stickyCategories.take(MAX_CATEGORY_NAMES).joinToString(", ") { category ->
        category.name?.takeIf { it.isNotBlank() } ?: fallback(category.id)
    }
    val remaining = stickyCategories.size - MAX_CATEGORY_NAMES
    return if (remaining > 0) "$labels +$remaining" else labels
}

private fun refreshWarningMessage(resources: Resources, issue: FlagRefreshIssue): String {
    val details = mutableListOf<String>()
    if (issue.stickyCategories.isNotEmpty()) {
        val categories = issue.stickyCategoryLabels { resources.getString(R.string.flags_category_fallback, it) }
        details += resources.getString(R.string.flags_incomplete_stickies, categories)
    }
    if (issue.paginationTruncated) details += resources.getString(R.string.flags_incomplete_pages)
    return resources.getString(R.string.flags_incomplete, details.joinToString(" ; "))
}

private const val MAX_CATEGORY_NAMES = 2
