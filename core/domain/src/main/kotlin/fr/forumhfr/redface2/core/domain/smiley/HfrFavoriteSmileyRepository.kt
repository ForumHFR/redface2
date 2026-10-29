package fr.forumhfr.redface2.core.domain.smiley

import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult

/** #1154 — mutates HFR account favorites; callers reconcile from the returned server list. */
interface HfrFavoriteSmileyRepository {
    /** Only canonical perso tokens are accepted; invalid input never triggers a request. */
    suspend fun add(token: String): HfrFavoriteSmileyMutationResult

    /** Reloads the complete form before writing. An already absent token succeeds without a POST. */
    suspend fun remove(token: String): HfrFavoriteSmileyMutationResult
}
