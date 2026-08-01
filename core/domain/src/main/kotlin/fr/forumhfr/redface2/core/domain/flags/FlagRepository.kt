package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the user's drapeaux. Phase 1D-1 (#110) ships a REST per-category
 * implementation that walks `forums/hardwarefr/categories/{cat}/topics/{bucket}/`
 * and concatenates the results — no Room persistence yet (deferred to #26). A
 * `lastReadPage` ack mutation endpoint will be added once the user-facing
 * remove/swipe interactions are designed; for now, drapeau mutations stay HTML
 * (`addflag.php` / `delflag.php`) per ADR-003.
 *
 * Flow semantics:
 * - [observe] fetches on the first subscription for a tab, then reuses the last success
 *   from the current auth session so tab switches do not implicitly mutate HFR read state.
 * - [refresh] always fetches the network, updates the in-memory success cache, and
 *   broadcasts the result to active observers.
 * - [clearSessionCache] must run when the auth session ends or changes user; cached
 *   drapeaux are user-private.
 * - On a fetch error, the [Result] in [FlagsResult.Failure] carries the cause; the flow
 *   does not retry on its own.
 */
interface FlagRepository {

    /**
     * Observe the user's drapeaux for the given [type]. Emits a cached success when
     * available for the current auth session; otherwise emits [FlagsResult.Loading] then
     * the initial fetch result. Subsequent emissions are produced by explicit [refresh]
     * calls.
     */
    fun observe(type: FlagType): Flow<FlagsResult>

    /**
     * Force a fresh network fetch, update the session cache, and broadcast it to current
     * observers. Active observers receive [FlagsResult.Loading] before the fresh result.
     */
    suspend fun refresh(type: FlagType)

    /** Drop all per-tab in-memory results tied to the current auth session. */
    fun clearSessionCache()

    /**
     * Remove a single drapeau (#99). Per ADR-003 the mutation stays HTML : a GET on
     * `/user/delflag.php` keyed on the flag's `(cat, subcat, topicId, owntopic, page)`
     * tuple, classified by the « Drapeau effacé avec succès » response sentence (the REST
     * layer only reads drapeaux, it does not delete them).
     *
     * On **success** the implementation drops the row from both its in-memory and Room
     * caches (logical key `cat + topicId + type`) and re-broadcasts the updated list to
     * active observers of [flag]'s [Flag.type], so the screen updates immediately without
     * a refetch. On **failure** (already removed, refused, or an unexpected page) **no**
     * cache is touched and the [Result] carries the cause. A session-expired GET surfaces
     * as a failed [Result] wrapping the underlying exception, like the read path.
     *
     * Returns [Result.success] with [Unit] on a confirmed deletion, [Result.failure]
     * otherwise — including transport / session-expiry errors.
     */
    suspend fun removeFlag(flag: Flag): Result<Unit>

    /**
     * Resolve the full [Flag] for a `(cat, topicId)` pair (#809), for surfaces OUTSIDE the Drapeaux
     * view that hold a topic identity but not the loaded [Flag] — e.g. the topic screen's long-press
     * « Retirer le drapeau ». [removeFlag] needs the complete object (its `subcat` / `type` /
     * `lastReadPage` form the `delflag.php` key), so a partial [Flag] must never be fabricated : this
     * lookup returns the real cached/fetched row or nothing.
     *
     * Resolution order (per-bucket) :
     * - Scans the in-memory per-type success caches first ; a hit returns without any network.
     * - A warm bucket is authoritative **for its own type only** and is never implicitly refreshed
     *   (the Drapeaux view owns refresh policy). On a miss, the **cold** buckets — and only those —
     *   are fetched so the lookup can still resolve a type whose tab was never opened ; then a
     *   re-scan decides. All three warm + miss → null with zero network.
     * - An anonymous session (no HFR user) can hold no drapeaux : null with no round-trip.
     *
     * Returns the matching [Flag], or null when the topic is not flagged / unresolvable / anonymous.
     */
    suspend fun findFlag(cat: Int, topicId: Int): Flag?
}

/**
 * Tri-state outcome of a flags fetch. Consumers (FlagsViewModel) map this into the
 * Compose UiState; keeping the domain layer free of Compose types makes it testable
 * without a Looper.
 */
sealed class FlagsResult {
    data object Loading : FlagsResult()
    data class Success(val flags: List<Flag>) : FlagsResult()
    data class Failure(val cause: Throwable) : FlagsResult()
}
