package fr.forumhfr.redface2.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2 finish (#208) — ViewModel for the user profile feature.
 *
 * Fetches the user profile from [ProfileRepository] and exposes [ProfileUiState]
 * to the UI. Does **not** own the navigation decision to open the full page — that
 * is hoisted to `:app` via a callback on the bottom sheet composable, keeping the
 * feature module navigation-graph-free.
 *
 * Uses `@AssistedInject` so that [userId], [pseudoHint], and [avatarUrlHint] can be
 * supplied at construction time by the Compose caller (`hiltViewModel(creationCallback
 * = { factory -> factory.create(...) })`), while [profileRepository] is injected by
 * Hilt's usual component binding.
 */
@HiltViewModel(assistedFactory = ProfileViewModel.Factory::class)
class ProfileViewModel @AssistedInject constructor(
    private val profileRepository: ProfileRepository,
    @Assisted("userId") private val userId: Int,
    @Assisted("pseudoHint") private val pseudoHint: String,
    @Assisted("avatarUrlHint") private val avatarUrlHint: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState.initial(userId, pseudoHint, avatarUrlHint),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        loadProfile()
    }

    fun onIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.Retry -> loadProfile()
        }
    }

    private fun loadProfile() {
        _state.update { it.copy(mode = ProfileUiState.Mode.Loading) }
        viewModelScope.launch {
            profileRepository.getProfile(userId).fold(
                onSuccess = { profile ->
                    _state.update { it.copy(mode = ProfileUiState.Mode.Loaded(profile)) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            mode = ProfileUiState.Mode.Error(
                                error.message ?: "Erreur inconnue",
                            ),
                        )
                    }
                },
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("userId") userId: Int,
            @Assisted("pseudoHint") pseudoHint: String,
            @Assisted("avatarUrlHint") avatarUrlHint: String?,
        ): ProfileViewModel
    }

}
