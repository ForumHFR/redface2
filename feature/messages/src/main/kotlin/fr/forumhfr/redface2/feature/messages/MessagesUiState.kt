package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary

/**
 * UI state for the private-message inbox (Messages tab). The state machine mirrors the
 * repository read without leaking domain types other than the [PrivateMessageSummary] list
 * item, so the screen stays Compose-only and the ViewModel stays unit-testable.
 */
data class MessagesUiState(
    val mode: Mode = Mode.Loading,
    val page: Int = 1,
    val totalPages: Int = 1,
    val isRefreshing: Boolean = false,
) {
    /** `true` when a previous inbox page exists (enables the "Précédent" pager control). */
    val canGoPrevious: Boolean get() = page > 1

    /** `true` when a further inbox page exists (enables the "Suivant" pager control). */
    val canGoNext: Boolean get() = page < totalPages

    sealed interface Mode {
        data object RequiresLogin : Mode
        data object Loading : Mode
        data class Content(val conversations: List<PrivateMessageSummary>) : Mode

        /**
         * A load/refresh failure. Carries NO raw throwable message on purpose (#316): a network or
         * auth error can embed the private `forum2.php?cat=prive&post=<id>` URL, which would leak the
         * conversation id on screen. The UI shows a generic message + retry; the raw message must
         * reach neither the screen nor the exportable DiagnosticsLog.
         *
         * [kind] (#324) is SAFE by construction: a closed enum derived from the exception TYPE
         * only (`classifyHfrError`), never from its message — it lets the screen tell an HFR 5xx
         * outage from a network cut without weakening the #316 guarantee.
         */
        data class Error(val kind: HfrErrorKind = HfrErrorKind.Other) : Mode
    }
}
