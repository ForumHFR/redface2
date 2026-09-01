package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.preferences.NavBarLabelsBootstrapStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [NavBarLabelsBootstrapStore] over a tiny dedicated `SharedPreferences` file (#1138), same
 * pattern as [SharedPreferencesThemeBootstrapStore] (#386): SharedPreferences (not DataStore) on
 * purpose — the nav shell needs a SYNCHRONOUS read for its very first composition, which DataStore
 * cannot provide. One boolean key, nothing sensitive.
 *
 * `apply()` (not `commit()`) like the theme mirror: this preference is re-observed at runtime
 * (eagerly collected by `AppThemeViewModel`), so a divergence left by a process death before the
 * async flush is self-healed by the next DataStore hydration — unlike the start-screen mirror,
 * which is only ever read at cold start and therefore commits synchronously.
 */
@Singleton
class SharedPreferencesNavBarLabelsBootstrapStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NavBarLabelsBootstrapStore {

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    // Default `true` (#666): the historical M3 labelled bar — matches the repository's DataStore
    // read default, so an empty mirror (fresh install, or a user who never toggled) seeds « labels
    // shown » and only a user who explicitly hid them ever sees the mirror return `false`.
    override fun read(): Boolean = prefs.getBoolean(KEY_NAV_BAR_LABELS, true)

    override fun write(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NAV_BAR_LABELS, enabled).apply()
    }

    private companion object {
        const val FILE_NAME = "nav_bar_labels_bootstrap"
        const val KEY_NAV_BAR_LABELS = "nav_bar_labels"
    }
}
