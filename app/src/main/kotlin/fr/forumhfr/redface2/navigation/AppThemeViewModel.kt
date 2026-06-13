package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
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
 * [SharingStarted.Eagerly] so the first DataStore read starts as soon as the ViewModel is created.
 * Until it resolves, the initial value comes from the SYNCHRONOUS [ThemeBootstrapStore] mirror —
 * not a hard-coded SYSTEM default — so a user who forced a theme against the OS no longer sees
 * the OS theme flash on a cold start (#386). MainActivity seeds the window background from the
 * same mirror, covering the pre-first-frame window too.
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    themeBootstrapStore: ThemeBootstrapStore,
) : ViewModel() {

    private val bootstrap = themeBootstrapStore.read()

    val themeMode: StateFlow<ThemeMode> =
        userPreferencesRepository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, bootstrap.themeMode)

    val amoledEnabled: StateFlow<Boolean> =
        userPreferencesRepository.observeAmoledEnabled()
            .stateIn(viewModelScope, SharingStarted.Eagerly, bootstrap.amoledEnabled)

    // #287 — reading presets. No bootstrap mirror (they do not paint the pre-first-frame window),
    // so the seed is just the enum default; DataStore resolves on the first Eagerly read.
    val displayDensity: StateFlow<DisplayDensity> =
        userPreferencesRepository.observeDisplayDensity()
            .stateIn(viewModelScope, SharingStarted.Eagerly, DisplayDensity.COMFORT)

    val fontScale: StateFlow<FontScalePreference> =
        userPreferencesRepository.observeFontScale()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FontScalePreference.M)
}
