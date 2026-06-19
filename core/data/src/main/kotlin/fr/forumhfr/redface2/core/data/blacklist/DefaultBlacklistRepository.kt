package fr.forumhfr.redface2.core.data.blacklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.coroutines.ApplicationScope
import fr.forumhfr.redface2.core.model.blacklist.BlacklistDocument
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Local [BlacklistRepository] backed by a dedicated Preferences [DataStore] holding a single
 * versioned JSON document (see [BlacklistDocument]). Reads tolerate a missing or corrupt document by
 * falling back to an empty blacklist, so a bad write can never hide the whole forum.
 *
 * Writes are read-modify-write **inside** [DataStore.edit] (transactional) and run on the
 * application-lifetime [externalScope] via [persist], mirroring
 * [fr.forumhfr.redface2.core.data.preferences.DataStoreUserPreferencesRepository]: a caller whose
 * coroutine is cancelled mid-write (a menu sheet dismissed right after the tap) still lands the
 * change.
 */
@Singleton
class DefaultBlacklistRepository @Inject constructor(
    @param:BlacklistDataStore private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val externalScope: CoroutineScope,
) : BlacklistRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun observeEntries(): Flow<List<BlacklistEntry>> =
        dataStore.data
            .map { prefs -> readDocument(prefs).entries }
            .catch { emit(emptyList()) }

    override fun observeBlockedCanonicals(): Flow<Set<String>> =
        observeEntries()
            .map { entries -> entries.mapTo(LinkedHashSet()) { it.canonical } }
            .distinctUntilChanged()

    override suspend fun isBlocked(pseudo: String): Boolean {
        val canonical = canonicalizePseudo(pseudo)
        if (canonical.isEmpty()) return false
        return readDocument(dataStore.data.first()).entries.any { it.canonical == canonical }
    }

    override suspend fun block(pseudo: String) {
        val canonical = canonicalizePseudo(pseudo)
        if (canonical.isEmpty()) return
        val display = pseudo.trim()
        persist {
            dataStore.edit { prefs ->
                val document = readDocument(prefs)
                if (document.entries.none { it.canonical == canonical }) {
                    val entry = BlacklistEntry(canonical, display, System.currentTimeMillis())
                    prefs[KEY_DOCUMENT] = json.encodeToString(
                        BlacklistDocument.serializer(),
                        document.copy(entries = document.entries + entry),
                    )
                }
            }
        }
    }

    override suspend fun unblock(pseudo: String) {
        // Canonicalise so the call works whether the caller passes a raw author pseudo (post menu) or
        // an already-canonical key (management screen). canonicalizePseudo is idempotent, so passing a
        // stored BlacklistEntry.canonical back through it is a no-op.
        val canonical = canonicalizePseudo(pseudo)
        if (canonical.isEmpty()) return
        persist {
            dataStore.edit { prefs ->
                val document = readDocument(prefs)
                val remaining = document.entries.filterNot { it.canonical == canonical }
                if (remaining.size != document.entries.size) {
                    prefs[KEY_DOCUMENT] = json.encodeToString(
                        BlacklistDocument.serializer(),
                        document.copy(entries = remaining),
                    )
                }
            }
        }
    }

    private fun readDocument(prefs: Preferences): BlacklistDocument {
        val raw = prefs[KEY_DOCUMENT] ?: return BlacklistDocument()
        return try {
            json.decodeFromString(BlacklistDocument.serializer(), raw)
        } catch (_: SerializationException) {
            // A v1 document we cannot parse is treated as empty; the next write replaces it. For v1
            // this is acceptable (the data was unreadable anyway). Revisit once a v2 shape ships so an
            // older reader never silently overwrites a newer document.
            BlacklistDocument()
        }
    }

    private suspend fun persist(block: suspend () -> Unit) {
        externalScope.async { block() }.await()
    }

    private companion object {
        val KEY_DOCUMENT = stringPreferencesKey("blacklist_v1")
    }
}
