package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.Category
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings section « Démarrage » (#458), its OWN ViewModel instead of more state on the already
 * detekt-budget-bound [SettingsViewModel]: the section bundles a preference pair (screen +
 * optional Forum category) with the category list it needs for its picker — an isolated concern
 * with an isolated lifecycle.
 *
 * Hydration follows the [SettingsViewModel] convention: read-once from the repository, guarded
 * against overwriting a selection the user already made locally ([StartScreenSettingsState
 * .touchedLocally]). Persistence is the usual optimistic-flip with rollback + error flag.
 */
@HiltViewModel
class StartScreenSettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    forumRepository: ForumRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StartScreenSettingsState())
    val state: StateFlow<StartScreenSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val persisted = userPreferencesRepository.observeStartScreen().first()
            _state.update { current ->
                if (current.touchedLocally) current else current.copy(preference = persisted)
            }
        }
        // Category list for the Forum picker. Loading/Failure only drive the picker's helper
        // text — the segmented choice itself never depends on the network.
        forumRepository.observeCategories()
            .onEach { result ->
                _state.update { current ->
                    when (result) {
                        ForumResult.Loading -> current.copy(categoriesLoading = true)
                        is ForumResult.Success -> current.copy(
                            categoriesLoading = false,
                            categoriesError = false,
                            categories = result.value,
                        )
                        is ForumResult.Failure -> current.copy(
                            categoriesLoading = false,
                            categoriesError = true,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun submit(intent: StartScreenSettingsIntent) {
        when (intent) {
            is StartScreenSettingsIntent.ScreenChanged -> {
                val current = _state.value.preference
                update(
                    StartScreenPreference(
                        screen = intent.screen,
                        // Keep a previously picked category when flipping back to FORUM; any
                        // other screen drops it (it would be meaningless and is not persisted).
                        forumCatId = current.forumCatId
                            .takeIf { intent.screen == StartScreenChoice.FORUM },
                    ),
                )
            }
            is StartScreenSettingsIntent.ForumCategoryChanged -> update(
                _state.value.preference.copy(forumCatId = intent.catId),
            )
        }
    }

    private fun update(preference: StartScreenPreference) {
        val previous = _state.value.preference
        if (previous == preference) return
        _state.update {
            it.copy(
                preference = preference,
                isUpdating = true,
                persistError = false,
                touchedLocally = true,
            )
        }
        viewModelScope.launch {
            try {
                userPreferencesRepository.setStartScreen(preference)
                _state.update { it.copy(isUpdating = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                _state.update {
                    it.copy(preference = previous, isUpdating = false, persistError = true)
                }
            }
        }
    }
}

/** UI state of the « Démarrage » section (#458). */
data class StartScreenSettingsState(
    val preference: StartScreenPreference = StartScreenPreference(),
    val isUpdating: Boolean = false,
    val persistError: Boolean = false,
    /** Set on the first local change — the late hydration read must not overwrite it. */
    val touchedLocally: Boolean = false,
    val categories: List<Category> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesError: Boolean = false,
) {
    val canChange: Boolean
        get() = !isUpdating
}

sealed interface StartScreenSettingsIntent {
    data class ScreenChanged(val screen: StartScreenChoice) : StartScreenSettingsIntent

    /** `null` = forum root (no pre-stacked category). */
    data class ForumCategoryChanged(val catId: Int?) : StartScreenSettingsIntent
}
