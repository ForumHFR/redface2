package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.entities.MpReadPositionEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Room-backed [PrivateMessageReadPositionStore] (#430). Rows are keyed by the lowercased [owner]
 * the caller captured when it read the conversation — NOT by a freshly-resolved active user, so a
 * write delayed by IO is always attributed to the session that actually read the page. Each call
 * still re-checks the active session and is a no-op when [owner] is `null`, anonymous, or no longer
 * active: an anonymous client has no inbox; a save racing a logout must not write a row the purge
 * pass has already swept (CacheInvalidator wipes by previous pseudo); and an `A → B` account switch
 * must not misattribute A's position to B (Codex review of the 0.11.0 beta).
 */
@Singleton
class RoomPrivateMessageReadPositionStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val mpReadPositionDao: MpReadPositionDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PrivateMessageReadPositionStore {

    override suspend fun readPage(owner: String?, threadId: Int): Int? = withContext(ioDispatcher) {
        val userId = owner?.lowercase() ?: return@withContext null
        if (userId != activeUserId()) return@withContext null
        mpReadPositionDao.readPage(userId = userId, threadId = threadId)
    }

    override suspend fun savePage(owner: String?, threadId: Int, page: Int) {
        if (page < 1) return
        withContext(ioDispatcher) {
            val userId = owner?.lowercase() ?: return@withContext
            if (userId != activeUserId()) return@withContext
            mpReadPositionDao.upsert(
                MpReadPositionEntity(userId = userId, threadId = threadId, page = page),
            )
        }
    }

    private suspend fun activeUserId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)
            ?.pseudo
            ?.lowercase()
}
