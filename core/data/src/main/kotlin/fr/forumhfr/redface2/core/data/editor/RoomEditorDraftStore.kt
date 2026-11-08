package fr.forumhfr.redface2.core.data.editor

import android.database.sqlite.SQLiteException
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult
import fr.forumhfr.redface2.core.domain.editor.PendingInlineQuoteRequest
import fr.forumhfr.redface2.core.domain.editor.UnreadableInlineQuoteRequestException
import fr.forumhfr.redface2.core.model.AuthState
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Account-bound draft storage. ED-161/170 writes use Room transactions/CAS and explicit results;
 * published tombstones survive cleanup. Legacy methods remain for editors awaiting migration.
 * No draft content is logged; private rows still participate in the account-switch purge.
 */
@Singleton
class RoomEditorDraftStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val editorDraftDao: EditorDraftDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EditorDraftStore {
    // Lock order: EditorDraftSession -> contentWrites -> Room. Purge takes only contentWrites;
    // nothing under this lock calls a session or performs a network request.
    private val contentWrites = Mutex()
    // Immutable snapshots allow capture before dispatch; increments stay under contentWrites.
    @Volatile private var purgeGenerations: Map<String, Long> = emptyMap()

    override suspend fun currentOwner(): String? = withContext(ioDispatcher) { activeOwnerId() }

    override suspend fun load(owner: String?, key: String): Draft? =
        loadEntry(owner, key)?.takeIf { it.attempt?.status != AttemptStatus.PUBLISHED }?.draft

    override suspend fun loadEntry(owner: String?, key: String): DraftEntry? = withContext(ioDispatcher) {
        if (owner == null || owner != activeOwnerId()) return@withContext null
        editorDraftDao.get(rowKey(owner, key))?.let { entity ->
            val attempt = entity.attemptId?.let { id ->
                Attempt(
                    AttemptToken(owner, key, id, checkNotNull(entity.attemptVersion)),
                    AttemptStatus.valueOf(checkNotNull(entity.attemptState)),
                )
            }
            val draft = try {
                entity.toDraft(key)
            } catch (error: UnreadableInlineQuoteRequestException) {
                throw UnreadableInlineQuoteRequestException(entity.contentVersion, error)
            }
            DraftEntry(draft, entity.contentVersion, attempt)
        }
    }

    override suspend fun save(owner: String?, key: String, draft: Draft) {
        ownedWrite(owner, Unit) {
            editorDraftDao.saveLegacy(draft.toEntity(checkNotNull(owner), key))
        }
    }

    /** Account-switch cleanup shares the writers' exclusion, including absent-row inserts. */
    suspend fun purgePrivateForUser(owner: String) = withContext(ioDispatcher) {
        val normalizedOwner = owner.lowercase()
        contentWrites.withLock {
            purgeGenerations = purgeGenerations + (normalizedOwner to (purgeGeneration(normalizedOwner) + 1))
            editorDraftDao.deletePrivateForUser(normalizedOwner)
        }
    }

    override suspend fun delete(owner: String?, key: String) {
        withContext(ioDispatcher) {
            if (owner != null && owner == activeOwnerId()) {
                try {
                    editorDraftDao.deleteByKey(rowKey(owner, key))
                } catch (_: SQLiteException) {
                    // Legacy editors retain their best-effort cleanup until step 3.
                } catch (_: IOException) {
                    // The versioned erasePublishedContent path reports failures to the controller.
                }
            }
        }
    }

    override suspend fun saveIfVersion(
        owner: String?,
        key: String,
        draft: Draft,
        expectedVersion: Long,
    ): StoreWriteResult = write(owner) {
        val next = draft.toEntity(checkNotNull(owner), key).copy(contentVersion = expectedVersion + 1)
        editorDraftDao.saveVersioned(next, expectedVersion, newComposition = false).result(next.contentVersion)
    }

    override suspend fun startNewComposition(
        owner: String?,
        key: String,
        draft: Draft,
        expectedVersion: Long,
    ): StoreWriteResult = write(owner) {
        val next = draft.toEntity(checkNotNull(owner), key).copy(contentVersion = expectedVersion + 1)
        editorDraftDao.saveVersioned(next, expectedVersion, newComposition = true).result(next.contentVersion)
    }

    override suspend fun saveUnresolved(token: AttemptToken, draft: Draft, expectedVersion: Long): StoreWriteResult =
        write(token.owner) {
            val next = draft.toEntity(token.owner, token.key).copy(
                contentVersion = expectedVersion + 1,
                attemptId = token.id,
                attemptVersion = token.version,
                attemptState = AttemptStatus.UNCERTAIN.name,
            )
            editorDraftDao.saveUnresolved(next, expectedVersion).result(next.contentVersion)
        }

    override suspend fun enqueueInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long,
    ): StoreWriteResult = write(owner) {
        if (request.key != key) return@write StoreWriteResult.Conflict
        val previous = editorDraftDao.get(rowKey(checkNotNull(owner), key))
        if ((previous?.contentVersion ?: 0L) != expectedVersion) return@write StoreWriteResult.Conflict
        val composition = if (previous == null || previous.attemptState == AttemptStatus.PUBLISHED.name) {
            Draft(body = "").toEntity(owner, key)
        } else {
            previous
        }
        val pending = composition.pendingInlineQuoteRequest?.let { PendingInlineQuoteRequestCodec.decode(it, key) }
        val next = composition.copy(
            pendingInlineQuoteRequest = PendingInlineQuoteRequestCodec.encode(pending?.merge(request) ?: request, key),
            contentVersion = expectedVersion + 1,
            updatedAt = clock.millis(),
        )
        editorDraftDao.mutateInlineQuoteRequest(next, previous).result(next.contentVersion)
    }

    override suspend fun consumeInlineQuoteRequest(
        owner: String?, key: String, request: PendingInlineQuoteRequest, expectedVersion: Long, draft: Draft?,
    ): StoreWriteResult = write(owner) {
        if (request.key != key) return@write StoreWriteResult.Conflict
        val previous = editorDraftDao.get(rowKey(checkNotNull(owner), key)) ?: return@write StoreWriteResult.Conflict
        val deliveryBlocked = draft != null && previous.attemptState != null
        if (previous.contentVersion != expectedVersion || previous.attemptState == AttemptStatus.PUBLISHED.name ||
            deliveryBlocked
        ) return@write StoreWriteResult.Conflict
        val pending = previous.pendingInlineQuoteRequest?.let { PendingInlineQuoteRequestCodec.decode(it, key) }
        if (pending != request) return@write StoreWriteResult.Conflict
        val content = draft?.copy(pendingInlineQuoteRequest = null)?.toEntity(owner, key) ?: previous
        val next = content.copy(
            pendingInlineQuoteRequest = null,
            contentVersion = expectedVersion + 1,
            updatedAt = clock.millis(),
        )
        editorDraftDao.mutateInlineQuoteRequest(next, previous).result(next.contentVersion)
    }

    override suspend fun discardUnreadableInlineQuoteRequest(
        owner: String?, key: String, expectedVersion: Long,
    ): StoreWriteResult =
        write(owner) {
            val previous = editorDraftDao.get(rowKey(checkNotNull(owner), key))
                ?: return@write StoreWriteResult.Conflict
            if (previous.contentVersion != expectedVersion) return@write StoreWriteResult.Conflict
            // No decoding: preserve even unknown payloads until the user explicitly discards them.
            val next = previous.copy(
                pendingInlineQuoteRequest = null,
                contentVersion = previous.contentVersion + 1,
                updatedAt = clock.millis(),
            )
            editorDraftDao.mutateInlineQuoteRequest(next, previous).result(next.contentVersion)
        }

    override suspend fun beginAttempt(
        owner: String?,
        key: String,
        snapshot: Draft,
        expectedVersion: Long,
        attemptId: String,
    ): BeginAttemptResult {
        val token = AttemptToken(owner.orEmpty(), key, attemptId, expectedVersion + 1)
        val result = write(owner) {
            val next = snapshot.toEntity(checkNotNull(owner), key).copy(
                contentVersion = token.version,
                attemptId = token.id,
                attemptVersion = token.version,
                attemptState = AttemptStatus.IN_PROGRESS.name,
            )
            editorDraftDao.beginAttempt(next, expectedVersion).result(token.version)
        }
        return if (result is StoreWriteResult.Written) {
            BeginAttemptResult.Started(token)
        } else {
            BeginAttemptResult.Rejected(result)
        }
    }

    override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult =
        write(
            token.owner,
            requireActiveOwner = outcome != AttemptOutcome.CERTAIN_FAILURE,
            writesContent = false,
        ) {
            val status = when (outcome) {
                AttemptOutcome.CERTAIN_FAILURE -> null
                AttemptOutcome.PUBLISHED -> AttemptStatus.PUBLISHED.name
                AttemptOutcome.UNCERTAIN -> AttemptStatus.UNCERTAIN.name
            }
            editorDraftDao.finishVersioned(
                rowKey(token.owner, token.key), token.id, token.version, status, clock.millis(),
            )?.let { StoreWriteResult.Written(it) } ?: StoreWriteResult.Conflict
        }

    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult =
        write(token.owner, writesContent = false) {
            val erased = editorDraftDao.erasePublishedContent(
                rowKey(token.owner, token.key), token.id, token.version, clock.millis(),
            )
            (erased == 1).result(token.version)
        }

    private suspend fun write(
        owner: String?,
        requireActiveOwner: Boolean = true,
        writesContent: Boolean = true,
        operation: suspend () -> StoreWriteResult,
    ): StoreWriteResult = try {
        ownedWrite(owner, StoreWriteResult.OwnerChanged, requireActiveOwner, writesContent, operation)
    } catch (error: SQLiteException) {
        StoreWriteResult.Failed(IOException("Editor draft write failed", error))
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private suspend fun <T> ownedWrite(
        owner: String?,
        rejected: T,
        requireActiveOwner: Boolean = true,
        writesContent: Boolean = true,
        operation: suspend () -> T,
    ): T {
        // Capture before dispatch or the first owner read: A may leave and reconnect while either waits.
        val authorizedGeneration = owner?.let(::purgeGeneration)
        return withContext(ioDispatcher) {
            if (owner == null || (requireActiveOwner && owner != activeOwnerId())) return@withContext rejected
            contentWrites.withLock {
                val ownerMatches = !requireActiveOwner || owner == activeOwnerId()
                // A matching pseudo alone cannot authorize content captured before a purge.
                // Metadata resolution and erasure cannot reintroduce any purged content.
                val generationMatches = !writesContent || authorizedGeneration == purgeGeneration(owner)
                if (generationMatches && ownerMatches) operation() else rejected
            }
        }
    }

    private fun purgeGeneration(owner: String): Long = purgeGenerations[owner.lowercase()] ?: 0L

    private fun Boolean.result(version: Long): StoreWriteResult =
        if (this) StoreWriteResult.Written(version) else StoreWriteResult.Conflict

    private fun Draft.toEntity(owner: String, key: String): EditorDraftEntity = EditorDraftEntity(
        draftKey = rowKey(owner, key),
        ownerId = owner,
        body = body,
        subject = subject,
        recipients = recipients,
        updatedAt = clock.millis(),
        isPrivate = isPrivate,
        pendingInlineQuoteRequest = pendingInlineQuoteRequest?.let { PendingInlineQuoteRequestCodec.encode(it, key) },
    )

    private fun EditorDraftEntity.toDraft(key: String): Draft = Draft(
        body, subject, recipients, isPrivate, updatedAt,
        pendingInlineQuoteRequest?.let { PendingInlineQuoteRequestCodec.decode(it, key) },
    )
    private fun rowKey(owner: String, key: String): String = "$owner|$key"
    private suspend fun activeOwnerId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo?.lowercase()
}
