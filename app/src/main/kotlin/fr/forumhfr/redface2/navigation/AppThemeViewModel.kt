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
 * keeping the initial-frame window where the default ([ThemeMode.SYSTEM] / amoled off) is shown as
 * short as possible. Only a user who picked a non-SYSTEM mode differing from the OS could see a
 * one-frame flicker, which is acceptable for the default-SYSTEM majority.
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
