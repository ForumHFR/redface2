package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.core.model.Topic

data class TopicUiState(
    val request: TopicRequest,
    val mode: Mode,
    val availablePages: List<Int>,
) {
    sealed interface Mode {
        data object Loading : Mode

        data class Loaded(
            val topic: Topic,
        ) : Mode

        data class Error(
            val message: String,
        ) : Mode

        data object Placeholder : Mode
    }

    companion object {
        fun initial(
            request: TopicRequest,
            availablePages: List<Int> = FixedTopicFixtures.availablePages,
        ): TopicUiState =
            TopicUiState(
                request = request,
                mode = Mode.Loading,
                availablePages = availablePages,
            )
    }
}

sealed interface TopicIntent {
    data object Retry : TopicIntent
}
