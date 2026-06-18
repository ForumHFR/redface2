package fr.forumhfr.redface2.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
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
 * Review feedback I7/I8: error states surface the classified `HfrErrorKind` (the UI
 * resolves the user-visible string via `stringResource`) and a [loadJob] is held so
 * concurrent Retry taps cancel the previous in-flight load.
 */
@HiltViewModel(assistedFactory = ProfileViewModel.Factory::class)
class ProfileViewModel @AssistedInject constructor(
    private val profileRepository: ProfileRepository,
    private val blacklistRepository: BlacklistRepository,
    @Assisted("userId") private val userId: Int,
    @Assisted("pseudoHint") private val pseudoHint: String,
    @Assisted("avatarUrlHint") private val avatarUrlHint: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState.initial(userId, pseudoHint, avatarUrlHint),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /**
     * #509 — canonical key of the previewed user. [pseudoHint] is the post author pseudo from the tap
     * site (always present, and the same identity the post menu blocks), so matching the blacklist on
     * its canonical form keeps the two entry points in sync.
     */
    private val pseudoCanonical = canonicalizePseudo(pseudoHint)

    /**
     * In-flight load job. Held so a Retry (or a follow-up [loadProfile] call) can
     * cancel a previous load that has not completed yet — without this, rapid taps
     * on Retry spawn N concurrent coroutines whose results race to update [_state].
     */
    private var loadJob: Job? = null

    init {
        loadProfile()
        observeBlacklist()
    }

    fun onIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.Retry -> loadProfile()
            ProfileIntent.ToggleBlocked -> toggleBlocked()
        }
    }

    /**
     * Keeps [ProfileUiState.isBlocked] in sync with the live blacklist. `observeBlockedCanonicals`
     * emits its current value immediately (documented contract), so the button renders the right label
     * from the first frame, and flips if the same user is (un)blocked elsewhere while the sheet is open.
     */
    private fun observeBlacklist() {
        viewModelScope.launch {
            blacklistRepository.observeBlockedCanonicals().collect { blocked ->
                _state.update { it.copy(isBlocked = pseudoCanonical in blocked) }
            }
        }
    }

    private fun toggleBlocked() {
        viewModelScope.launch {
            if (_state.value.isBlocked) {
                blacklistRepository.unblock(pseudoHint)
            } else {
                blacklistRepository.block(pseudoHint)
            }
            // `isBlocked` is not flipped here: the `observeBlacklist` collector is the single source of
            // truth and updates it once the store write lands.
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
                    // `stringResource(...)`. #324 — the kind is the shared classifier's
                    // verdict so a 5xx outage and a network cut render distinctly.
                    _state.update {
                        it.copy(
                            mode = ProfileUiState.Mode.Error(
                                kind = classifyHfrError(error),
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
