package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the user's drapeaux. Phase 1B.4 ships a network-only implementation
 * (no Room persistence yet); a `lastReadPage` ack endpoint will be added in Phase 1B.5+
 * once the user-facing remove/swipe interactions are designed.
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
