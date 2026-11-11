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
    private var writingVersion: Long? = null
    /** ED-81: includes a CAS committed on the store dispatcher whose result is still in flight. */
    val nextContentVersion: Long get() = (writingVersion ?: version) + 1
    /** Optional surface checkpoint; null result announces the revision before the first suspension. */
    var checkpointObserver: ((Draft, Long, StoreWriteResult?) -> Unit)? = null
    private var published = false
    private var readable = false
    private var unresolved: AttemptToken? = null
    var pendingInlineQuoteRequest: PendingInlineQuoteRequest? = null
        private set
    var hasUnreadableInlineQuoteRequest = false
        private set
    private var unreadableVersion: Long? = null
    var invalidated = false
        private set

    suspend fun load(): DraftEntry? {
        readable = false
        hasUnreadableInlineQuoteRequest = false
        unreadableVersion = null
        val entry = try {
            owner = store.currentOwner()
            key?.let { store.loadEntry(owner, it) }
        } catch (error: UnreadableInlineQuoteRequestException) {
            // Only an unreadable inline request seals the session until a reload.
            readable = false
            hasUnreadableInlineQuoteRequest = true
            unreadableVersion = error.contentVersion
            throw error
        } catch (error: IOException) {
            // Preserve the ordinary best-effort restore contract. Versioned writes still guard the row.
            readable = true
            throw error
        }
        version = entry?.version ?: 0L
        published = entry?.attempt?.status == AttemptStatus.PUBLISHED
        unresolved = entry?.attempt?.token?.takeUnless { published }
        pendingInlineQuoteRequest = entry?.draft?.pendingInlineQuoteRequest.takeUnless { published }
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
            val result = if (canWrite()) checkpointWrite(ownedDraft(draft)) {
                writeComposition(ownedDraft(draft))
            } else StoreWriteResult.OwnerChanged
            adopt(result)
            result
        }
    }

    /** A new user action only; loading/restoring a surface must never enqueue its launch snapshot. */
    suspend fun enqueueInlineQuoteRequest(request: PendingInlineQuoteRequest): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val result = mutation {
                store.enqueueInlineQuoteRequest(owner, checkNotNull(key), request, version)
            }
            if (result is StoreWriteResult.Written) {
                pendingInlineQuoteRequest = pendingInlineQuoteRequest?.merge(request) ?: request
            }
            adopt(result)
            result
        }
    }

    /**
     * Await delivery ([draft] supplied) or ignore (no draft). The exact request guards GET callbacks
     * that finish after a newer quote action. A dormant request can only be ignored, not delivered.
     */
    suspend fun consumeInlineQuoteRequest(
        request: PendingInlineQuoteRequest, draft: Draft? = null,
    ): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val result = if (pendingInlineQuoteRequest != request || (draft != null && unresolved != null)) {
                StoreWriteResult.Conflict
            } else {
                checkpointWrite(draft) {
                    mutation { store.consumeInlineQuoteRequest(owner, checkNotNull(key), request, version, draft) }
                }
            }
            if (result is StoreWriteResult.Written) pendingInlineQuoteRequest = null
            adopt(result)
            result
        }
    }

    /** The raw row stays sealed until this explicit abandonment succeeds and the binding reloads. */
    @Suppress("ComplexCondition") // One guard: sealed flag, live binding, key, and the version found unreadable.
    suspend fun discardUnreadableInlineQuoteRequest(): StoreWriteResult = writes.withLock {
        withContext(NonCancellable) {
            val expectedVersion = unreadableVersion
            if (!hasUnreadableInlineQuoteRequest || invalidated || key == null || expectedVersion == null) {
                StoreWriteResult.Conflict
            } else {
                try {
                    store.discardUnreadableInlineQuoteRequest(owner, key, expectedVersion)
                } catch (error: IOException) {
                    StoreWriteResult.Failed(error)
                }
            }
        }
    }

    suspend fun begin(draft: Draft, attemptId: String): BeginAttemptResult = writes.withLock {
        if (!canWrite()) return@withLock BeginAttemptResult.Rejected(StoreWriteResult.OwnerChanged)
        val snapshot = ownedDraft(draft)
        val prepared = if (published) writeComposition(snapshot) else StoreWriteResult.Written(version)
        adopt(prepared)
        if (prepared !is StoreWriteResult.Written) return@withLock BeginAttemptResult.Rejected(prepared)
        store.beginAttempt(owner, checkNotNull(key), snapshot, version, attemptId).also { result ->
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
                if (published) pendingInlineQuoteRequest = null
            }
        }
    }

    suspend fun erase(token: AttemptToken): StoreWriteResult = writes.withLock {
        store.erasePublishedContent(token)
    }

    private fun canWrite(): Boolean = readable && !invalidated && key != null

    private suspend fun checkpointWrite(draft: Draft?, write: suspend () -> StoreWriteResult): StoreWriteResult {
        val target = version + 1
        writingVersion = target
        if (draft != null) checkpointObserver?.invoke(draft, target, null)
        return try {
            write().also { result ->
                if (draft != null) checkpointObserver?.invoke(draft, target, result)
            }
        } finally {
            writingVersion = null
        }
    }

    private fun ownedDraft(draft: Draft): Draft = draft.copy(pendingInlineQuoteRequest = pendingInlineQuoteRequest)

    private suspend fun mutation(operation: suspend () -> StoreWriteResult): StoreWriteResult = try {
        if (canWrite()) operation() else StoreWriteResult.OwnerChanged
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

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
