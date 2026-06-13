package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.entities.EditorDraftEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.model.AuthState
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Room-backed [EditorDraftStore] (#405). The active account is snapshotted per call from
 * [AuthRepository]; with no session both reads and writes are no-ops — an anonymous client cannot
 * post (HFR's write forms require a session), so it has no draft, and a save racing a logout must
 * not write a row the purge pass has already swept (CacheInvalidator wipes by previous pseudo).
 *
 * The row key folds the owning account into the domain context key (`"<ownerId>|<contextKey>"`)
 * so two accounts editing the same topic never collide. No draft content is ever logged.
 */
@Singleton
class RoomEditorDraftStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val editorDraftDao: EditorDraftDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EditorDraftStore {

    override suspend fun load(key: String): Draft? = withContext(ioDispatcher) {
        val owner = activeOwnerId() ?: return@withContext null
        editorDraftDao.get(rowKey(owner, key))?.let { entity ->
            Draft(
                body = entity.body,
                subject = entity.subject,
                recipients = entity.recipients,
                isPrivate = entity.isPrivate,
                updatedAt = entity.updatedAt,
            )
        }
    }

    override suspend fun save(key: String, draft: Draft) {
        withContext(ioDispatcher) {
            val owner = activeOwnerId() ?: return@withContext
            editorDraftDao.upsert(
                EditorDraftEntity(
                    draftKey = rowKey(owner, key),
                    ownerId = owner,
                    body = draft.body,
                    subject = draft.subject,
                    recipients = draft.recipients,
                    updatedAt = clock.millis(),
                    isPrivate = draft.isPrivate,
                ),
            )
        }
    }

    override suspend fun delete(key: String) {
        withContext(ioDispatcher) {
            val owner = activeOwnerId() ?: return@withContext
            editorDraftDao.deleteByKey(rowKey(owner, key))
        }
    }

    private fun rowKey(owner: String, key: String): String = "$owner|$key"

    private suspend fun activeOwnerId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)
            ?.pseudo
            ?.lowercase()
}
