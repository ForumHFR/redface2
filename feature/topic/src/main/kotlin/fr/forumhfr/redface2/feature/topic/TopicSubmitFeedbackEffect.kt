package fr.forumhfr.redface2.feature.topic

import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import fr.forumhfr.redface2.core.ui.feedback.showAcknowledgement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The coroutine owns display after consumption, rather than being keyed by progress or event id. */
@Composable
internal fun TopicSubmitFeedbackEffect(
    acknowledgements: TopicAcknowledgements,
    host: SnackbarHostState,
    active: Boolean,
    account: String?,
    openPage: (Int) -> Unit,
) {
    val resources by rememberUpdatedState(LocalResources.current)
    val accessibility = LocalAccessibilityManager.current
    val currentOpenPage by rememberUpdatedState(openPage)
    // Capture this generation by value, including while the previous effect is being cancelled.
    val invalidation = acknowledgements.invalidations.collectAsState().value
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(acknowledgements, host, active, account, invalidation, foreground) {
        if (!active || !foreground || account == null) return@LaunchedEffect
        coroutineScope {
            val feedback = TopicSubmitFeedback()
            var lastContent: Pair<Long, Int>? = null
            acknowledgements.feedback.collect { ack ->
                // A new submit invalidates the old host before publishing its next feedback.
                // Let the replacement effect consume it, even if recomposition has not run yet.
                if (acknowledgements.invalidations.value != invalidation) return@collect
                val content = ack?.let { it.id to it.revision }
                if (ack != null && content != lastContent && ack.account.equals(account, ignoreCase = true)) {
                    // A failure can replace this id while showSnackbar is waiting for the host.
                    // Expiry-only changes after presentation must not enqueue another success.
                    lastContent = content
                    val isError = ack.refreshFailed || ack.kind == TopicAcknowledgement.Kind.RefreshWarning
                    feedback.present(this, error = isError, replace = ack.lastPage != null && ack.revision > 0) {
                        val latest = acknowledgements.forPresentation(ack.id, ack.revision) ?: return@present
                        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                            if (latest.lastPage == null) 4_000L else 10_000L,
                            containsIcons = true,
                            containsText = true,
                            containsControls = latest.lastPage != null,
                        ) ?: if (latest.lastPage == null) 4_000L else 10_000L
                        host.presentTopicAcknowledgement(acknowledgements, latest, resources, timeout) {
                            currentOpenPage(it)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun SnackbarHostState.presentTopicAcknowledgement(
    store: TopicAcknowledgements,
    ack: TopicAcknowledgement,
    resources: Resources,
    timeoutMillis: Long,
    openPage: (Int) -> Unit,
) = coroutineScope {
    var expiry: kotlinx.coroutines.Job? = null
    var presented = false
    try {
        val result = showAcknowledgement(
            message = acknowledgementMessage(resources, ack),
            actionLabel = ack.lastPage?.let { resources.getString(R.string.topic_post_submitted_elsewhere_action) },
            onPresented = {
                presented = true
                store.presented(ack.id, timeoutMillis, ack.revision)
                // Material still owns the normal accessible timeout. A restored offer also has
                // this absolute cap, so rotation cannot grant another full Long duration.
                store.remainingMillis(ack.id, ack.revision)?.let { remaining ->
                    val data = currentSnackbarData
                    expiry = launch {
                        delay(remaining)
                        data?.dismiss()
                    }
                }
            },
        )
        if (result == SnackbarResult.ActionPerformed) {
            store.takeOffer(ack.id, ack.account, ack.revision)?.let(openPage)
        } else {
            store.dismiss(ack.id, ack.revision)
        }
    } finally {
        expiry?.cancel()
        // A disposed Short host is no longer visible. Its old revision cannot touch a late offer.
        if (presented && ack.lastPage == null) store.dismiss(ack.id, ack.revision)
    }
}

private fun acknowledgementMessage(resources: Resources, ack: TopicAcknowledgement): String {
    if (ack.offerOnly) return resources.getString(R.string.topic_fab_last_page)
    val success = resources.getString(
        when (ack.kind) {
            TopicAcknowledgement.Kind.Published -> if (ack.lastPage == null) {
                R.string.topic_post_submit_confirmed
            } else {
                R.string.topic_post_submitted_elsewhere
            }
            TopicAcknowledgement.Kind.Edited -> R.string.topic_post_edit_confirmed
            TopicAcknowledgement.Kind.Deleted -> R.string.topic_post_delete_success
            TopicAcknowledgement.Kind.RefreshWarning -> R.string.topic_refresh_failed
        },
    )
    return if (ack.refreshFailed) resources.getString(R.string.topic_post_submit_refresh_failed, success) else success
}
