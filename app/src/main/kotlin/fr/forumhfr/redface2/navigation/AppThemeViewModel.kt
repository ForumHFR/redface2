package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.domain.preferences.NavBarLabelsBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.domain.preferences.SmileyPickerDecoration
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * App-root theme state (#286/#883). Exposes the persisted [ThemeMode] and complete colour
 * preferences so [RedfaceApp] can compute the effective dark theme passed to `RedfaceTheme` and
 * apply accent/surface choices atomically.
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
    navBarLabelsBootstrapStore: NavBarLabelsBootstrapStore,
) : ViewModel() {

    private val bootstrap = themeBootstrapStore.read()
    private val navBarLabelsSeed = navBarLabelsBootstrapStore.read()

    val themeMode: StateFlow<ThemeMode> =
        userPreferencesRepository.observeThemeMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, bootstrap.themeMode)

    val themeColorPreferences: StateFlow<ThemeColorPreferences> =
        userPreferencesRepository.observeThemeColorPreferences()
            .stateIn(viewModelScope, SharingStarted.Eagerly, bootstrap.colorPreferences)

    // #1207 — app-root chooser policy, exposed through RedfaceTheme's CompositionLocal so every
    // explicit external-link menu observes one live global value without feature-level injection.
    val alwaysAskLinkApp: StateFlow<Boolean> =
        userPreferencesRepository.observeAlwaysAskLinkApp()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // #105 — « afficher l'ascenseur » reading preference, eagerly collected like the presets above.
    // No bootstrap mirror (it does not paint the pre-first-frame window); the seed is the `true`
    // default and DataStore resolves on the first Eagerly read.
    val showScrollbar: StateFlow<Boolean> =
        userPreferencesRepository.observeShowScrollbar()
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // #973 (§8 [AMENDEMENT-v1.5-2]) — block-GIF display profile, eagerly collected like the
    // reading presets above (#332 model). No bootstrap mirror (it does not paint the
    // pre-first-frame window); the seed is the M default and DataStore resolves on the first
    // Eagerly read (unknown persisted value already falls back to M in the repository).
    val mediaDisplayProfile: StateFlow<MediaDisplayProfile> =
        userPreferencesRepository.observeMediaDisplayProfile()
            .stateIn(viewModelScope, SharingStarted.Eagerly, MediaDisplayProfile.M)

    // #991 — maximum fImage width of content images, eagerly collected like the media profile.
    // Seed = P95 so the historical 0.95 cap remains visible until DataStore hydrates.
    val postImageMaxWidth: StateFlow<PostImageMaxWidth> =
        userPreferencesRepository.observePostImageMaxWidth()
            .stateIn(viewModelScope, SharingStarted.Eagerly, PostImageMaxWidth.DEFAULT)

    // #985 — content-image corners, eagerly collected next to their width preference. The
    // historical 8 dp ROUNDED preset is the seed until DataStore hydrates.
    val postImageCorners: StateFlow<PostImageCorners> =
        userPreferencesRepository.observePostImageCorners()
            .stateIn(viewModelScope, SharingStarted.Eagerly, PostImageCorners.DEFAULT)

    // #989 — the smiley picker's cell delimiter, collected at the shell like the reading presets so
    // RedfaceTheme can seed LocalSmileyPickerDecoration. Seed = NONE, the shipped default (an
    // unknown persisted value already falls back to NONE in the repository).
    val smileyPickerDecoration: StateFlow<SmileyPickerDecoration> =
        userPreferencesRepository.observeSmileyPickerDecoration()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SmileyPickerDecoration.NONE)

    // #666 — show the labels under the bottom-nav icons. Eagerly collected at the shell so the
    // NavigationSuiteScaffold can drop the labels. Seeded from the SYNCHRONOUS bootstrap mirror
    // (#1138) — NOT a hard-coded `true` — so a user who hid the labels no longer sees them flash
    // on a cold start before the stored `false` hydrates from DataStore.
    val navBarLabels: StateFlow<Boolean> =
        userPreferencesRepository.observeNavBarLabels()
            .stateIn(viewModelScope, SharingStarted.Eagerly, navBarLabelsSeed)

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
