package fr.forumhfr.redface2.core.data.smiley

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.data.preferences.normalizeLocalAccountOwner
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageFailure
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository.Companion.MAX_ENTRIES
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One strict, versioned document per owner. Every read/modify/write happens inside edit. */
@Singleton
class DataStoreSmileyUsageRepository @Inject constructor(
    @param:SmileyUsageDataStore private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : SmileyUsageRepository {
    private val json = Json { encodeDefaults = true }
    private val ranking = compareByDescending<SmileyUsageEntry> { it.count }
        .thenByDescending { it.lastUsedAt }
        .thenBy { it.token }

    override fun observeTop(owner: String, limit: Int): Flow<List<SmileyUsageEntry>> {
        if (owner.isBlank() || limit <= 0) return flowOf(emptyList())
        return dataStore.data
            .map { decode(it[ownerKey(owner)])?.entries.orEmpty().sortedWith(ranking).take(limit) }
            .distinctUntilChanged()
            .catch { error ->
                if (error !is IOException) throw error
                emit(emptyList())
            }
    }

    override suspend fun record(owner: String, usages: Map<String, Int>): SmileyUsageMutationResult = when {
        owner.isBlank() -> failure(SmileyUsageFailure.INVALID_OWNER)
        usages.any { (token, count) -> token.isBlank() || count <= 0 } -> failure(SmileyUsageFailure.INVALID_USAGES)
        usages.isEmpty() -> SmileyUsageMutationResult.Unchanged
        else -> mutate(owner) { current ->
            val now = clock.millis()
            val entries = current.entries.associateBy { it.token }.toMutableMap()
            usages.forEach { (token, occurrences) ->
                val previous = entries[token]?.count ?: 0
                // Saturate instead of wrapping a long-lived counter into a negative value.
                val count = previous + occurrences.toLong().coerceAtMost(Long.MAX_VALUE - previous)
                entries[token] = SmileyUsageEntry(token, count, now)
            }
            Document(CURRENT_VERSION, entries.values.sortedWith(ranking).take(MAX_ENTRIES))
        }
    }

    override suspend fun clear(owner: String): SmileyUsageMutationResult = mutate(owner) { null }

    private suspend fun mutate(owner: String, transform: (Document) -> Document?): SmileyUsageMutationResult {
        if (owner.isBlank()) return failure(SmileyUsageFailure.INVALID_OWNER)
        val key = ownerKey(owner)
        var result: SmileyUsageMutationResult = SmileyUsageMutationResult.Unchanged
        return try {
            dataStore.edit { preferences ->
                val current = decode(preferences[key])
                if (current == null) {
                    result = failure(SmileyUsageFailure.UNREADABLE_DOCUMENT)
                } else {
                    val next = transform(current)
                    result = when {
                        next == null && key in preferences -> {
                            preferences.remove(key)
                            SmileyUsageMutationResult.Updated
                        }
                        next == null || next == current -> SmileyUsageMutationResult.Unchanged
                        else -> {
                            preferences[key] = json.encodeToString(next)
                            SmileyUsageMutationResult.Updated
                        }
                    }
                }
            }
            result
        } catch (_: IOException) {
            failure(SmileyUsageFailure.STORAGE_FAILURE)
        }
    }

    private fun decode(raw: String?): Document? {
        if (raw == null) return Document(CURRENT_VERSION, emptyList())
        return try {
            json.decodeFromString<Document>(raw).takeIf { document ->
                document.version == CURRENT_VERSION && document.entries.size <= MAX_ENTRIES &&
                    document.entries.all { it.token.isNotBlank() && it.count > 0 } &&
                    document.entries.map { it.token }.toSet().size == document.entries.size
            }
        } catch (_: SerializationException) {
            null
        }
    }

    private fun ownerKey(owner: String): Preferences.Key<String> =
        stringPreferencesKey("smiley_usage_${normalizeLocalAccountOwner(owner)}")

    private fun failure(reason: SmileyUsageFailure) = SmileyUsageMutationResult.Failure(reason)

    @Serializable
    private data class Document(
        @SerialName("version") val version: Int,
        @SerialName("entries") val entries: List<SmileyUsageEntry>,
    )

    private companion object {
        // Raising this version requires a read migration; never strand existing valid documents.
        const val CURRENT_VERSION = 1
    }
}
