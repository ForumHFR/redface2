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

        /**
         * A load failure. Carries NO raw throwable message on purpose (#316): a network or auth
         * error can embed the private `forum2.php?cat=prive&post=<id>` URL, which would leak the
         * conversation id on screen. The UI shows a generic message + retry.
         */
        data object Error : Mode
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
