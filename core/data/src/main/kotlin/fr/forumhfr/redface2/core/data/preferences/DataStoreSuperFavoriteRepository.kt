package fr.forumhfr.redface2.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Local super-favorite store (#603). Reuses the shared `@UserPreferencesDataStore` Preferences store
 * under a dedicated key (no second DataStore file). Ids are persisted as a `Set<String>` (the only
 * set type DataStore Preferences offers) and parsed back to `Int`, tolerant of a corrupt entry.
 */
@Singleton
class DataStoreSuperFavoriteRepository @Inject constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : SuperFavoriteRepository {

    override fun observeSuperFavoriteTopicIds(): Flow<Set<Int>> =
        dataStore.data
            .map { prefs -> prefs[KEY].orEmpty().mapNotNull(String::toIntOrNull).toSet() }
            .distinctUntilChanged()
            .catch { emit(emptySet()) }

    override suspend fun setSuperFavorite(topicId: Int, enabled: Boolean) {
        // Parented to the process-lifetime scope (cf. DataStoreUserPreferencesRepository.persist) so a
        // long-press sheet dismissed mid-write still commits the toggle.
        externalScope.async {
            dataStore.edit { prefs ->
                val current = prefs[KEY].orEmpty().toMutableSet()
                if (enabled) current.add(topicId.toString()) else current.remove(topicId.toString())
                prefs[KEY] = current
            }
        }.await()
    }

    private companion object {
        val KEY = stringSetPreferencesKey("super_favorite_topic_ids")
    }
}
