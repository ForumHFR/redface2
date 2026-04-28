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
 * - Emits the freshly-fetched list on each [refresh] / [observe] subscription.
 * - Re-emits when the auth state flips to authenticated (a logged-out user has no
 *   drapeaux to show; the [observe] flow is expected to be combined with `AuthState`
 *   upstream by the ViewModel layer).
 * - On a fetch error, the [Result] in [FlagsResult.Failure] carries the cause; the flow
 *   does not retry on its own.
 */
interface FlagRepository {

    /**
     * Observe the user's drapeaux for the given [type]. The first emission is the result
     * of an initial fetch (success or failure); subsequent emissions are produced by
     * explicit [refresh] calls.
     */
    fun observe(type: FlagType): Flow<FlagsResult>

    /**
     * Force a fresh network fetch and broadcast it to current observers. No-op when no
     * one is observing — the caller wouldn't see the result anyway.
     */
    suspend fun refresh(type: FlagType)
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
