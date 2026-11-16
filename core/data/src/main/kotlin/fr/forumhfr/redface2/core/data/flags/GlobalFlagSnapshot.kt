package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType

/** Never removes an absent row, updates an absent position, or certifies a bucket/sticky source. */
internal fun mergeGlobalFlagSnapshot(
    old: FlagCacheSnapshot,
    userId: String,
    type: FlagType,
    batch: FlagRefreshBatch,
): FlagCacheSnapshot {
    // An empty success proves nothing: keep the exact snapshot, including its dates and coverage.
    if (batch.isEmptyGlobalSuccess) return old
    val incoming = batch.observations.map { flag ->
        val previous = old.rows.find { it.cat == flag.cat && it.topicId == flag.topicId }
        // The global contract excludes stickies: their provenance and receipt date remain owned
        // by topics/last even if an unexpected row appears in this experimental endpoint.
        when {
            previous == null -> flag.toEntity(userId, batch.attemptedAt, FlagSource.GLOBAL)
            previous.source == FlagSource.STICKY -> previous
            else -> previous.copy(
                // Only positive changes; reading anchors, identity, provenance and receipt date
                // remain owned by the category refresh, even when the global row says "read".
                hasUnread = previous.hasUnread || flag.hasUnread,
                isFavorite = previous.isFavorite || flag.isFavorite,
                totalPages = maxOf(previous.totalPages, flag.totalPages),
                replyCount = maxOf(previous.replyCount, flag.replyCount),
                lastReplyAt = maxOf(previous.lastReplyAt, flag.lastReplyAt),
                lastReplyAuthor = if (flag.lastReplyAt > previous.lastReplyAt) flag.lastReplyAuthor
                    else previous.lastReplyAuthor,
            )
        }
    }
    val previousSuccess = old.coverage.firstOrNull { it.isGlobalMarker() }?.lastSuccessAt
    val marker = FlagCategoryCoverageEntity(
        userId, type, GLOBAL_COVERAGE_CAT, FlagSource.GLOBAL,
        if (batch.cause == null) batch.attemptedAt else previousSuccess,
        batch.attemptedAt, batch.cause?.toFlagFailureKind(),
    )
    val rows = (old.rows + incoming).associateBy { it.cat to it.topicId }.values.toList()
    return FlagCacheSnapshot(rows, old.coverage.filterNot { it.isGlobalMarker() } + marker)
}

/** Read both in-memory GLOBAL and its rollback-compatible persisted encoding. */
internal fun FlagCategoryCoverageEntity.isGlobalMarker(): Boolean =
    source == FlagSource.GLOBAL || (cat == GLOBAL_COVERAGE_CAT && source == FlagSource.LEGACY_UNKNOWN)

/** Positive observations (or failures with retained content) survive process death as unverified. */
private const val GLOBAL_COVERAGE_CAT = 0
