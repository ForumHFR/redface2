package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult

/**
 * #1154 — mutates HFR account favorites; callers reconcile from the returned server list.
 * [owner] is the HFR pseudo captured at the gesture (lowercase only, no trim, like local collections).
 * It is checked against the session after serialization and dispatcher waits, before any request.
 */
interface HfrFavoriteSmileyRepository {
    /** Only canonical perso tokens are accepted; invalid input never triggers a request. */
    suspend fun add(owner: String, token: String): HfrFavoriteSmileyMutationResult

    /** Reloads the complete form before writing. An absent token returns NotInFavorites without a POST. */
    suspend fun remove(owner: String, token: String): HfrFavoriteSmileyMutationResult
}
