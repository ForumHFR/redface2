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
        withContext(ioDispatcher) {
            if (owner != null && owner == activeOwnerId()) {
                editorDraftDao.saveLegacy(draft.toEntity(owner, key))
            }
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
        write(token.owner) {
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

    private suspend fun write(owner: String?, operation: suspend () -> StoreWriteResult): StoreWriteResult =
        withContext(ioDispatcher) {
            try {
                if (owner == null || owner != activeOwnerId()) StoreWriteResult.OwnerChanged else operation()
            } catch (error: SQLiteException) {
                StoreWriteResult.Failed(IOException("Editor draft write failed", error))
            } catch (error: IOException) {
                StoreWriteResult.Failed(error)
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
