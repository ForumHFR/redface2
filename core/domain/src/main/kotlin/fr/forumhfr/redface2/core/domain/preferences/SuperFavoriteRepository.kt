package fr.forumhfr.redface2.core.domain.preferences

import fr.forumhfr.redface2.core.model.Flag
import kotlinx.coroutines.flow.Flow

/**
 * Local « super favori » store (#603, ADR-017 decision 5). A super favorite is a purely CLIENT-SIDE
 * mark on a topic — a personal pin, distinct from the server `isFavorite`/`flag_owntopic`
 * decoration (there is no server equivalent).
 *
 * Deliberately a SEPARATE repository (not a method on [UserPreferencesRepository]) so it carries its
 * own responsibility and does not force every existing preferences test-double to grow a method.
 */
interface SuperFavoriteRepository {

    /**
     * Emits the current set of super-favorited topics; starts with the persisted value.
     *
     * New entries are keyed by `(cat, topicId)` and carry a title/subcategory snapshot so the Super
     * tab can still render a topic after the HFR flag disappeared. Legacy entries migrated from the
     * former topic-id-only store keep `cat == null`; they are displayed as orphans unless a warm flag
     * cache can resolve their missing category/title.
     */
    fun observeSuperFavoriteTopics(): Flow<Set<SuperFavoriteTopic>>

    /** Adds ([enabled] = true) or removes [flag] from the super-favorite set. Idempotent. */
    suspend fun setSuperFavorite(flag: Flag, enabled: Boolean)

    /** Atomically flips [flag]'s local super-favorite mark against the persisted current value. */
    suspend fun toggleSuperFavorite(flag: Flag)
}

data class SuperFavoriteTopic(
    val cat: Int?,
    val topicId: Int,
    val title: String?,
    val subcat: Int?,
)

fun SuperFavoriteTopic.matches(flag: Flag): Boolean =
    topicId == flag.topicId && (cat == null || cat == flag.cat)
