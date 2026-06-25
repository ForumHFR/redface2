package fr.forumhfr.redface2.core.domain.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Local « super favori » store (#603, ADR-017 decision 5). A super favorite is a purely CLIENT-SIDE
 * mark on a topic — a personal pin, distinct from [UserPreferencesRepository]-less of the server
 * `isFavorite`/`flag_owntopic` decoration (there is no server equivalent). Persisted as a set of
 * topic ids.
 *
 * Deliberately a SEPARATE repository (not a method on [UserPreferencesRepository]) so it carries its
 * own responsibility and does not force every existing preferences test-double to grow a method.
 */
interface SuperFavoriteRepository {

    /** Emits the current set of super-favorited topic ids; starts with the persisted value. */
    fun observeSuperFavoriteTopicIds(): Flow<Set<Int>>

    /** Adds ([enabled] = true) or removes the topic from the super-favorite set. Idempotent. */
    suspend fun setSuperFavorite(topicId: Int, enabled: Boolean)
}
