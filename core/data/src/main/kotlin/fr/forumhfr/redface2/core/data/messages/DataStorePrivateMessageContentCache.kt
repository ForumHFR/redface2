package fr.forumhfr.redface2.core.data.messages

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import fr.forumhfr.redface2.core.data.preferences.UserPreferencesDataStore
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCache
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCacheException
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

internal interface PrivateMessageContentCacheMaintenance {
    suspend fun purgeForUser(userId: String)

    suspend fun reconcileOnStartup()
}

/** Repository-facing access gate. Every Room operation shares the preference/purge mutex. */
internal interface PrivateMessageContentAccess {
    suspend fun readIfEnabled(userId: String, threadId: Int, page: Int): PrivateMessageThread?

    suspend fun replaceIfEnabled(
        userId: String,
        thread: PrivateMessageThread,
        fetchedAt: Instant,
        isSessionCurrent: suspend () -> Boolean,
    )
}

/**
 * Safe preference boundary for the opt-in MP content cache.
 *
 * OFF is persisted before the RAM generation advances and before the global Room purge starts.
 * A failed purge leaves [KEY_PURGE_PENDING] set, so [reconcileOnStartup] retries at app startup.
 */
@Singleton
class DataStorePrivateMessageContentCache @Inject internal constructor(
    @param:UserPreferencesDataStore private val dataStore: DataStore<Preferences>,
    private val sessionCache: PrivateMessageThreadSessionCache,
    private val diskCache: PrivateMessageThreadDiskCache,
) : PrivateMessageContentCache, PrivateMessageContentCacheMaintenance, PrivateMessageContentAccess {
    private val roomAccessMutex = Mutex()

    override fun observeEnabled(): Flow<Boolean> = dataStore.data
        .map(::isEffectivelyEnabled)
        .distinctUntilChanged()

    override fun observePurgePending(): Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_PURGE_PENDING] ?: false }
        .distinctUntilChanged()

    override suspend fun isEnabled(): Boolean = observeEnabled().first()

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            withRoomAccess {
                reconcilePendingPurgeLocked(forceWhenOff = false)
                try {
                    dataStore.edit { preferences -> preferences[KEY_ENABLED] = true }
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    throw PrivateMessageContentCacheException.PreferenceWriteFailed(error)
                }
            }
            return
        }

        // Persist OFF before waiting for an in-flight Room operation. New contenders observe OFF;
        // an operation already past its final seal completes before the purge acquired below.
        val purgeRequired = try {
            dataStore.edit { preferences ->
                val wasEnabled = preferences[KEY_ENABLED] ?: false
                val wasPending = preferences[KEY_PURGE_PENDING] ?: false
                preferences[KEY_ENABLED] = false
                preferences[KEY_PURGE_PENDING] = wasEnabled || wasPending
            }[KEY_PURGE_PENDING] ?: false
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw PrivateMessageContentCacheException.PreferenceWriteFailed(error)
        }
        if (!purgeRequired) return

        sessionCache.clearAndAdvanceGeneration()
        try {
            withRoomAccess {
                // A concurrent enable may have completed while OFF was waiting for the mutex.
                // Reassert the caller's terminal state before the serialized purge.
                dataStore.edit { preferences ->
                    preferences[KEY_ENABLED] = false
                    preferences[KEY_PURGE_PENDING] = true
                }
                diskCache.clearAll()
                dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = false }
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw PrivateMessageContentCacheException.PurgeFailed(error)
        }
    }

    override suspend fun readIfEnabled(
        userId: String,
        threadId: Int,
        page: Int,
    ): PrivateMessageThread? = withRoomAccess {
        if (!isEffectivelyEnabled(dataStore.data.first())) return@withRoomAccess null
        val pageFromDisk = diskCache.read(userId, threadId, page)
        // OFF is persisted before it waits for this mutex. Rechecking prevents a read already in
        // progress from escaping as displayable content after that transition has started.
        pageFromDisk.takeIf { isEffectivelyEnabled(dataStore.data.first()) }
    }

    override suspend fun replaceIfEnabled(
        userId: String,
        thread: PrivateMessageThread,
        fetchedAt: Instant,
        isSessionCurrent: suspend () -> Boolean,
    ) = withRoomAccess {
        if (!isEffectivelyEnabled(dataStore.data.first())) return@withRoomAccess
        // This is the final account/generation seal immediately before the Room transaction. OFF,
        // logout and account-switch purges share this mutex, so a write that already passed it is
        // necessarily followed by their purge; a later write observes the advanced generation/OFF.
        if (!isSessionCurrent()) return@withRoomAccess
        diskCache.replace(userId, thread, fetchedAt)
    }

    /** Every account transition purges rows and scrubs SQLite, whether the opt-in is ON or OFF. */
    override suspend fun purgeForUser(userId: String) {
        try {
            withRoomAccess {
                // Persist the fail-closed marker before DELETE. If the scrub fails, the setting
                // may still be stored as ON but its effective state remains OFF until startup or
                // the manual settings retry completes the global purge.
                dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = true }
                diskCache.clearForUser(userId)
                dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = false }
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            runCatching { dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = true } }
            throw PrivateMessageContentCacheException.PurgeFailed(error)
        }
    }

    /** Startup owner: [fr.forumhfr.redface2.core.data.cache.CacheInvalidator]. */
    override suspend fun reconcileOnStartup() {
        val preferences = dataStore.data.first()
        if ((preferences[KEY_ENABLED] ?: false) && !(preferences[KEY_PURGE_PENDING] ?: false)) return
        sessionCache.clearAndAdvanceGeneration()
        try {
            withRoomAccess { reconcilePendingPurgeLocked(forceWhenOff = true) }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw PrivateMessageContentCacheException.PurgeFailed(error)
        }
    }

    override suspend fun retryPendingPurge() {
        if (!(dataStore.data.first()[KEY_PURGE_PENDING] ?: false)) return
        sessionCache.clearAndAdvanceGeneration()
        try {
            withRoomAccess { reconcilePendingPurgeLocked(forceWhenOff = false) }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            throw PrivateMessageContentCacheException.PurgeFailed(error)
        }
    }

    private suspend fun reconcilePendingPurgeLocked(forceWhenOff: Boolean) {
        val preferences = dataStore.data.first()
        val purgePending = preferences[KEY_PURGE_PENDING] ?: false
        val preferenceOff = !(preferences[KEY_ENABLED] ?: false)
        if (!purgePending && !(forceWhenOff && preferenceOff)) return
        dataStore.edit { mutable -> mutable[KEY_PURGE_PENDING] = true }
        diskCache.clearAll()
        dataStore.edit { mutable -> mutable[KEY_PURGE_PENDING] = false }
    }

    private suspend fun <T> withRoomAccess(block: suspend () -> T): T {
        roomAccessMutex.lock()
        return try {
            block()
        } finally {
            roomAccessMutex.unlock()
        }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("private_message_content_cache_enabled")
        val KEY_PURGE_PENDING = booleanPreferencesKey("private_message_content_cache_purge_pending")

        fun isEffectivelyEnabled(preferences: Preferences): Boolean =
            preferences[KEY_ENABLED] == true && preferences[KEY_PURGE_PENDING] != true
    }
}
