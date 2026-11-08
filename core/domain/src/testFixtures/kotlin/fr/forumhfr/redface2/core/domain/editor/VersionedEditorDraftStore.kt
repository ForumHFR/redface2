package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/** ED-170: in-memory CAS fake, with observable durable boundaries; no feature or Room dependency. */
open class VersionedEditorDraftStore : EditorDraftStore {
    var activeOwner: String? = "tester"
    private val ownerEntries = mutableMapOf<String?, MutableMap<String, DraftEntry>>()
    val entries: MutableMap<String, DraftEntry> get() = ownerEntries.getOrPut(activeOwner) { mutableMapOf() }
    val saved: Map<String, Draft> get() = entries.mapValues { it.value.draft }
    val deletedKeys = mutableListOf<String>()
    var saveCount = 0
        private set
    var writes = 0
        private set
    var lastSavedOwner: String? = null
        private set
    var loadGate: CompletableDeferred<Unit>? = null
    var beginGate: CompletableDeferred<Unit>? = null
    var beginReturnGate: CompletableDeferred<Unit>? = null
    var saveReturnGate: CompletableDeferred<Unit>? = null
    var finishGate: CompletableDeferred<Unit>? = null
    var cleanupGate: CompletableDeferred<Unit>? = null
    var loadFailure: IOException? = null
    var saveFailure: IOException? = null
    var beginFailure: IOException? = null
    var cleanupFailure: IOException? = null
    var finishFailure: IOException? = null
    val attemptedIds = mutableListOf<String>()
    var beginCalls = 0
        private set
    var finishCalls = 0
        private set

    fun preload(key: String, draft: Draft) { entries[key] = DraftEntry(draft, 0) }

    /** Mirrors Room's account-switch purge: only durable attempts survive, with no private content. */
    fun purgePrivateForUser(owner: String) {
        val owned = ownerEntries[owner.lowercase()] ?: return
        for ((key, entry) in owned.toMap()) {
            if (entry.draft.isPrivate) {
                if (entry.attempt == null) {
                    owned.remove(key)
                    deletedKeys += key
                } else {
                    owned[key] = entry.copy(draft = entry.draft.copy(
                        body = "", subject = null, recipients = null, pendingInlineQuoteRequest = null,
                    ))
                }
                writes++
            }
        }
    }

    override suspend fun currentOwner(): String? = activeOwner
    override suspend fun load(owner: String?, key: String): Draft? =
        loadEntry(owner, key)?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }?.draft
    override suspend fun loadEntry(owner: String?, key: String): DraftEntry? {
        loadFailure?.let { throw it }
        loadGate?.await()
        return entries[key].takeIf { owner != null && owner == activeOwner }
    }
    override suspend fun save(owner: String?, key: String, draft: Draft) {
        val entry = entries[key]
        saveIfVersion(owner, key, draft.copy(pendingInlineQuoteRequest = entry?.draft?.pendingInlineQuoteRequest),
            entry?.version ?: 0)
    }
    override suspend fun delete(owner: String?, key: String) {
        if (owner != null && owner == activeOwner && entries[key]?.attempt == null) {
            deletedKeys += key
            entries.remove(key)
            writes++
        }
    }
    override suspend fun saveIfVersion(
        owner: String?, key: String, draft: Draft, expectedVersion: Long,
    ): StoreWriteResult {
        val result = saveVersioned(owner, key, draft, expectedVersion, newComposition = false)
        saveReturnGate?.await() // The transaction committed, but its dispatcher has not delivered the result.
        return result
    }
    override suspend fun startNewComposition(owner: String?, key: String, draft: Draft, expectedVersion: Long) =
        saveVersioned(owner, key, draft, expectedVersion, newComposition = true)

    override suspend fun enqueueInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult {
        val entry = entries[key]
        val rejection = quoteMutationRejection(owner, key, request, expectedVersion)
        if (rejection != null) return rejection
        val composition = entry?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }
        val pending = composition?.draft?.pendingInlineQuoteRequest
        val draft = (composition?.draft ?: Draft(body = "")).copy(
            pendingInlineQuoteRequest = pending?.merge(request) ?: request,
        )
        entries[key] = DraftEntry(draft, expectedVersion + 1, composition?.attempt)
        writes++
        saveReturnGate?.await()
        return StoreWriteResult.Written(expectedVersion + 1)
    }

    override suspend fun consumeInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long, draft: Draft?,
    ): StoreWriteResult {
        val rejection = quoteMutationRejection(owner, key, request, expectedVersion)
        if (rejection != null) return rejection
        return entries[key]?.let { entry ->
            val deliveryBlocked = draft != null && entry.attempt != null
            if (entry.draft.pendingInlineQuoteRequest != request || entry.attempt?.status == AttemptStatus.PUBLISHED ||
                deliveryBlocked
            ) {
                StoreWriteResult.Conflict
            } else {
                entries[key] = entry.copy(
                    draft = (draft ?: entry.draft).copy(pendingInlineQuoteRequest = null),
                    version = expectedVersion + 1,
                )
                writes++
                saveReturnGate?.await()
                StoreWriteResult.Written(expectedVersion + 1)
            }
        } ?: StoreWriteResult.Conflict
    }

    private fun quoteMutationRejection(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult? = when {
        owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
        saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
        request.key != key || (entries[key]?.version ?: 0L) != expectedVersion -> StoreWriteResult.Conflict
        else -> null
    }

    override suspend fun saveUnresolved(token: AttemptToken, draft: Draft, expectedVersion: Long): StoreWriteResult {
        val entry = entries[token.key]
        val result = when {
            saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
            token.owner != activeOwner -> StoreWriteResult.OwnerChanged
            entry?.version != expectedVersion || !entry.hasUnresolvedAttempt(token) -> StoreWriteResult.Conflict
            else -> {
                entries[token.key] = DraftEntry(draft, expectedVersion + 1, Attempt(token, AttemptStatus.UNCERTAIN))
                saveCount++
                writes++
                lastSavedOwner = token.owner
                StoreWriteResult.Written(expectedVersion + 1)
            }
        }
        saveReturnGate?.await()
        return result
    }

    private fun saveVersioned(
        owner: String?, key: String, draft: Draft, expected: Long, newComposition: Boolean,
    ): StoreWriteResult {
        val entry = entries[key]
        val attempt = entry?.attempt
        val writable = attempt == null || newComposition && attempt.status == AttemptStatus.PUBLISHED
        return when {
            saveFailure != null -> StoreWriteResult.Failed(checkNotNull(saveFailure))
            owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
            (entry?.version ?: 0) != expected || !writable -> StoreWriteResult.Conflict
            else -> {
                entries[key] = DraftEntry(draft, expected + 1)
                saveCount++
                writes++
                lastSavedOwner = owner
                StoreWriteResult.Written(expected + 1)
            }
        }
    }

    override suspend fun discardUnreadableInlineQuoteRequest(owner: String?, key: String): StoreWriteResult {
        if (owner == null || owner != activeOwner) return StoreWriteResult.OwnerChanged
        saveFailure?.let { return StoreWriteResult.Failed(it) }
        val entry = entries[key] ?: return StoreWriteResult.Conflict
        entries[key] = entry.copy(
            draft = entry.draft.copy(pendingInlineQuoteRequest = null), version = entry.version + 1,
        )
        loadFailure = null
        writes++
        return StoreWriteResult.Written(entry.version + 1)
    }

    override suspend fun beginAttempt(
        owner: String?, key: String, snapshot: Draft, expectedVersion: Long, attemptId: String,
    ): BeginAttemptResult {
        beginCalls++
        attemptedIds += attemptId
        beginGate?.await()
        val failure = beginFailure
        val rejection = when {
            failure != null -> StoreWriteResult.Failed(failure)
            owner == null || owner != activeOwner -> StoreWriteResult.OwnerChanged
            (entries[key]?.version ?: 0) != expectedVersion -> StoreWriteResult.Conflict
            entries[key]?.attempt != null -> StoreWriteResult.Conflict
            else -> null
        }
        if (rejection != null) return BeginAttemptResult.Rejected(rejection)
        val token = AttemptToken(checkNotNull(owner), key, attemptId, expectedVersion + 1)
        entries[key] = DraftEntry(snapshot, token.version, Attempt(token, AttemptStatus.IN_PROGRESS))
        writes++
        beginReturnGate?.await() // Owner was checked and the transaction committed before this suspension.
        return BeginAttemptResult.Started(token)
    }

    override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
        finishCalls++
        finishGate?.await()
        finishFailure?.let { return StoreWriteResult.Failed(it) }
        return if (token.owner != activeOwner && outcome != AttemptOutcome.CERTAIN_FAILURE) {
            StoreWriteResult.OwnerChanged
        } else {
            finishOwnedAttempt(token, outcome)
        }
    }

    private fun finishOwnedAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
        val owned = ownerEntries[token.owner]
        val entry = owned?.get(token.key)
        if (owned == null || entry == null || !entry.hasUnresolvedAttempt(token)) {
            return StoreWriteResult.Conflict
        }
        val status = when (outcome) {
            AttemptOutcome.CERTAIN_FAILURE -> null
            AttemptOutcome.PUBLISHED -> AttemptStatus.PUBLISHED
            AttemptOutcome.UNCERTAIN -> AttemptStatus.UNCERTAIN
        }
        owned[token.key] = entry.copy(
            attempt = status?.let { Attempt(token, it) },
            draft = if (status == AttemptStatus.PUBLISHED) entry.draft.copy(pendingInlineQuoteRequest = null)
            else entry.draft,
        )
        writes++
        return StoreWriteResult.Written(entry.version)
    }

    private fun DraftEntry?.hasUnresolvedAttempt(token: AttemptToken): Boolean =
        this?.attempt?.let { it.token == token && it.status != AttemptStatus.PUBLISHED } == true

    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult {
        cleanupGate?.await()
        if (token.owner != activeOwner) return StoreWriteResult.OwnerChanged
        val entry = entries[token.key]
        val failure = cleanupFailure
        return when {
            failure != null -> StoreWriteResult.Failed(failure)
            entry == null || entry.version != token.version ||
                entry.attempt != Attempt(token, AttemptStatus.PUBLISHED) -> StoreWriteResult.Conflict
            else -> {
                entries[token.key] = entry.copy(draft = Draft(body = "", isPrivate = entry.draft.isPrivate))
                writes++
                StoreWriteResult.Written(token.version)
            }
        }
    }
}
