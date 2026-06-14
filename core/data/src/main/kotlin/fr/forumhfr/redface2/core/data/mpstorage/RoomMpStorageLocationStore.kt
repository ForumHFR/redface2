package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.database.dao.MpStorageLocationDao
import fr.forumhfr.redface2.core.database.entities.MpStorageLocationEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.model.AuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Room-backed [MpStorageLocationStore] (#6, ADR-014). Rows are keyed by the lowercased [owner] the
 * caller resolved for the active session; each call re-checks the active session and is a no-op when
 * [owner] is `null`, anonymous, or no longer active — same owner-binding contract as
 * [RoomPrivateMessageReadPositionStore], so a write delayed across an `A → B` account switch can
 * never attribute A's storage location to B.
 */
@Singleton
class RoomMpStorageLocationStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val mpStorageLocationDao: MpStorageLocationDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MpStorageLocationStore {

    override suspend fun read(owner: String?): MpStorageLocation? = withContext(ioDispatcher) {
        val userId = owner?.lowercase() ?: return@withContext null
        if (userId != activeUserId()) return@withContext null
        mpStorageLocationDao.get(userId)?.let { MpStorageLocation(it.threadId, it.numreponse) }
    }

    override suspend fun save(owner: String?, threadId: Int, numreponse: Int) {
        withContext(ioDispatcher) {
            val userId = owner?.lowercase() ?: return@withContext
            if (userId != activeUserId()) return@withContext
            mpStorageLocationDao.upsert(
                MpStorageLocationEntity(userId = userId, threadId = threadId, numreponse = numreponse),
            )
        }
    }

    override suspend fun clear(owner: String?) {
        withContext(ioDispatcher) {
            val userId = owner?.lowercase() ?: return@withContext
            if (userId != activeUserId()) return@withContext
            mpStorageLocationDao.deleteAllForUser(userId)
        }
    }

    private suspend fun activeUserId(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)
            ?.pseudo
            ?.lowercase()
}
