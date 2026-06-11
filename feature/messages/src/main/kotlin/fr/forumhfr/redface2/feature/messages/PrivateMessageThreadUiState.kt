package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread

/**
 * UI state for one private-message conversation. Mirrors [TopicUiState]'s shape: a Loading /
 * Content / Error mode plus the pager bounds.
 *
 * [isRefreshing] (#351) — true while a load runs WITH content kept on screen (pull-to-refresh, or a
 * page change from a loaded conversation). There is no MP cache (ADR-013: nothing persisted), so
 * every page change is a network round-trip; keeping the previous page visible behind the refresh
 * indicator beats wiping to a full-screen spinner. [page]/[totalPages] only advance when the new
 * page actually lands, so the pager keeps describing what is on screen during the round-trip.
 */
data class PrivateMessageThreadUiState(
    val request: PrivateMessageThreadRequest,
    val mode: Mode,
    val page: Int,
    val totalPages: Int,
    val isRefreshing: Boolean = false,
) {
    val canGoPrevious: Boolean get() = page > 1
    val canGoNext: Boolean get() = page < totalPages

    sealed interface Mode {
        data object RequiresLogin : Mode
        data object Loading : Mode
        data class Content(val thread: PrivateMessageThread) : Mode

        /**
         * A load failure. Carries NO raw throwable message on purpose (#316): a network or auth
         * error can embed the private `forum2.php?cat=prive&post=<id>` URL, which would leak the
         * conversation id on screen. The UI shows a generic message + retry.
         *
         * [kind] (#324) is SAFE by construction: a closed enum derived from the exception TYPE
         * only (`classifyHfrError`), never from its message — it lets the screen tell an HFR 5xx
         * outage from a network cut without weakening the #316 guarantee.
         */
        data class Error(val kind: HfrErrorKind = HfrErrorKind.Other) : Mode
    }

    companion object {
        fun initial(request: PrivateMessageThreadRequest): PrivateMessageThreadUiState {
            val startPage = request.page.coerceAtLeast(1)
            return PrivateMessageThreadUiState(
                request = request,
                mode = Mode.Loading,
                page = startPage,
                // Unknown until the first page resolves; seeding totalPages = page keeps
                // canGoNext false so the pager never offers a page we haven't confirmed.
                totalPages = startPage,
            )
        }
    }
}

/**
 * One-shot side effects of the conversation screen, mirroring the topic screen's `TopicEffect`
 * channel idiom (`Channel(BUFFERED)` + `receiveAsFlow`, collected once by the screen).
 */
sealed interface PrivateMessageThreadEffect {
    /**
     * #351 — a load that kept the conversation on screen (pull-to-refresh, or a page change from a
     * loaded page) failed. The displayed page stays put; the screen surfaces a Toast inviting a new
     * attempt. Initial loads (nothing on screen yet) keep going through [Mode.Error] + Retry instead.
     */
    data object RefreshFailed : PrivateMessageThreadEffect
}
