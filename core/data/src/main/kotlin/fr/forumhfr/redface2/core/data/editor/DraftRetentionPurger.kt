package fr.forumhfr.redface2.core.data.editor

import android.util.Log
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot retention sweep of the `editor_drafts` table run at app start (#405): drops every draft
 * (public or MP) last touched more than [RETENTION] ago, so an abandoned draft cannot linger on
 * disk indefinitely. Independent of the auth state — unlike the logout purge owned by
 * `CacheInvalidator` — hence its own component rather than a branch inside the auth-driven
 * invalidator.
 *
 * The cutoff is sourced from an injectable [Clock] so the sweep is testable, and the DAO call
 * runs on the IO dispatcher off the main thread. Nothing about draft content is ever logged.
 */
@Singleton
class DraftRetentionPurger @Inject constructor(
    private val editorDraftDao: EditorDraftDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Launches the retention sweep on a fresh IO scope (parented on [parent] when provided, so the
     * platform `Application` can cancel it on process death). Best-effort: a DAO failure is logged
     * without crashing app start. Returns the launched [Job].
     */
    fun purge(parent: Job? = null): Job {
        val scope = CoroutineScope(ioDispatcher + (parent ?: SupervisorJob()))
        return scope.launch {
            val cutoff = clock.millis() - RETENTION.toMillis()
            runCatching { editorDraftDao.deleteOlderThan(cutoff) }
                .onFailure { Log.w(LOG_TAG, "Failed to purge stale editor drafts", it) }
        }
    }

    private companion object {
        const val LOG_TAG = "DraftRetentionPurger"
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
