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
    val entries = mutableMapOf<String, DraftEntry>()
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
    var beginFailure: IOException? = null
    var cleanupFailure: IOException? = null
    var beginCalls = 0
        private set
    private var sequence = 0

    fun preload(key: String, draft: Draft) { entries[key] = DraftEntry(draft, 0) }
    override suspend fun currentOwner(): String = "tester"
    override suspend fun load(owner: String?, key: String): Draft? =
        loadEntry(owner, key)?.takeUnless { it.attempt?.status == AttemptStatus.PUBLISHED }?.draft
    override suspend fun loadEntry(owner: String?, key: String): DraftEntry? {
        loadGate?.await()
        return entries[key]
    }
    override suspend fun save(owner: String?, key: String, draft: Draft) {
        saveIfVersion(owner, key, draft, entries[key]?.version ?: 0)
    }
    override suspend fun delete(owner: String?, key: String) {
        deletedKeys += key
        entries.remove(key)
        writes++
    }
    override suspend fun saveIfVersion(owner: String?, key: String, draft: Draft, expectedVersion: Long) =
        saveVersioned(owner, key, draft, expectedVersion, newComposition = false)
    override suspend fun startNewComposition(owner: String?, key: String, draft: Draft, expectedVersion: Long) =
        saveVersioned(owner, key, draft, expectedVersion, newComposition = true)

    private fun saveVersioned(
        owner: String?, key: String, draft: Draft, expected: Long, newComposition: Boolean,
    ): StoreWriteResult {
        val entry = entries[key]
        val attempt = entry?.attempt
        val writable = attempt == null || newComposition && attempt.status == AttemptStatus.PUBLISHED
        return when {
            owner != "tester" -> StoreWriteResult.OwnerChanged
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

    override suspend fun beginAttempt(
        owner: String?, key: String, snapshot: Draft, expectedVersion: Long,
    ): BeginAttemptResult {
        beginCalls++
        beginGate?.await()
        val failure = beginFailure
        val rejection = when {
            failure != null -> StoreWriteResult.Failed(failure)
            owner != "tester" -> StoreWriteResult.OwnerChanged
            (entries[key]?.version ?: 0) != expectedVersion -> StoreWriteResult.Conflict
            entries[key]?.attempt != null -> StoreWriteResult.Conflict
            else -> null
        }
        if (rejection != null) return BeginAttemptResult.Rejected(rejection)
        val token = AttemptToken(checkNotNull(owner), key, "attempt-${++sequence}", expectedVersion + 1)
        entries[key] = DraftEntry(snapshot, token.version, Attempt(token, AttemptStatus.IN_PROGRESS))
        writes++
        return BeginAttemptResult.Started(token)
    }

    override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult {
        val entry = entries[token.key]
        if (entry == null || entry.attempt?.token != token) return StoreWriteResult.Conflict
        val status = when (outcome) {
            AttemptOutcome.CERTAIN_FAILURE -> null
            AttemptOutcome.PUBLISHED -> AttemptStatus.PUBLISHED
            AttemptOutcome.UNCERTAIN -> AttemptStatus.UNCERTAIN
        }
        entries[token.key] = entry.copy(attempt = status?.let { Attempt(token, it) })
        writes++
        return StoreWriteResult.Written(token.version)
    }

    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult {
        val entry = entries[token.key]
        val failure = cleanupFailure
        return when {
            failure != null -> StoreWriteResult.Failed(failure)
            entry == null || entry.attempt != Attempt(token, AttemptStatus.PUBLISHED) -> StoreWriteResult.Conflict
            else -> {
                entries[token.key] = entry.copy(draft = Draft(body = "", isPrivate = entry.draft.isPrivate))
                writes++
                StoreWriteResult.Written(token.version)
            }
        }
    }
}
