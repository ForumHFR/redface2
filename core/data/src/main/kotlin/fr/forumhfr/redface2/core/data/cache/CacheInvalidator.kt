package fr.forumhfr.redface2.core.data.cache

import android.util.Log
import fr.forumhfr.redface2.core.data.messages.PrivateMessageThreadSessionCache
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.dao.MpStorageLocationDao
import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
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
 * The same two reasons apply to `mp_read_positions` (#430): the rows only hold page numbers,
 * but they reveal which conversations exist for the previous account, so they are wiped on the
 * same transition.
 *
 * MP editor drafts (`editor_drafts` rows with `isPrivate = 1`, #405) are wiped on the same
 * transition too: an unsent MP draft reveals a recipient and a private message. Public post
 * drafts (`isPrivate = 0`) are NOT purged here — they survive logout and are bounded only by the
 * app-start retention sweep (cf. `DraftRetentionPurger`).
 *
 * Uploaded-image traces (`uploaded_images`, #459) are wiped on the same transition: the rows hold
 * per-account deletion handles and image URLs (private metadata, same contract as
 * `mp_read_positions`), so none may survive the session that produced them.
 *
 * The cached MPStorage location (`mp_storage_locations`, #6/ADR-014) is wiped on the same
 * transition too: the row reveals the account owns a cross-userscript storage MP and at which
 * conversation, so it must not survive the session that discovered it.
 *
 * On a `Authenticated(A) → Authenticated(B)` switch (login, then logout, then
 * login as someone else), we wipe rows owned by A explicitly. The session
 * cache held in [FlagRepository.clearSessionCache] is also flushed so that the
 * new account does not inherit the previous in-memory results. The private-message page cache is
 * cleared first and its generation advanced synchronously, before any suspending database purge.
 *
 * Topic page caches are *not* per-user — the HTML is the same for every
 * authenticated reader. We do **not** purge `topic_pages`/`posts` on logout to
 * avoid wiping a useful background read cache. The downside (stale per-user
 * fields like `isOwnPost` carried over from the previous session) is mitigated
 * by `CachePolicy.topicPage` (60s TTL) + the `authMode` guard that prevents an
 * anonymous prefetch from clobbering authenticated rows.
 */
@Singleton
@Suppress("LongParameterList") // All constructor args are injected per-user caches the invalidator aggregates.
class CacheInvalidator @Inject internal constructor(
    private val authRepository: AuthRepository,
    private val flagDao: FlagDao,
    private val mpReadPositionDao: MpReadPositionDao,
    private val editorDraftDao: EditorDraftDao,
    private val uploadedImageDao: UploadedImageDao,
    private val mpStorageLocationDao: MpStorageLocationDao,
    private val flagRepository: FlagRepository,
    private val privateMessageThreadSessionCache: PrivateMessageThreadSessionCache,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * The earlier shape of [start] was `CoroutineScope(ioDispatcher + supervisor + parent)`
     * where `supervisor = SupervisorJob()` was a field. That field was dead code : in a
     * `CoroutineContext`, both `Job` and `SupervisorJob` share the same `Job.Key`, so the
     * second `Job` (parent) silently replaced `supervisor`. The field is now removed so
     * the scope's parent is always the explicit one — either the caller's `parent` (lets
     * the platform `Application.onCreate` cancel everything on app death) or a fresh
     * `SupervisorJob()` when no parent is provided. Calling [start] twice still creates
     * two independent scopes; if the future requires a long-lived stop button, expose it
     * through a returned handle then.
     */
    fun start(parent: Job? = null): Job {
        val scope = CoroutineScope(ioDispatcher + (parent ?: SupervisorJob()))
        return authRepository.observeAuthState()
            .distinctUntilChanged()
            .scan(InvalidatorState(previous = null)) { state, current -> state.transition(current) }
            .onEach { state ->
                val previousPseudo = state.previousPseudo ?: return@onEach
                if (state.shouldPurge) {
                    // Synchronous and deliberately first: DAO purges suspend. Any MP response that
                    // lands while they run must already carry an obsolete generation and be unable
                    // to refill the RAM cache or reach the UI under the next account (#1080).
                    privateMessageThreadSessionCache.clearAndAdvanceGeneration()
                    runCatching { flagDao.deleteAllForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge flag cache for $previousPseudo", it) }
                    runCatching { mpReadPositionDao.deleteAllForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge MP positions for $previousPseudo", it) }
                    runCatching { editorDraftDao.deletePrivateForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge MP drafts for $previousPseudo", it) }
                    runCatching { uploadedImageDao.deleteAllForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge uploaded images for $previousPseudo", it) }
                    runCatching { mpStorageLocationDao.deleteAllForUser(previousPseudo) }
                        .onFailure { Log.w(LOG_TAG, "Failed to purge MPStorage location for $previousPseudo", it) }
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
