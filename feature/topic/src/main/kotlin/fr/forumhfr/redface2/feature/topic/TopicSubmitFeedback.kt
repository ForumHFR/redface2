package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarResult
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
 * It also separates « a post-submit refresh is running » — [TopicRefreshKind.PostSubmit], the
 * durable state of the network work — from « an acknowledgement is still owed » : taking the offer
 * calls `TopicViewModel.openSubmittedPostPage`, which starts another post-submit refresh. That
 * refresh is a jump, not a new submit, and must not reopen the generic confirmation.
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

    /** Armed by the offer's action : the refresh it starts is a jump, not a new submit. */
    private var skipNextConfirmation = false

    /**
     * #1301 — a post-submit refresh just started : confirm that HFR accepted the message, unless
     * this refresh is the one the reader asked for by taking the « page N » offer.
     */
    fun confirmSubmit(scope: CoroutineScope, show: suspend () -> Unit) {
        if (skipNextConfirmation) {
            skipNextConfirmation = false
            return
        }
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
                skipNextConfirmation = true
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
