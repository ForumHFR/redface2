package fr.forumhfr.redface2.core.data.smiley

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.data.preferences.normalizeLocalAccountOwner
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyExportResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyFailure
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository.Companion.MAX_DOCUMENT_BYTES
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository.Companion.MAX_FAVORITES
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.LocalFavoriteSmileySnapshot
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One versioned JSON document per explicit owner, in a dedicated Preferences file. Every mutation
 * reads and validates inside DataStore's transaction; no cached read can overwrite a concurrent edit.
 * Unknown/corrupt documents stay untouched, including on clear. No logout hook or anonymous adoption.
 */
@Singleton
class DataStoreLocalFavoriteSmileyRepository @Inject constructor(
    @param:LocalFavoriteSmileyDataStore private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : LocalFavoriteSmileyRepository {
    // Strict decoding also refuses unknown fields instead of silently dropping them on a rewrite.
    private val json = Json { encodeDefaults = true }

    override fun observeFavorites(owner: String): Flow<List<LocalFavoriteSmiley>> {
        if (owner.isBlank()) return flowOf(emptyList())
        val key = ownerKey(owner)
        return dataStore.data
            .map { decode(it[key])?.favorites.orEmpty() }
            .distinctUntilChanged()
            .catch { error ->
                if (error !is IOException) throw error
                emit(emptyList())
            }
    }

    override suspend fun add(owner: String, token: String, imageUrl: String?): LocalFavoriteSmileyMutationResult {
        if (token.isBlank()) return failure(LocalFavoriteSmileyFailure.INVALID_TOKEN)
        return mutate(owner) { current ->
            when {
                current.favorites.any { it.token == token } ->
                    Change(current, LocalFavoriteSmileyMutationResult.AlreadyPresent)
                current.favorites.size >= MAX_FAVORITES ->
                    Change(current, LocalFavoriteSmileyMutationResult.LimitReached)
                else -> {
                    val now = clock.millis()
                    Change(
                        current.copy(
                            favorites = current.favorites + LocalFavoriteSmiley(token, imageUrl, now),
                            tombstones = current.tombstones.filterNot { it.token == token },
                            orderUpdatedAt = now,
                        ),
                        LocalFavoriteSmileyMutationResult.Added,
                    )
                }
            }
        }
    }

    override suspend fun remove(owner: String, token: String): LocalFavoriteSmileyMutationResult {
        if (token.isBlank()) return failure(LocalFavoriteSmileyFailure.INVALID_TOKEN)
        return mutate(owner) { current ->
            if (current.favorites.none { it.token == token }) {
                Change(current, LocalFavoriteSmileyMutationResult.Unchanged)
            } else {
                val now = clock.millis()
                Change(
                    current.copy(
                        favorites = current.favorites.filterNot { it.token == token },
                        tombstones = current.tombstones + LocalFavoriteSmileySnapshot.Tombstone(token, now),
                        orderUpdatedAt = now,
                    ),
                    LocalFavoriteSmileyMutationResult.Updated,
                    pruneTombstones = true,
                )
            }
        }
    }

    override suspend fun move(
        owner: String,
        token: String,
        beforeToken: String?,
    ): LocalFavoriteSmileyMutationResult {
        if (token.isBlank() || beforeToken?.isBlank() == true) return failure(LocalFavoriteSmileyFailure.INVALID_TOKEN)
        return mutate(owner) { current -> moveBefore(current, token, beforeToken) }
    }

    override suspend fun clear(owner: String): LocalFavoriteSmileyMutationResult =
        mutate(owner) { Change(null, LocalFavoriteSmileyMutationResult.Updated) }

    override suspend fun exportSnapshot(owner: String): LocalFavoriteSmileyExportResult {
        if (owner.isBlank()) return LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.INVALID_OWNER)
        return try {
            val snapshot = decode(dataStore.data.first()[ownerKey(owner)])
            if (snapshot == null) {
                LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.UNREADABLE_DOCUMENT)
            } else {
                LocalFavoriteSmileyExportResult.Success(snapshot)
            }
        } catch (_: IOException) {
            LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE)
        }
    }

    private fun moveBefore(
        current: LocalFavoriteSmileySnapshot,
        token: String,
        beforeToken: String?,
    ): Change {
        val entry = current.favorites.find { it.token == token }
        val hasAnchor = beforeToken == null || current.favorites.any { it.token == beforeToken }
        if (entry == null || token == beforeToken || !hasAnchor) {
            return Change(current, LocalFavoriteSmileyMutationResult.Unchanged)
        }
        val reordered = current.favorites.filterNot { it.token == token }.toMutableList()
        val index = if (beforeToken == null) reordered.size else reordered.indexOfFirst { it.token == beforeToken }
        reordered.add(index, entry)
        return if (reordered == current.favorites) {
            Change(current, LocalFavoriteSmileyMutationResult.Unchanged)
        } else {
            Change(
                current.copy(favorites = reordered, orderUpdatedAt = clock.millis()),
                LocalFavoriteSmileyMutationResult.Updated,
            )
        }
    }

    private suspend fun mutate(
        owner: String,
        transform: (LocalFavoriteSmileySnapshot) -> Change,
    ): LocalFavoriteSmileyMutationResult {
        if (owner.isBlank()) return failure(LocalFavoriteSmileyFailure.INVALID_OWNER)
        val key = ownerKey(owner)
        var result: LocalFavoriteSmileyMutationResult = LocalFavoriteSmileyMutationResult.Unchanged
        return try {
            dataStore.edit { preferences ->
                val current = decode(preferences[key])
                if (current == null) {
                    result = failure(LocalFavoriteSmileyFailure.UNREADABLE_DOCUMENT)
                } else {
                    val change = transform(current)
                    result = applyChange(preferences, key, current, change)
                }
            }
            result
        } catch (_: IOException) {
            failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE)
        }
    }

    private fun applyChange(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        current: LocalFavoriteSmileySnapshot,
        change: Change,
    ): LocalFavoriteSmileyMutationResult = when {
        change.snapshot == current -> change.result
        change.snapshot == null -> {
            if (preferences.remove(key) == null) {
                LocalFavoriteSmileyMutationResult.Unchanged
            } else {
                change.result
            }
        }
        else -> storeDocument(preferences, key, change.snapshot, change.result, change.pruneTombstones)
    }

    private fun storeDocument(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        snapshot: LocalFavoriteSmileySnapshot,
        result: LocalFavoriteSmileyMutationResult,
        pruneTombstones: Boolean,
    ): LocalFavoriteSmileyMutationResult {
        var document = snapshot
        var encoded = json.encodeToString(document)
        while (
            pruneTombstones && document.tombstones.isNotEmpty() &&
            encoded.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES
        ) {
            val oldest = document.tombstones.minBy { it.removedAt }
            document = document.copy(tombstones = document.tombstones - oldest)
            encoded = json.encodeToString(document)
        }
        return if (encoded.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            failure(LocalFavoriteSmileyFailure.DOCUMENT_TOO_LARGE)
        } else {
            preferences[key] = encoded
            result
        }
    }

    private fun decode(raw: String?): LocalFavoriteSmileySnapshot? = when {
        raw == null -> LocalFavoriteSmileySnapshot(
            version = LocalFavoriteSmileySnapshot.CURRENT_VERSION,
            favorites = emptyList(),
            tombstones = emptyList(),
            orderUpdatedAt = 0,
        )
        raw.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES -> null
        else -> decodeDocument(raw)
    }

    private fun decodeDocument(raw: String): LocalFavoriteSmileySnapshot? =
        try {
            json.decodeFromString<LocalFavoriteSmileySnapshot>(raw).takeIf(::isValid)
        } catch (_: SerializationException) {
            null
        }

    private fun isValid(snapshot: LocalFavoriteSmileySnapshot): Boolean {
        val activeTokens = snapshot.favorites.map { it.token }.toSet()
        val removedTokens = snapshot.tombstones.map { it.token }.toSet()
        return snapshot.version == LocalFavoriteSmileySnapshot.CURRENT_VERSION &&
            snapshot.favorites.size <= MAX_FAVORITES &&
            activeTokens.size == snapshot.favorites.size &&
            removedTokens.size == snapshot.tombstones.size &&
            activeTokens.intersect(removedTokens).isEmpty()
    }

    private fun ownerKey(owner: String): Preferences.Key<String> =
        stringPreferencesKey("local_favorite_smileys_${normalizeLocalAccountOwner(owner)}")

    private fun failure(reason: LocalFavoriteSmileyFailure) = LocalFavoriteSmileyMutationResult.Failure(reason)

    private data class Change(
        val snapshot: LocalFavoriteSmileySnapshot?,
        val result: LocalFavoriteSmileyMutationResult,
        val pruneTombstones: Boolean = false,
    )
}
