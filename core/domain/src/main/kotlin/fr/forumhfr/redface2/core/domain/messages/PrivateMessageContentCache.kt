package fr.forumhfr.redface2.core.domain.messages

import kotlinx.coroutines.flow.Flow

/**
 * Application-wide opt-in for persisting private-message content.
 *
 * No UI consumes this contract in the dormant substrate. The setter exists so the activation layer
 * can later expose one safe operation which persists OFF before invalidating and purging content.
 */
interface PrivateMessageContentCache {
    fun observeEnabled(): Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)
}
