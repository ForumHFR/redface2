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
import fr.forumhfr.redface2.core.model.AuthState
import java.io.IOException
import java.time.Clock
import java.util.UUID
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
    @Volatile private var purgeGeneration = 0L

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
            DraftEntry(entity.toDraft(), entity.contentVersion, attempt)
        }
    }

    override suspend fun save(owner: String?, key: String, draft: Draft) {
        ownedWrite(owner, Unit) {
            editorDraftDao.saveLegacy(draft.toEntity(checkNotNull(owner), key))
        }
    }

    /** Account-switch cleanup shares the writers' exclusion, including absent-row inserts. */
    suspend fun purgePrivateForUser(owner: String) = withContext(ioDispatcher) {
        contentWrites.withLock {
            purgeGeneration++
            editorDraftDao.deletePrivateForUser(owner)
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

    override suspend fun beginAttempt(
        owner: String?,
        key: String,
        snapshot: Draft,
        expectedVersion: Long,
    ): BeginAttemptResult {
        val token = AttemptToken(owner.orEmpty(), key, UUID.randomUUID().toString(), expectedVersion + 1)
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
        write(token.owner, requireActiveOwner = outcome != AttemptOutcome.CERTAIN_FAILURE) {
            val status = when (outcome) {
                AttemptOutcome.CERTAIN_FAILURE -> null
                AttemptOutcome.PUBLISHED -> AttemptStatus.PUBLISHED.name
                AttemptOutcome.UNCERTAIN -> AttemptStatus.UNCERTAIN.name
            }
            editorDraftDao.finishVersioned(rowKey(token.owner, token.key), token.id, token.version, status)
                ?.let { StoreWriteResult.Written(it) } ?: StoreWriteResult.Conflict
        }

    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult = write(token.owner) {
        (editorDraftDao.erasePublishedContent(rowKey(token.owner, token.key), token.id, token.version) == 1)
            .result(token.version)
    }

    private suspend fun write(
        owner: String?,
        requireActiveOwner: Boolean = true,
        operation: suspend () -> StoreWriteResult,
    ): StoreWriteResult = try {
        ownedWrite(owner, StoreWriteResult.OwnerChanged, requireActiveOwner, operation)
    } catch (error: SQLiteException) {
        StoreWriteResult.Failed(IOException("Editor draft write failed", error))
    } catch (error: IOException) {
        StoreWriteResult.Failed(error)
    }

    private suspend fun <T> ownedWrite(
        owner: String?,
        rejected: T,
        requireActiveOwner: Boolean = true,
        operation: suspend () -> T,
    ): T {
        // Capture before dispatch or the first owner read: A may leave and reconnect while either waits.
        val authorizedGeneration = purgeGeneration
        return withContext(ioDispatcher) {
            if (owner == null || (requireActiveOwner && owner != activeOwnerId())) return@withContext rejected
            contentWrites.withLock {
                val ownerMatches = !requireActiveOwner || owner == activeOwnerId()
                // A matching pseudo alone cannot authorize content captured before a purge.
                if (authorizedGeneration != purgeGeneration || !ownerMatches) rejected else operation()
            }
        }
    }

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
    )

    private fun EditorDraftEntity.toDraft(): Draft = Draft(body, subject, recipients, isPrivate, updatedAt)
    private fun rowKey(owner: String, key: String): String = "$owner|$key"
    private suspend fun activeOwnerId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo?.lowercase()
}
