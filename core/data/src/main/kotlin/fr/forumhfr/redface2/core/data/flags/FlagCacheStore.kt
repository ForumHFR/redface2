package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class FlagCacheStore @Inject constructor(
    private val flagDao: FlagDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun load(type: FlagType, userId: String): CachedFlags? = withContext(ioDispatcher) {
        val rows = flagDao.getFlags(userId = userId, type = type)
        if (rows.isEmpty()) return@withContext null

        val fetchedAt = flagDao.getLastFetchedAt(userId = userId, type = type)
            ?: return@withContext null
        CachedFlags(
            result = FlagsResult.Success(rows.map { it.toFlag() }),
            isFresh = CachePolicy.isFresh(fetchedAt, CachePolicy.flags, clock),
        )
    }

    suspend fun replace(userId: String, type: FlagType, flags: List<Flag>) {
        val fetchedAt = clock.instant()
        withContext(ioDispatcher) {
            flagDao.replaceForType(
                userId = userId,
                type = type,
                rows = flags.map { it.toEntity(userId = userId, fetchedAt = fetchedAt) },
            )
        }
    }

    data class CachedFlags(
        val result: FlagsResult.Success,
        val isFresh: Boolean,
    )
}

private fun FlagTopicEntity.toFlag(): Flag = Flag(
    cat = cat,
    subcat = subcat,
    topicId = topicId,
    title = title,
    totalPages = totalPages,
    replyCount = replyCount,
    type = type,
    hasUnread = hasUnread,
    lastReadPage = lastReadPage,
    lastPostReadId = lastPostReadId,
    firstPostAuthor = firstPostAuthor,
    lastReplyAuthor = lastReplyAuthor,
    lastReplyAt = lastReplyAt,
)

private fun Flag.toEntity(userId: String, fetchedAt: Instant): FlagTopicEntity = FlagTopicEntity(
    userId = userId,
    type = type,
    cat = cat,
    subcat = subcat,
    topicId = topicId,
    title = title,
    totalPages = totalPages,
    replyCount = replyCount,
    hasUnread = hasUnread,
    lastReadPage = lastReadPage,
    lastPostReadId = lastPostReadId,
    firstPostAuthor = firstPostAuthor,
    lastReplyAuthor = lastReplyAuthor,
    lastReplyAt = lastReplyAt,
    fetchedAt = fetchedAt,
    authMode = FetchMode.AUTHENTICATED,
)
