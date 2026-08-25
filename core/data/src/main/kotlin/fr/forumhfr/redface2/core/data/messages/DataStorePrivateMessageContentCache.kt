package fr.forumhfr.redface2.core.data.messages

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import fr.forumhfr.redface2.core.data.preferences.UserPreferencesDataStore
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageContentCache
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

    suspend fun reconcilePendingPurge()
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
 * Safe preference boundary for the dormant MP content cache.
 *
 * OFF is persisted before the RAM generation advances and before the global Room purge starts.
 * A failed purge leaves [KEY_PURGE_PENDING] set, so [reconcilePendingPurge] retries at app startup.
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

    override suspend fun isEnabled(): Boolean = observeEnabled().first()

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            withRoomAccess {
                reconcilePendingPurgeLocked()
                dataStore.edit { preferences -> preferences[KEY_ENABLED] = true }
            }
            return
        }

        // Persist OFF before waiting for an in-flight Room operation. New contenders observe OFF;
        // an operation already past its final seal completes before the purge acquired below.
        val purgeRequired = dataStore.edit { preferences ->
            val wasEnabled = preferences[KEY_ENABLED] ?: false
            val wasPending = preferences[KEY_PURGE_PENDING] ?: false
            preferences[KEY_ENABLED] = false
            preferences[KEY_PURGE_PENDING] = wasEnabled || wasPending
        }[KEY_PURGE_PENDING] ?: false
        if (!purgeRequired) return

        sessionCache.clearAndAdvanceGeneration()
        withRoomAccess {
            // A concurrent enable may have completed while OFF was waiting for the mutex. Reassert
            // the caller's terminal state so setEnabled(false) cannot return with the cache active.
            dataStore.edit { preferences ->
                preferences[KEY_ENABLED] = false
                preferences[KEY_PURGE_PENDING] = true
            }
            diskCache.clearAll()
            dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = false }
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

    /** Retries only an explicitly pending failed purge; default-OFF startup never opens the tables. */
    override suspend fun purgeForUser(userId: String) {
        val preferences = dataStore.data.first()
        val cacheEnabled = preferences[KEY_ENABLED] ?: false
        val purgePending = preferences[KEY_PURGE_PENDING] ?: false
        if (!cacheEnabled && !purgePending) return
        withRoomAccess {
            val current = dataStore.data.first()
            if (!(current[KEY_ENABLED] ?: false) && !(current[KEY_PURGE_PENDING] ?: false)) {
                return@withRoomAccess
            }
            try {
                diskCache.clearForUser(userId)
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                dataStore.edit { mutable -> mutable[KEY_PURGE_PENDING] = true }
                throw error
            }
        }
    }

    override suspend fun reconcilePendingPurge() {
        val purgePending = dataStore.data.first()[KEY_PURGE_PENDING] ?: false
        if (!purgePending) return
        sessionCache.clearAndAdvanceGeneration()
        withRoomAccess { reconcilePendingPurgeLocked() }
    }

    private suspend fun reconcilePendingPurgeLocked() {
        val purgePending = dataStore.data.first()[KEY_PURGE_PENDING] ?: false
        if (!purgePending) return
        diskCache.clearAll()
        dataStore.edit { preferences -> preferences[KEY_PURGE_PENDING] = false }
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
