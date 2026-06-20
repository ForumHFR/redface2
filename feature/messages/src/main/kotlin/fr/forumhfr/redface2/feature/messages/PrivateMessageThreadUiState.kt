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
    /**
     * #612 — participant roster sheet state. Lazily loaded (only when the user opens the sheet, never
     * on screen entry) and cached for the life of the screen. See [Roster].
     */
    val roster: Roster = Roster.Hidden,
) {
    val canGoPrevious: Boolean get() = page > 1
    val canGoNext: Boolean get() = page < totalPages

    /**
     * #612 — the « Participants » bottom sheet. Its own little state machine rather than a fragile
     * boolean (Codex framing): the sheet is closed ([Hidden]) until the user taps the action, then
     * [Loading] while the reply form is fetched, then [Loaded] (the full member list parsed from the
     * owner-only `newdest`) or [Error] (kept open with a retry) or [Unavailable] (HFR served no
     * `newdest` — the logged-in user is not the owner, so there is no authoritative roster).
     *
     * Source = `newdest` from the message.php reply form (#612 fix), the ONLY place HFR exposes the
     * complete member list. The forum2.php page only shows authors who have posted, so a fallback on
     * visible authors would be a misleading partial list — per Codex framing we deliberately do NOT
     * offer it under the « Participants » label; a non-owner simply gets no roster button.
     */
    sealed interface Roster {
        /** The sheet is closed. The action button is shown only while this allows it (see VM). */
        data object Hidden : Roster

        /** The sheet is open and the reply form GET is in flight. */
        data object Loading : Roster

        /** The owner's full member list, parsed from `newdest` (HFR's `, ` separator). */
        data class Loaded(val members: List<String>) : Roster

        /**
         * The reply form loaded but carried no `newdest` — the user is a participant, not the owner.
         * HFR has no authoritative roster to show; the sheet surfaces a sober « non disponible »
         * note rather than a partial author list.
         */
        data object Unavailable : Roster

        /** The reply form GET failed; the sheet stays open with a retry affordance. */
        data class Error(val kind: HfrErrorKind = HfrErrorKind.Other) : Roster
    }

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
