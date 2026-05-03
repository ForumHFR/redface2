package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Topic

data class TopicUiState(
    val request: TopicRequest,
    val mode: Mode,
    val availablePages: List<Int>,
) {
    /**
     * Helper used by the screen / ViewModel : `true` when the user has navigated to a
     * page > 1, i.e. when "Previous" should be enabled. Mirrors the symmetric helper
     * `canGoNext()` below.
     */
    val canGoPrevious: Boolean get() = request.page > 1

    val canGoNext: Boolean
        get() = when (mode) {
            is Mode.Loaded -> request.page < mode.topic.totalPages
            else -> request.page < (availablePages.lastOrNull() ?: 1)
        }

    sealed interface Mode {
        data object Loading : Mode

        data class Loaded(
            val topic: Topic,
        ) : Mode

        data class Error(
            val message: String,
        ) : Mode
    }

    companion object {
        fun initial(request: TopicRequest): TopicUiState =
            TopicUiState(
                request = request,
                mode = Mode.Loading,
                availablePages = emptyList(),
            )
    }
}

sealed interface TopicIntent {
    data object Retry : TopicIntent
}

/**
 * One-shot side effects produced by [TopicViewModel]. The screen consumes these via
 * `LaunchedEffect(Unit)` collecting from the effects channel — exactly once per
 * effect, never replayed across recompositions or process death (intentional : if
 * the user scrolled away, replaying a scroll on rotation would steal focus).
 */
sealed interface TopicEffect {
    /**
     * Ask the screen to scroll to a specific `numreponse` once the topic page is
     * rendered. The ViewModel only emits this when the post is present in the loaded
     * page, so the screen can blindly trust `numreponse` and resolve the index from
     * the current `Topic.posts` list.
     */
    data class ScrollToPost(val numreponse: Int) : TopicEffect
}
