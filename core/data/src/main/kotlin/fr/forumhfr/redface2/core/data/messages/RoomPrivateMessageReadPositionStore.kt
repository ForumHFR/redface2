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
 * Room-backed [PrivateMessageReadPositionStore] (#430). Rows are keyed by the lowercased pseudo
 * of the CURRENT session (snapshotted per call from [AuthRepository]); with no active session
 * both operations are no-ops — an anonymous client has no inbox, and a save racing a logout must
 * not write a row the purge pass has already swept (CacheInvalidator wipes by previous pseudo).
 */
@Singleton
class RoomPrivateMessageReadPositionStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val mpReadPositionDao: MpReadPositionDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PrivateMessageReadPositionStore {

    override suspend fun readPage(threadId: Int): Int? = withContext(ioDispatcher) {
        val userId = activeUserId() ?: return@withContext null
        mpReadPositionDao.readPage(userId = userId, threadId = threadId)
    }

    override suspend fun savePage(threadId: Int, page: Int) {
        if (page < 1) return
        withContext(ioDispatcher) {
            val userId = activeUserId() ?: return@withContext
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
