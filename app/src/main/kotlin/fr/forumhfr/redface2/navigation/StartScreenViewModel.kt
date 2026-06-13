package fr.forumhfr.redface2.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.preferences.StartScreenBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import javax.inject.Inject

/**
 * Cold-start screen selection (#458), frozen at creation from the SYNCHRONOUS
 * [StartScreenBootstrapStore] mirror — the navigation seeds its initial tab and the Forum back
 * stack during the very first composition, before DataStore's first emission could land
 * (same cold-start rationale as [AppThemeViewModel] / #386).
 *
 * Deliberately NOT observed live: the preference describes what a cold start opens on. Changing
 * it in Settings must not teleport the running session; it applies on the next launch. Saved
 * instance state still wins over this seed (`rememberSaveable` / `rememberNavBackStack` only
 * consume their initial value when there is nothing to restore).
 */
@HiltViewModel
class StartScreenViewModel @Inject constructor(
    startScreenBootstrapStore: StartScreenBootstrapStore,
) : ViewModel() {

    val startScreen: StartScreenPreference = startScreenBootstrapStore.read()
}
