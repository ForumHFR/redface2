package fr.forumhfr.redface2.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Forum home tab — exposes the list of HFR top-level categories.
 * The repository is REST-backed (ADR-003); the home tab itself only needs the public
 * categories list, so we use the anonymous fetch path indirectly via the repository.
 *
 * `isRefreshing` is toggled around the user-driven refresh round-trip so a Material 3
 * PullToRefresh indicator can stay anchored over the existing content without wiping
 * the list back to a cold spinner.
 */
@HiltViewModel
class ForumViewModel @Inject constructor(
    private val forumRepository: ForumRepository,
) : ViewModel() {

    private val _isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val uiState: StateFlow<ForumUiState> = forumRepository.observeCategories()
        .map { it.toUiState() }
        // Suppress transient `Loading` re-emitted by refreshCategories() once we have
        // a Content to keep showing — see CategoryViewModel.keepContentDuringRefresh
        // for the same pattern at the per-category screen level.
        .keepContentDuringRefresh()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ForumUiState.Loading,
        )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                forumRepository.refreshCategories()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun ForumResult<List<Category>>.toUiState(): ForumUiState =
        when (this) {
            ForumResult.Loading -> ForumUiState.Loading
            is ForumResult.Success -> ForumUiState.Content(value)
            is ForumResult.Failure -> ForumUiState.Error(cause.message)
        }

    private fun Flow<ForumUiState>.keepContentDuringRefresh(): Flow<ForumUiState> = flow {
        var lastContent: ForumUiState.Content? = null
        collect { value ->
            when (value) {
                is ForumUiState.Content -> {
                    lastContent = value
                    emit(value)
                }
                ForumUiState.Loading -> if (lastContent != null) {
                    // Skip — keep showing the previous content under a refresh spinner.
                } else {
                    emit(value)
                }
                is ForumUiState.Error -> emit(value)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
