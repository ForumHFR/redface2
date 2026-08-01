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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Room-backed [EditorDraftStore] (#405). Drafts are keyed by the OWNING account the caller
 * captured via [currentOwner] when the editor opened, never by whatever account is active when a
 * late write lands. The row key folds that owner into the domain context key
 * (`"<ownerId>|<contextKey>"`) so two accounts editing the same topic never collide.
 *
 * Every operation additionally checks that the captured [owner] is still the active account
 * (switch or logout otherwise), the same guard on all three verbs (#953 F2) :
 *  - [save] drops the write — a debounced autosave racing an account change must not leak account
 *    A's content under account B, nor revive a row A's logout purge already swept (CacheInvalidator
 *    wipes by the previous pseudo) ;
 *  - [load] reads nothing — a session riding a stale owner snapshot must never seed account B's
 *    editor from account A's private row ;
 *  - [delete] is a no-op — a submit landing after a switch must not sweep the previous account's
 *    still-valid row.
 * No draft content is ever logged.
 */
@Singleton
class RoomEditorDraftStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val editorDraftDao: EditorDraftDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EditorDraftStore {

    override suspend fun currentOwner(): String? = withContext(ioDispatcher) { activeOwnerId() }

    override suspend fun load(owner: String?, key: String): Draft? = withContext(ioDispatcher) {
        // #953 F2 — same owner guard as save : an anonymous session, or a session whose account
        // is no longer active, reads nothing (the DAO is never touched).
        if (owner == null || owner != activeOwnerId()) return@withContext null
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

    override suspend fun save(owner: String?, key: String, draft: Draft) {
        withContext(ioDispatcher) {
            // No-op for an anonymous session, OR when the session's account is no longer active:
            // the draft belongs to a session whose owner switched/logged out, so writing it would
            // leak A's content under B or revive a row the logout purge already swept.
            if (owner == null || owner != activeOwnerId()) return@withContext
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

    override suspend fun delete(owner: String?, key: String) {
        withContext(ioDispatcher) {
            // #953 F2 — same owner guard as save : a delete riding a stale owner snapshot (e.g.
            // a submit finishing after an account switch) must not sweep the previous account's
            // still-valid row.
            if (owner == null || owner != activeOwnerId()) return@withContext
            // Best-effort: callers AWAIT this on the post-success path (so the nav pop can't cancel
            // the delete before the row is gone), which means a throw here would abort the success
            // flow on a message HFR already accepted — crashing or stranding the editor. A local
            // DELETE-by-key can still fail (disk full / DB locked); swallow it. A surviving row only
            // costs a spurious « restore draft » prompt. CancellationException is rethrown so genuine
            // coroutine cancellation still propagates.
            try {
                editorDraftDao.deleteByKey(rowKey(owner, key))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Best-effort cleanup — never block a successful post on a failed local delete.
            }
        }
    }

    private fun rowKey(owner: String, key: String): String = "$owner|$key"

    private suspend fun activeOwnerId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)
            ?.pseudo
            ?.lowercase()
}
