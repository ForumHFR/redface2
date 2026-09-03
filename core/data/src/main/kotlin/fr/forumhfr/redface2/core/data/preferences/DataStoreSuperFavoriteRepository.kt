package fr.forumhfr.redface2.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.matches
import fr.forumhfr.redface2.core.model.Flag
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Local super-favorite store (#603). Reuses the shared `@UserPreferencesDataStore` Preferences store
 * under a dedicated key (no second DataStore file). Entries are persisted as a `Set<String>` (the only
 * set type DataStore Preferences offers), with a versioned `(cat, topicId, subcat, title)` encoding.
 * The former topic-id-only values are still decoded as orphan snapshots so no user pin is lost.
 */
@Singleton
class DataStoreSuperFavoriteRepository @Inject constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : SuperFavoriteRepository {

    override fun observeSuperFavoriteTopics(): Flow<Set<SuperFavoriteTopic>> =
        dataStore.data
            .map { prefs -> prefs[SUPER_FAVORITE_KEY].orEmpty().mapNotNull(::decodeEntry).toSet() }
            .distinctUntilChanged()
            // Audit #1 — rethrow CancellationException before degrading: a bare `catch { emit(...) }`
            // swallows ALL throwables, including the cooperative cancellation that structured
            // concurrency relies on (mirrors DefaultFlagRepository.fetchStickyFlagSupplement). Only a
            // genuine DataStore read error degrades to an empty set (e.g. corrupt prefs file).
            .catch { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not read super-favorite topics; degrading to empty set", e)
                emit(emptySet())
            }

    override suspend fun setSuperFavorite(flag: Flag, enabled: Boolean) {
        // Parented to the process-lifetime scope (cf. DataStoreUserPreferencesRepository.persist) so a
        // long-press sheet dismissed mid-write still commits the toggle.
        externalScope.async {
            dataStore.edit { prefs ->
                val current = prefs[SUPER_FAVORITE_KEY].orEmpty()
                    .mapNotNull(::decodeEntry)
                    .filterNot { it.matches(flag) }
                    .toMutableSet()
                if (enabled) current.add(flag.toSuperFavoriteTopic())
                prefs[SUPER_FAVORITE_KEY] = current.mapTo(mutableSetOf(), ::encodeEntry)
            }
        }.await()
    }
}

private const val TAG = "SuperFavoriteRepo"
private const val VERSION = "v1"
private const val FIELD_SEPARATOR = "|"
private const val NULL_SUBCAT = "_"
private val SUPER_FAVORITE_KEY = stringSetPreferencesKey("super_favorite_topic_ids")
private val TITLE_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val TITLE_DECODER: Base64.Decoder = Base64.getUrlDecoder()

private fun Flag.toSuperFavoriteTopic(): SuperFavoriteTopic = SuperFavoriteTopic(
    cat = cat,
    topicId = topicId,
    title = title,
    subcat = subcat,
)

private fun encodeEntry(topic: SuperFavoriteTopic): String {
    val cat = topic.cat
    if (cat == null) return topic.topicId.toString()
    val subcat = topic.subcat?.toString() ?: NULL_SUBCAT
    val encodedTitle = TITLE_ENCODER.encodeToString(
        topic.title.orEmpty().toByteArray(Charsets.UTF_8),
    )
    return listOf(
        VERSION,
        cat.toString(),
        topic.topicId.toString(),
        subcat,
        encodedTitle,
    ).joinToString(FIELD_SEPARATOR)
}

private fun decodeEntry(raw: String): SuperFavoriteTopic? =
    decodeLegacyEntry(raw) ?: decodeVersionedEntry(raw)

private fun decodeLegacyEntry(raw: String): SuperFavoriteTopic? =
    raw.toIntOrNull()?.let { legacyId ->
        SuperFavoriteTopic(cat = null, topicId = legacyId, title = null, subcat = null)
    }

private fun decodeVersionedEntry(raw: String): SuperFavoriteTopic? {
    val parts = raw.split(FIELD_SEPARATOR, limit = 5)
    val hasExpectedShape = parts.size == 5 && parts[0] == VERSION
    val cat = parts.getOrNull(1)?.takeIf { hasExpectedShape }?.toIntOrNull()
    val topicId = parts.getOrNull(2)?.takeIf { hasExpectedShape }?.toIntOrNull()
    return if (cat != null && topicId != null) {
        SuperFavoriteTopic(
            cat = cat,
            topicId = topicId,
            title = parts[4].decodeTitle(),
            subcat = parts[3].decodeSubcat(),
        )
    } else {
        null
    }
}

private fun String.decodeSubcat(): Int? =
    takeUnless { it == NULL_SUBCAT }?.toIntOrNull()

private fun String.decodeTitle(): String? =
    runCatching {
        String(TITLE_DECODER.decode(this), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }
