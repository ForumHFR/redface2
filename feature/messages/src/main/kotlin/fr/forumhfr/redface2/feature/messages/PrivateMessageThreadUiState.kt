package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageThreadPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread

/**
 * UI state for one private-message conversation. Mirrors [TopicUiState]'s shape: a Loading /
 * Content / Error mode plus the pager bounds.
 *
 * [isRefreshing] (#351) — true while a load runs WITH content kept on screen (pull-to-refresh, or a
 * page change from a loaded conversation). A session-cache hit replaces the page immediately while
 * [isRefreshing] stays true until mandatory network revalidation. [page]/[totalPages] only advance
 * when cache or network content lands, so the pager always describes what is on screen.
 */
data class PrivateMessageThreadUiState(
    val request: PrivateMessageThreadRequest,
    val mode: Mode,
    val page: Int,
    val totalPages: Int,
    val isRefreshing: Boolean = false,
    /** #1050 — global reading preference, render-only: no page reload when it changes. */
    val fullWidthPosts: Boolean = false,
    /** #1050 — shared topic/MP signature preference; inert when HFR supplied no signature. */
    val showSignatures: Boolean = false,
    /** #1050 — shared #874 EgoQuote preference, independent from [egoPostEnabled]; render-only. */
    val egoQuoteEnabled: Boolean = true,
    /** #1050 — shared #874 EgoPost preference, independent from [egoQuoteEnabled]; render-only. */
    val egoPostEnabled: Boolean = true,
    /**
     * #383/#1040 — historical topic-page-FAB preference, now shared by both reading surfaces.
     * Keeping the state name generic avoids exposing the persisted key's legacy name to the UI.
     */
    val showPageFabs: Boolean = true,
    /**
     * #1050 — pseudo of the authenticated session, the session-bound input of the Ego markers
     * (the list derives both from it; `Post.isOwnPost` is deliberately not trusted, see
     * `core.domain.ego.isEgoPost`). This is NOT private conversation metadata (the #316/#298
     * exclusions cover subject/correspondent — data about the OTHER party): it is the reader's own
     * identity, which the account menu already displays. It stays session-bound nonetheless:
     * `null` while anonymous, and clearPrivateState drops it at logout instead of carrying it
     * over like the render-only preferences above.
     */
    val connectedPseudo: String? = null,
    /**
     * #1040 lot 6 — one landing published in the SAME state update as its first rendered page
     * emission (session cache when present, network otherwise). Keeping it atomic with [mode]
     * closes the cache→network double-scroll race: a later emission retains the same value until
     * the screen applies and compare-and-clears it through the ViewModel.
     */
    val pageLanding: PrivateMessagePageLanding? = null,
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

        /**
         * The full member list, parsed from the form's recipients roster (#618 — `newdest` for the
         * owner, the read-only « Destinataires » span for a participant ; HFR's `, ` separator). The
         * viewer is prepended so the sheet shows the whole group.
         *
         * [canManageRecipients] (#618) — true only for the owner (HFR served the editable `newdest`).
         * Gates the « Gérer les destinataires » entry in the sheet: a participant reads the roster but
         * cannot edit it (HFR mutates members only via an owner reply).
         */
        data class Loaded(
            val members: List<String>,
            val canManageRecipients: Boolean = false,
        ) : Roster

        /**
         * The reply form loaded but exposed no roster at all (#618 — neither an editable `newdest`
         * nor a read-only « Destinataires » row): a one-to-one MP. The sheet surfaces a sober « non
         * disponible » note rather than a partial author list.
         */
        data object Unavailable : Roster

        /** The reply form GET failed; the sheet stays open with a retry affordance. */
        data class Error(val kind: HfrErrorKind = HfrErrorKind.Other) : Roster
    }

    sealed interface Mode {
        data object RequiresLogin : Mode
        data object Loading : Mode
        data class Content(
            val thread: PrivateMessageThread,
            /** Cache content may own the first visual landing; domain writes remain network-only. */
            val source: PrivateMessageThreadPage.Source,
            /**
             * #509/#1050 — `numreponse` of this page's messages whose canonical author is blocked.
             * The full [PrivateMessageThread.messages] list stays intact; the screen replaces only
             * these cards with a collapsed placeholder, preserving pagination, keys and anchors.
             */
            val hiddenNumreponses: Set<Int> = emptySet(),
            /**
             * The canonical blacklist snapshot used to compute [hiddenNumreponses]. Supplied to
             * `LocalBlockedQuoteAuthors` by the thread screen so quoted content cannot bypass the
             * message-level mask. Both sets are built together by the ViewModel.
             */
            val blockedQuoteAuthors: Set<String> = emptySet(),
        ) : Mode

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

    /** #831/#1051 — the image was written to the shared Pictures collection. */
    data object ImageSaved : PrivateMessageThreadEffect

    /** The image could not be fetched from its remote URL. */
    data object ImageSaveFailedFetch : PrivateMessageThreadEffect

    /** The fetched image could not be written to shared storage. */
    data object ImageSaveFailedStorage : PrivateMessageThreadEffect

    /** The image exceeded the saver's bounded download size. */
    data object ImageSaveFailedTooLarge : PrivateMessageThreadEffect
}
