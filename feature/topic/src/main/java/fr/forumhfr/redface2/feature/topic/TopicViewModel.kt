package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.fixtures.FixedTopicFixtures
import fr.forumhfr.redface2.core.domain.fixtures.TopicFixtureRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = TopicViewModel.Factory::class)
class TopicViewModel @AssistedInject constructor(
    @Assisted private val request: TopicRequest,
    private val topicFixtureRepository: TopicFixtureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TopicUiState.initial(request))
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    init {
        loadCurrentPage()
    }

    fun send(intent: TopicIntent) {
        when (intent) {
            TopicIntent.Retry -> loadCurrentPage()
        }
    }

    private fun loadCurrentPage() {
        if (!FixedTopicFixtures.isFixedTopic(request.cat, request.post)) {
            _state.update { it.copy(mode = TopicUiState.Mode.Placeholder) }
            return
        }
        _state.update { it.copy(mode = TopicUiState.Mode.Loading) }
        viewModelScope.launch {
            val outcome = runCatching { topicFixtureRepository.loadTopicPage(request.page) }
                .fold(
                    onSuccess = { topic -> TopicUiState.Mode.Loaded(topic) },
                    onFailure = { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        TopicUiState.Mode.Error(error.message ?: "Unknown error")
                    },
                )
            _state.update { it.copy(mode = outcome) }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: TopicRequest): TopicViewModel
    }
}
