package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ED-161/170: serialize autosaves and attempts; a stale writer never replaces a durable attempt. */
class EditorDraftSession(private val store: EditorDraftStore, private val key: String?) {
    private val writes = Mutex()
    var owner: String? = null
        private set
    private var version = 0L
    private var published = false
    private var readable = false
    private var unresolved: AttemptToken? = null
    var invalidated = false
        private set

    suspend fun load(): DraftEntry? {
        owner = store.currentOwner()
        val entry = key?.let { store.loadEntry(owner, it) }
        version = entry?.version ?: 0L
        published = entry?.attempt?.status == AttemptStatus.PUBLISHED
        unresolved = entry?.attempt?.token?.takeUnless { published }
        readable = true
        return entry
    }

    fun invalidate() { invalidated = true }

    suspend fun ownsCurrentAccount(): Boolean {
        val current = store.currentOwner()
        return !invalidated && owner != null && current == owner
    }

    suspend fun save(draft: Draft): StoreWriteResult = writes.withLock {
        // Once a storage call can commit, cancellation must not separate its result from adoption.
        withContext(NonCancellable) {
            val result = if (canWrite()) writeComposition(draft) else StoreWriteResult.OwnerChanged
            adopt(result)
            result
        }
    }

    suspend fun begin(draft: Draft, attemptId: String): BeginAttemptResult = writes.withLock {
        if (!canWrite()) return@withLock BeginAttemptResult.Rejected(StoreWriteResult.OwnerChanged)
        val prepared = if (published) writeComposition(draft) else StoreWriteResult.Written(version)
        adopt(prepared)
        if (prepared !is StoreWriteResult.Written) return@withLock BeginAttemptResult.Rejected(prepared)
        store.beginAttempt(owner, checkNotNull(key), draft, version, attemptId).also { result ->
            if (result is BeginAttemptResult.Started) {
                version = result.token.version
                unresolved = result.token
            }
        }
    }

    suspend fun finish(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult = writes.withLock {
        store.finishAttempt(token, outcome).also { result ->
            adopt(result)
            if (result is StoreWriteResult.Written) {
                published = outcome == AttemptOutcome.PUBLISHED
                unresolved = token.takeIf { outcome == AttemptOutcome.UNCERTAIN }
            }
        }
    }

    suspend fun erase(token: AttemptToken): StoreWriteResult = writes.withLock {
        store.erasePublishedContent(token)
    }

    private fun canWrite(): Boolean = readable && !invalidated && key != null

    private suspend fun writeComposition(draft: Draft): StoreWriteResult = try {
        val attempt = unresolved
        when {
            published -> store.startNewComposition(owner, checkNotNull(key), draft, version)
            attempt != null -> store.saveUnresolved(attempt, draft, version)
            else -> store.saveIfVersion(owner, checkNotNull(key), draft, version)
        }
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private fun adopt(result: StoreWriteResult) {
        if (result is StoreWriteResult.Written) {
            version = result.version
            published = false
        }
    }
}
