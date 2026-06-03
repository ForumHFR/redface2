package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread

/**
 * UI state for one private-message conversation. Mirrors [TopicUiState]'s shape: a Loading /
 * Content / Error mode plus the pager bounds.
 */
data class PrivateMessageThreadUiState(
    val request: PrivateMessageThreadRequest,
    val mode: Mode,
    val page: Int,
    val totalPages: Int,
) {
    val canGoPrevious: Boolean get() = page > 1
    val canGoNext: Boolean get() = page < totalPages

    sealed interface Mode {
        data object RequiresLogin : Mode
        data object Loading : Mode
        data class Content(val thread: PrivateMessageThread) : Mode
        data class Error(val message: String?) : Mode
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
