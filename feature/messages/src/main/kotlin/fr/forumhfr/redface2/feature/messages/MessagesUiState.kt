package fr.forumhfr.redface2.feature.messages

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
        data class Error(val message: String?) : Mode
    }
}
