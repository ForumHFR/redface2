package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Messages tab: loads one page of the private-message inbox and exposes a
 * [MessagesUiState] state machine (Loading → Content / Error). Unlike the topic listing it has
 * no cache layer yet (#298 MVP) — each [load] hits the network once. The inbox requires an
 * authenticated session; an anonymous/expired session surfaces as [MessagesUiState.Mode.Error]
 * because HFR redirects `cat=prive` to login.
 */
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: MessagesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    init {
        load(page = 1)
    }

    /** Navigates to another inbox page (pager). No-op for out-of-range pages. */
    fun selectPage(page: Int) {
        if (page < 1) return
        load(page)
    }

    /** Re-runs the current page load after an error. */
    fun retry() {
        load(_state.value.page)
    }

    /** Pull-to-refresh: reloads the current page, keeping the existing content visible. */
    fun refresh() {
        load(_state.value.page, refreshing = true)
    }

    private fun load(page: Int, refreshing: Boolean = false) {
        viewModelScope.launch {
            _state.update { current ->
                if (refreshing) {
                    current.copy(isRefreshing = true)
                } else {
                    current.copy(mode = MessagesUiState.Mode.Loading, page = page)
                }
            }
            try {
                val result = repository.getPrivateMessageList(page = page)
                _state.update {
                    it.copy(
                        mode = MessagesUiState.Mode.Content(result.items),
                        page = result.page,
                        totalPages = result.totalPages,
                        isRefreshing = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                _state.update {
                    it.copy(
                        mode = MessagesUiState.Mode.Error(error.message),
                        isRefreshing = false,
                    )
                }
            }
        }
    }
}
