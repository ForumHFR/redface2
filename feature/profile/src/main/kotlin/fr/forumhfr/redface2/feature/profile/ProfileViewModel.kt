package fr.forumhfr.redface2.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import kotlinx.coroutines.Job
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
 *
 * Review feedback I7/I8: error states surface an [ProfileUiState.ErrorKind] (the UI
 * resolves the user-visible string via `stringResource`) and a [loadJob] is held so
 * concurrent Retry taps cancel the previous in-flight load.
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

    /**
     * In-flight load job. Held so a Retry (or a follow-up [loadProfile] call) can
     * cancel a previous load that has not completed yet — without this, rapid taps
     * on Retry spawn N concurrent coroutines whose results race to update [_state].
     */
    private var loadJob: Job? = null

    init {
        loadProfile()
    }

    fun onIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.Retry -> loadProfile()
        }
    }

    private fun loadProfile() {
        // Review feedback I8: cancel any in-flight load before starting a new one.
        // Without this, a tap-spam on « Réessayer » fans out N concurrent coroutines
        // and the slowest one wins the race to update _state.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(mode = ProfileUiState.Mode.Loading) }
            profileRepository.getProfile(userId).fold(
                onSuccess = { profile ->
                    _state.update { it.copy(mode = ProfileUiState.Mode.Loaded(profile)) }
                },
                onFailure = { error ->
                    // Review feedback I7: ViewModel must not carry a localised String. It
                    // surfaces the kind + cause ; the UI resolves the message via
                    // `stringResource(R.string.profile_error_load_failed)`.
                    _state.update {
                        it.copy(
                            mode = ProfileUiState.Mode.Error(
                                kind = ProfileUiState.ErrorKind.Unknown,
                                cause = error,
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
