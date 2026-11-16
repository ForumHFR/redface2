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
    val incoming = batch.observations.map { flag ->
        val previous = old.rows.find { it.cat == flag.cat && it.topicId == flag.topicId }
        // The global contract excludes stickies: their provenance and receipt date remain owned
        // by topics/last even if an unexpected row appears in this experimental endpoint.
        previous?.takeIf { it.source == FlagSource.STICKY }
            ?: flag.toEntity(userId, batch.attemptedAt, FlagSource.GLOBAL)
    }
    val previousSuccess = old.coverage.firstOrNull { it.source == FlagSource.GLOBAL }?.lastSuccessAt
    val marker = FlagCategoryCoverageEntity(
        userId, type, GLOBAL_COVERAGE_CAT, FlagSource.GLOBAL,
        if (batch.cause == null) batch.attemptedAt else previousSuccess,
        batch.attemptedAt, batch.cause?.toFlagFailureKind(),
    )
    val rows = (old.rows + incoming).associateBy { it.cat to it.topicId }.values.toList()
    return FlagCacheSnapshot(rows, old.coverage.filterNot { it.source == FlagSource.GLOBAL } + marker)
}

/** A global attempt marker also survives a successful EMPTY response and process death. */
private const val GLOBAL_COVERAGE_CAT = 0
