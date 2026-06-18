package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
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

    // TU 2788511 — accent colour family (rose default ↔ vivid « REDFACE1 » red). Eager like the
    // reading presets; ROSE seed. No bootstrap mirror: the accent re-tints only primary/secondary
    // roles, NOT the window background, so there is no pre-first-frame window to seed (cf. density).
    val accentColor: StateFlow<AccentColor> =
        userPreferencesRepository.observeAccentColor()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.ROSE)

    // #287 — reading presets. No bootstrap mirror (they do not paint the pre-first-frame window),
    // so the seed is just the enum default; DataStore resolves on the first Eagerly read.
    val displayDensity: StateFlow<DisplayDensity> =
        userPreferencesRepository.observeDisplayDensity()
            .stateIn(viewModelScope, SharingStarted.Eagerly, DisplayDensity.COMFORT)

    val fontScale: StateFlow<FontScalePreference> =
        userPreferencesRepository.observeFontScale()
            .stateIn(viewModelScope, SharingStarted.Eagerly, FontScalePreference.M)

    // #332 — « fold long quotes » reading preference, eagerly collected like the presets above.
    // No bootstrap mirror (it does not paint the pre-first-frame window); the seed is the `true`
    // default and DataStore resolves on the first Eagerly read.
    val foldLongQuotes: StateFlow<Boolean> =
        userPreferencesRepository.observeFoldLongQuotes()
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // #445 — debug bounds overlay toggle (dev channel only; the channel gate lives in RedfaceApp).
    // No bootstrap mirror: it does not paint the pre-first-frame window, so the seed is the `false`
    // default and DataStore resolves on the first Eagerly read.
    val debugBoundsOverlay: StateFlow<Boolean> =
        userPreferencesRepository.observeDebugBoundsOverlay()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // #518 — immersive mode: hide the bottom Android system navigation bar. Applied on the host window
    // by RedfaceApp. Eagerly collected like the presets above; off by default, no bootstrap mirror
    // (it does not paint the pre-first-frame window).
    val hideSystemNavBar: StateFlow<Boolean> =
        userPreferencesRepository.observeHideSystemNavBar()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // #518 follow-up — in-app back button shown while immersive mode is active. Default true (it only
    // renders when hideSystemNavBar is on), eager like the presets above.
    val immersiveBackButton: StateFlow<Boolean> =
        userPreferencesRepository.observeImmersiveBackButton()
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // #518 follow-up — when the hidden system nav bar auto-reveals from inside the app (scroll-driven).
    // Default MANUAL (swipe-only, historical #518). Only acts while hideSystemNavBar is on. Eager seed.
    val immersiveNavBarReveal: StateFlow<ImmersiveNavBarReveal> =
        userPreferencesRepository.observeImmersiveNavBarReveal()
            .stateIn(viewModelScope, SharingStarted.Eagerly, ImmersiveNavBarReveal.MANUAL)
}
