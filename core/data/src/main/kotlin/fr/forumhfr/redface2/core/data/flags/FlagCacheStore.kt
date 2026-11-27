package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.cache.CachePolicy
import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagSource
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
        val snapshot = flagDao.getSnapshot(userId, type)
        if (snapshot.rows.isEmpty() && snapshot.coverage.none { it.lastSuccessAt != null }) {
            return@withContext null
        }
        val result = snapshot.toResult()
        CachedFlags(result, isFresh(result))
    }

    fun isFresh(result: FlagsResult.Success): Boolean = result.coverage.isComplete &&
        result.refreshStatus == FlagRefreshStatus.COMPLETE &&
        result.coverage.oldestSuccessAt?.let { CachePolicy.isFresh(it, CachePolicy.flags, clock) } == true

    /** Builds the next snapshot before the single guarded write; retained rows keep their dates. */
    internal suspend fun merge(
        userId: String,
        type: FlagType,
        batch: FlagRefreshBatch,
        reconciledCats: Set<Int> = emptySet(),
    ): FlagCacheSnapshot = withContext(ioDispatcher) {
        val previous = flagDao.getSnapshot(userId, type)
        val merged = mergeFlagSnapshot(previous, userId, type, batch)
        if (reconciledCats.isEmpty()) merged else FlagCacheSnapshot(
            rows = merged.rows.filterNot { it.cat in reconciledCats } +
                previous.rows.filter { it.cat in reconciledCats },
            coverage = merged.coverage.filterNot { it.cat in reconciledCats } +
                previous.coverage.filter { it.cat in reconciledCats },
        )
    }

    internal suspend fun save(userId: String, type: FlagType, snapshot: FlagCacheSnapshot) {
        withContext(ioDispatcher) { flagDao.replaceSnapshot(userId, type, snapshot) }
    }

    /** Local favourite decoration changes no source, coverage or receipt time. */
    suspend fun markFavorite(userId: String, type: FlagType, cat: Int, topicId: Int) {
        withContext(ioDispatcher) { flagDao.markFavorite(userId, type, cat, topicId) }
    }

    /**
     * Removes a single cached drapeau row after a successful `delflag.php` (#99). Keyed on
     * the logical `(userId, type, cat, topicId)` tuple — see [FlagDao.deleteFlag]. No-op
     * when the row is already absent.
     */
    suspend fun delete(userId: String, type: FlagType, cat: Int, topicId: Int) {
        withContext(ioDispatcher) {
            flagDao.deleteFlag(userId = userId, type = type, cat = cat, topicId = topicId)
        }
    }

    /**
     * Invalidates a complete cached bucket after a mutation whose response cannot provide a full
     * replacement row (#986). The next observation must fetch REST instead of serving a fresh but
     * incomplete Room snapshot.
     */
    suspend fun invalidate(userId: String, type: FlagType) {
        withContext(ioDispatcher) {
            flagDao.deleteForType(userId = userId, type = type)
        }
    }

    data class CachedFlags(
        val result: FlagsResult.Success,
        val isFresh: Boolean,
    )
}

internal fun FlagTopicEntity.toFlag(): Flag = Flag(
    cat = cat,
    subcat = subcat,
    topicId = topicId,
    title = title,
    totalPages = totalPages,
    replyCount = replyCount,
    type = type,
    isFavorite = isFavorite,
    hasUnread = hasUnread,
    lastReadPage = lastReadPage,
    lastPostReadId = lastPostReadId,
    lastPosition = lastPosition,
    postsPerPage = postsPerPage,
    firstPostAuthor = firstPostAuthor,
    lastReplyAuthor = lastReplyAuthor,
    lastReplyAt = lastReplyAt,
)

internal fun Flag.toEntity(userId: String, fetchedAt: Instant, source: FlagSource): FlagTopicEntity = FlagTopicEntity(
    userId = userId,
    type = type,
    cat = cat,
    subcat = subcat,
    topicId = topicId,
    title = title,
    totalPages = totalPages,
    replyCount = replyCount,
    isFavorite = isFavorite,
    hasUnread = hasUnread,
    lastReadPage = lastReadPage,
    lastPostReadId = lastPostReadId,
    lastPosition = lastPosition,
    postsPerPage = postsPerPage,
    firstPostAuthor = firstPostAuthor,
    lastReplyAuthor = lastReplyAuthor,
    lastReplyAt = lastReplyAt,
    fetchedAt = fetchedAt,
    authMode = FetchMode.AUTHENTICATED,
    source = source,
)
