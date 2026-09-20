package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * #1301 — the single owner of the post-submit snackbar on the topic screen.
 *
 * The confirmation (« Message publié », armed by [TopicRefreshKind.PostSubmit]) and the
 * « published on page N » offer ([TopicEffect.PostSubmittedElsewhere]) used to reach the host's
 * `SnackbarHostState` from two independent coroutines. Material serialises `showSnackbar` behind a
 * single mutex, so the offer QUEUED behind the Indefinite confirmation and the reader got two
 * confirmations one after the other (beta feedback from nicko on 0.61.0). Routing both through this
 * coordinator makes the offer REPLACE the confirmation instead.
 *
 * The ViewModel separates a submit refresh ([TopicRefreshKind.PostSubmit]) from the explicit jump
 * started by the offer ([TopicRefreshKind.PostSubmitJump]). The coordinator therefore owns only
 * snackbar replacement; it has no transient flag that a recreation or a conflated StateFlow
 * transition could lose.
 *
 * Plain class remembered by the screen : a one-shot snackbar is an Effect and must never be replayed
 * at recomposition (`docs/specs/mvi.md`), so none of this belongs in [TopicUiState].
 *
 * @property dismissCurrentSnackbar removes whatever the host currently shows, so post-submit
 *   feedback preempts stale transient feedback instead of queueing behind it.
 */
internal class TopicSubmitFeedback(private val dismissCurrentSnackbar: () -> Unit) {

    /** The snackbar coroutine this coordinator owns, cancelled before any replacement. */
    private var showing: Job? = null

    /** True while [showing] is the « page N » offer, which the end of a refresh must not remove. */
    private var showingOffer = false

    /** #1301 — a post-submit refresh just started: confirm that HFR accepted the message. */
    fun confirmSubmit(scope: CoroutineScope, show: suspend () -> Unit) {
        replace(scope, offer = false) { show() }
    }

    /**
     * #1301 — the refreshed page reports the message landed further on : REPLACE the confirmation
     * rather than queue a second one behind it. [openPage] only carries the page — the producer
     * knows no numreponse, so nothing is promised about reaching the exact message.
     */
    fun offerSubmittedElsewhere(
        scope: CoroutineScope,
        show: suspend () -> SnackbarResult,
        openPage: () -> Unit,
    ) {
        replace(scope, offer = true) {
            if (show() == SnackbarResult.ActionPerformed) {
                openPage()
            }
        }
    }

    /**
     * #1301 — the post-submit work reached its end (terminal page, or the failure path that
     * replaces it with an error). Removes the confirmation only : an offer that already replaced it
     * survives, whatever the order in which the state change and the effect reach the screen.
     */
    fun dismissConfirmation() {
        if (showingOffer) return
        val confirmation = showing ?: return
        confirmation.cancel()
        showing = null
        // Cancelling only frees the host on the next dispatch; the error path that follows needs the
        // confirmation gone NOW. Guarded on owning one, so a snackbar from another producer stays.
        dismissCurrentSnackbar()
    }

    private fun replace(scope: CoroutineScope, offer: Boolean, block: suspend () -> Unit) {
        showing?.cancel()
        dismissCurrentSnackbar()
        showingOffer = offer
        showing = scope.launch { block() }
    }
}

/**
 * #1301 — bridges the ViewModel's durable refresh cause to the one-shot snackbar coordinator.
 * Only a real submit owns a confirmation; a jump still owns progress but dismisses no offer because
 * [TopicSubmitFeedback.dismissConfirmation] preserves the offer currently on screen.
 */
@Composable
internal fun TopicSubmitFeedbackEffect(
    refreshKind: TopicRefreshKind,
    submitFeedback: TopicSubmitFeedback,
    scope: CoroutineScope,
    showConfirmation: suspend () -> Unit,
) {
    val currentShowConfirmation by rememberUpdatedState(showConfirmation)
    LaunchedEffect(refreshKind, submitFeedback) {
        if (refreshKind == TopicRefreshKind.PostSubmit) {
            submitFeedback.confirmSubmit(scope) { currentShowConfirmation() }
        } else {
            submitFeedback.dismissConfirmation()
        }
    }
}
