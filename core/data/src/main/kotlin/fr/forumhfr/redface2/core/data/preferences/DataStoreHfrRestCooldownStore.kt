package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import fr.forumhfr.redface2.core.network.HfrRestCooldown
import fr.forumhfr.redface2.core.network.HfrRestCooldownStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DataStoreHfrRestCooldownStore @Inject constructor(
    @UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : HfrRestCooldownStore {
    override suspend fun read(): HfrRestCooldown? {
        val preferences = dataStore.data.first()
        return preferences[BLOCKED_UNTIL]?.let {
            HfrRestCooldown(Instant.ofEpochMilli(it), preferences[LEVEL] ?: 0)
        }
    }

    override suspend fun write(cooldown: HfrRestCooldown?) {
        dataStore.edit { preferences ->
            if (cooldown == null) {
                preferences.remove(BLOCKED_UNTIL)
                preferences.remove(LEVEL)
            } else {
                preferences[BLOCKED_UNTIL] = cooldown.until.toEpochMilli()
                preferences[LEVEL] = cooldown.level
            }
        }
    }

    private companion object {
        val BLOCKED_UNTIL = longPreferencesKey("hfr_rest_blocked_until_ms")
        val LEVEL = intPreferencesKey("hfr_rest_cooldown_level")
    }
}
