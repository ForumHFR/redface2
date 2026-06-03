package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for one private-message conversation. Receives its route arguments via Hilt
 * assisted injection ([PrivateMessageThreadRequest]), mirroring [TopicViewModel]. Loads the
 * requested page once (no cache in the #298 MVP) and forwards the inbox-row correspondent as
 * the parser fallback.
 */
@HiltViewModel(assistedFactory = PrivateMessageThreadViewModel.Factory::class)
class PrivateMessageThreadViewModel @AssistedInject constructor(
    @Assisted private val request: PrivateMessageThreadRequest,
    private val repository: MessagesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivateMessageThreadUiState.initial(request))
    val state: StateFlow<PrivateMessageThreadUiState> = _state.asStateFlow()

    init {
        load(request.page.coerceAtLeast(1))
    }

    fun selectPage(page: Int) {
        if (page < 1) return
        load(page)
    }

    fun retry() {
        load(_state.value.page)
    }

    private fun load(page: Int) {
        viewModelScope.launch {
            _state.update { it.copy(mode = PrivateMessageThreadUiState.Mode.Loading, page = page) }
            try {
                val thread = repository.getPrivateMessageThread(
                    threadId = request.threadId,
                    page = page,
                    fallbackCorrespondent = request.correspondent,
                )
                _state.update {
                    it.copy(
                        mode = PrivateMessageThreadUiState.Mode.Content(thread),
                        page = thread.page,
                        totalPages = thread.totalPages,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                _state.update { it.copy(mode = PrivateMessageThreadUiState.Mode.Error(error.message)) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(request: PrivateMessageThreadRequest): PrivateMessageThreadViewModel
    }
}
