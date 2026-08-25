package fr.forumhfr.redface2.core.domain.messages

import kotlinx.coroutines.flow.Flow

/**
 * Application-wide opt-in for persisting private-message content.
 *
 * OFF is the effective state while a purge is pending: callers must not read or write Room until
 * the retry has completed.
 */
interface PrivateMessageContentCache {
    fun observeEnabled(): Flow<Boolean>

    fun observePurgePending(): Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    suspend fun retryPendingPurge()
}

/** Lets the settings layer distinguish a rejected preference write from a failed privacy purge. */
sealed class PrivateMessageContentCacheException(cause: Throwable) : Exception(cause) {
    /** The requested persisted state did not change; no destructive operation was started. */
    class PreferenceWriteFailed(cause: Throwable) : PrivateMessageContentCacheException(cause)

    /** The cache is fail-closed and the global purge remains pending until a retry succeeds. */
    class PurgeFailed(cause: Throwable) : PrivateMessageContentCacheException(cause)
}
