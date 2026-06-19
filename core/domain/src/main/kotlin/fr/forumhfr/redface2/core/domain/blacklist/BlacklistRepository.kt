package fr.forumhfr.redface2.core.domain.blacklist

import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import kotlinx.coroutines.flow.Flow

/**
 * Local store of blacklisted (hidden) HFR users — the "local first" half of #509. The blacklist is
 * applied at the UI/MVI layer (post kept in the list, rendered as a collapsed placeholder), never in
 * the parser or cache, so pagination, anchors and `numreponse` keys stay intact.
 *
 * Identity is matched on the **pseudo** (always present on a [fr.forumhfr.redface2.core.model.Post];
 * the profile id is nullable, hence unusable as a key), normalised through [canonicalizePseudo].
 *
 * Designed so a later MPStorage sync becomes an additive adapter, not a rewrite: it is a proper
 * repository over a versioned document, not a throwaway `Set` in a ViewModel.
 */
interface BlacklistRepository {

    /** Blocked users in insertion order, for the management screen. Empty when nothing is blocked. */
    fun observeEntries(): Flow<List<BlacklistEntry>>

    /**
     * Canonical keys of currently blocked users, for cheap membership checks while rendering a topic:
     * `canonicalizePseudo(post.author) in canonicals`.
     *
     * Contract: emits the current set **immediately** on subscription (the empty set when nothing is
     * blocked), then on every change. This lets a consumer `combine` it with another flow to gate the
     * first emission on a known blacklist without ever stalling. The DataStore-backed implementation
     * satisfies this by construction (a Preferences `DataStore` always emits its current value first).
     */
    fun observeBlockedCanonicals(): Flow<Set<String>>

    /** Whether [pseudo] is currently blocked (canonicalised internally). */
    suspend fun isBlocked(pseudo: String): Boolean

    /**
     * Block [pseudo]. No-op if blank or already blocked (canonical match). The first spelling seen is
     * kept as the entry's display name.
     */
    suspend fun block(pseudo: String)

    /**
     * Unblock [pseudo]. Canonicalised internally, so it accepts either a raw author pseudo (post
     * menu) or a stored [BlacklistEntry.canonical] (management screen). No-op if blank or absent.
     */
    suspend fun unblock(pseudo: String)
}
