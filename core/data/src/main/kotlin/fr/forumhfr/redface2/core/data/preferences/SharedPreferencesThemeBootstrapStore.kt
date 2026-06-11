package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrap
import fr.forumhfr.redface2.core.domain.preferences.ThemeBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ThemeBootstrapStore] over a tiny dedicated `SharedPreferences` file (#386).
 *
 * SharedPreferences (not DataStore) on purpose : the whole point of the mirror is a
 * synchronous read on the cold-start path, which DataStore cannot provide. The file holds
 * two keys, so the one-off synchronous load is negligible. This does NOT reopen ADR-002
 * (credentials stay in DataStore + Keystore) — nothing sensitive lives here.
 */
@Singleton
class SharedPreferencesThemeBootstrapStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ThemeBootstrapStore {

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override fun read(): ThemeBootstrap = ThemeBootstrap(
        // Defensive read, same stance as the repository's DataStore read : an unknown stored
        // value (downgrade, manual edit) falls back to SYSTEM instead of crashing.
        themeMode = prefs.getString(KEY_THEME_MODE, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
        amoledEnabled = prefs.getBoolean(KEY_AMOLED_ENABLED, false),
    )

    override fun writeThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    override fun writeAmoledEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED_ENABLED, enabled).apply()
    }

    private companion object {
        const val FILE_NAME = "theme_bootstrap"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AMOLED_ENABLED = "amoled_enabled"
    }
}
