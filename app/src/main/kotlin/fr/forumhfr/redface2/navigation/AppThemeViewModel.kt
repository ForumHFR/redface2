package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * App-root theme state (#286). Exposes the persisted [ThemeMode] and AMOLED toggle so
 * [RedfaceApp] can compute the effective dark theme passed to `RedfaceTheme` (which previously
 * only read `isSystemInDarkTheme()`).
 *
 * [SharingStarted.Eagerly] so the first DataStore read starts as soon as the ViewModel is created,
 * keeping the initial-default window as short as possible. The default ([ThemeMode.SYSTEM] / amoled
 * off) is shown until that first read resolves — a brief flash (not a single frame) that only a user
 * who forced a non-SYSTEM mode differing from the OS can perceive on a cold start. Acceptable for the
 * default-SYSTEM majority; a dedicated `Loading` gate is the follow-up if that cold-start flash ever
 * becomes a complaint.
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> =
        userPreferencesRepository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val amoledEnabled: StateFlow<Boolean> =
        userPreferencesRepository.observeAmoledEnabled()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
