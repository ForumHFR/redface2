package fr.forumhfr.redface2.core.data.cache

import android.util.Log
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.plus

/**
 * Watches the auth session and wipes per-user persisted caches when the session
 * ends or the active pseudo changes.
 *
 * Two reasons drive the wipe:
 *
 * 1. **Privacy** — drapeaux are private. After logout, a freshly opened app must
 *    not surface another account's drapeaux even for one frame.
 * 2. **Correctness** — `flag_topics` is keyed by `userId`, so technically the
 *    rows for the previous account would just sit there ignored. But leaving
 *    them on disk grows the table indefinitely; we wipe `userId = previous`
 *    on transition.
 *
 * On a `Authenticated(A) → Authenticated(B)` switch (login, then logout, then
 * login as someone else), we wipe rows owned by A explicitly. The session
 * cache held in [FlagRepository.clearSessionCache] is also flushed so that the
 * new account does not inherit the previous in-memory results.
 *
 * Topic page caches are *not* per-user — the HTML is the same for every
 * authenticated reader. We do **not** purge `topic_pages`/`posts` on logout to
 * avoid wiping a useful background read cache. The downside (stale per-user
 * fields like `isOwnPost` carried over from the previous session) is mitigated
 * by `CachePolicy.topicPage` (60s TTL) + the `authMode` guard that prevents an
 * anonymous prefetch from clobbering authenticated rows.
 */
@Singleton
class CacheInvalidator @Inject constructor(
    private val authRepository: AuthRepository,
    private val flagDao: FlagDao,
    private val flagRepository: FlagRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val supervisor = SupervisorJob()

    fun start(parent: Job? = null): Job {
        val scope = CoroutineScope(ioDispatcher + supervisor + (parent ?: SupervisorJob()))
        return authRepository.observeAuthState()
            .distinctUntilChanged()
            .scan(InvalidatorState(previous = null)) { state, current -> state.transition(current) }
            .onEach { state ->
                val previousPseudo = state.previousPseudo ?: return@onEach
                if (state.shouldPurge) {
                    runCatching { flagDao.deleteAllForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge flag cache for $previousPseudo", it) }
                    flagRepository.clearSessionCache()
                }
            }
            .launchIn(scope)
    }

    private data class InvalidatorState(
        val previous: AuthState?,
        val previousPseudo: String? = null,
        val shouldPurge: Boolean = false,
    ) {
        fun transition(current: AuthState): InvalidatorState {
            val priorPseudo = (previous as? AuthState.Authenticated)?.pseudo?.lowercase()
            val currentPseudo = (current as? AuthState.Authenticated)?.pseudo?.lowercase()
            val purge = priorPseudo != null && priorPseudo != currentPseudo
            return InvalidatorState(
                previous = current,
                previousPseudo = priorPseudo,
                shouldPurge = purge,
            )
        }
    }

    private companion object {
        const val LOG_TAG = "CacheInvalidator"
    }
}
