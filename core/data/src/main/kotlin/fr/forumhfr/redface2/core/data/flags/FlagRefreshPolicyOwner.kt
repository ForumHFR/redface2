package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.network.FlagReadLimiter
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes policy changes with fetch admission, without owning or discarding cached data. */
internal class FlagRefreshPolicyOwner(
    private val preferences: UserPreferencesRepository,
    private val limiter: FlagReadLimiter,
    private val diagnostics: FlagRefreshDiagnostics,
    private val retire: suspend () -> List<Job>,
) {
    private val admission = Mutex()
    @Volatile var current = FlagsRefreshPolicy()
        private set

    suspend fun synchronize() { change(preferences.observeFlagsRefreshPolicy().first()) }
    suspend fun observe() { preferences.observeFlagsRefreshPolicy().collect { change(it) } }

    suspend fun <T> withPolicy(block: suspend (FlagsRefreshPolicy) -> T): T =
        admission.withLock { block(current) }

    private suspend fun change(next: FlagsRefreshPolicy) = admission.withLock {
        if (next == current) return@withLock
        val obsolete = retire()
        withContext(NonCancellable) {
            obsolete.forEach { it.join() }
            limiter.configure(next)
            current = next
            diagnostics.policy = next
        }
    }
}
