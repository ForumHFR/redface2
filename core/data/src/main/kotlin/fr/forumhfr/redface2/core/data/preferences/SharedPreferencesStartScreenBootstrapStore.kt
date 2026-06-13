package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.preferences.StartScreenBootstrapStore
import fr.forumhfr.redface2.core.domain.preferences.StartScreenChoice
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StartScreenBootstrapStore] over a tiny dedicated `SharedPreferences` file (#458), same
 * pattern as [SharedPreferencesThemeBootstrapStore] (#386): SharedPreferences on purpose —
 * the navigation needs a SYNCHRONOUS read in its very first composition, which DataStore
 * cannot provide. Two keys, nothing sensitive (a tab name and a public forum category id).
 */
@Singleton
class SharedPreferencesStartScreenBootstrapStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : StartScreenBootstrapStore {

    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override fun read(): StartScreenPreference {
        // Defensive read, same stance as the DataStore side: unknown stored value → FLAGS.
        val screen = prefs.getString(KEY_SCREEN, null)
            ?.let { stored -> StartScreenChoice.entries.firstOrNull { it.name == stored } }
            ?: StartScreenChoice.FLAGS
        val catId = prefs.getInt(KEY_FORUM_CAT, NO_CATEGORY)
            .takeIf { screen == StartScreenChoice.FORUM && it > 0 }
        return StartScreenPreference(screen = screen, forumCatId = catId)
    }

    override fun write(preference: StartScreenPreference) {
        prefs.edit()
            .putString(KEY_SCREEN, preference.screen.name)
            .putInt(KEY_FORUM_CAT, preference.forumCatId ?: NO_CATEGORY)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "start_screen_bootstrap"
        const val KEY_SCREEN = "start_screen"
        const val KEY_FORUM_CAT = "start_forum_cat"
        const val NO_CATEGORY = 0
    }
}
