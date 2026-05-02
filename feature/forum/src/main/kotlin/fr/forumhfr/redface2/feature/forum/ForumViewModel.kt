package fr.forumhfr.redface2.feature.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Forum home tab — exposes the list of HFR top-level categories.
 * The repository is REST-backed (ADR-003); the home tab itself only needs the public
 * categories list, so we use the anonymous fetch path indirectly via the repository.
 */
@HiltViewModel
class ForumViewModel @Inject constructor(
    private val forumRepository: ForumRepository,
) : ViewModel() {

    val uiState: StateFlow<ForumUiState> = forumRepository.observeCategories()
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ForumUiState.Loading,
        )

    fun refresh() {
        viewModelScope.launch { forumRepository.refreshCategories() }
    }

    private fun ForumResult<List<fr.forumhfr.redface2.core.model.Category>>.toUiState(): ForumUiState =
        when (this) {
            ForumResult.Loading -> ForumUiState.Loading
            is ForumResult.Success -> ForumUiState.Content(value)
            is ForumResult.Failure -> ForumUiState.Error(cause.message)
        }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
