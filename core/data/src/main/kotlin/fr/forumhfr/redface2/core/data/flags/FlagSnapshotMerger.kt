package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.domain.flags.FlagCategoryCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshCategory
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant

internal data class CategoryRefreshOutcome(
    val cat: Int,
    val source: FlagSource,
    val flags: List<Flag> = emptyList(),
    val receivedAt: Instant,
    val failure: FlagFailureKind? = null,
)

internal data class FlagRefreshBatch(
    // Null means the catalogue failed: preserve its previous membership, rather than purging it.
    val categoryIds: List<Int>?,
    val outcomes: List<CategoryRefreshOutcome>,
    val attemptedAt: Instant,
    val cause: Throwable? = null,
    val categoryNames: Map<Int, String> = emptyMap(),
)

internal fun mergeFlagSnapshot(
    old: FlagCacheSnapshot,
    userId: String,
    type: FlagType,
    batch: FlagRefreshBatch,
): FlagCacheSnapshot {
    val outcomes = batch.outcomes.ifEmpty {
        val cats = (old.rows.map { it.cat } + old.coverage.map { it.cat }).distinct()
        cats.flatMap { cat ->
            listOf(FlagSource.BUCKET, FlagSource.STICKY).map { source ->
                CategoryRefreshOutcome(cat, source, receivedAt = batch.attemptedAt,
                    failure = batch.cause?.toFlagFailureKind())
            }
        }
    }
    val coverage = outcomes.map { outcome ->
        val previous = old.coverage.find { it.cat == outcome.cat && it.source == outcome.source }
        FlagCategoryCoverageEntity(userId, type, outcome.cat, outcome.source,
            if (outcome.failure == null) outcome.receivedAt else previous?.lastSuccessAt,
            outcome.receivedAt, outcome.failure)
    }
    val successful = outcomes.filter { it.failure == null }.map { it.cat to it.source }.toSet()
    val retained = old.rows.filter { row ->
        (batch.categoryIds == null || row.cat in batch.categoryIds) && (row.cat to row.source) !in successful
    }
    val incoming = outcomes.filter { it.failure == null }.flatMap { outcome ->
        outcome.flags.map { it.toEntity(userId, outcome.receivedAt, outcome.source) }
    }
    // LEGACY_UNKNOWN is retired only after BOTH sources succeeded at least once since migration.
    // A later failure preserves lastSuccessAt and does not revoke that historical reconciliation.
    val reconciled = coverage.groupBy { it.cat }.filterValues { sources ->
        listOf(FlagSource.BUCKET, FlagSource.STICKY).all { source ->
            sources.any { it.source == source && it.lastSuccessAt != null }
        }
    }.keys
    val rows = (retained.filterNot { it.source == FlagSource.LEGACY_UNKNOWN && it.cat in reconciled } + incoming)
        .associateBy { it.cat to it.topicId }.values.toList()
    return FlagCacheSnapshot(rows, coverage)
}

internal fun FlagCacheSnapshot.toResult(): FlagsResult.Success {
    val domainCoverage = FlagCoverage(coverage.map {
        FlagCategoryCoverage(it.cat, it.source, it.lastSuccessAt, it.lastAttemptAt, it.failure)
    })
    val bucketsSucceeded = coverage.any {
        it.source == FlagSource.BUCKET && it.failure == null
    }
    val status = when {
        domainCoverage.isComplete -> FlagRefreshStatus.COMPLETE
        bucketsSucceeded -> FlagRefreshStatus.PARTIAL
        else -> FlagRefreshStatus.FAILED
    }
    val missingStickies = coverage.filter { it.source == FlagSource.STICKY && it.failure != null }
        .map { FlagRefreshCategory(it.cat, null) }
    val truncated = coverage.any { it.failure == FlagFailureKind.PAGE_CAP }
    return FlagsResult.Success(
        flags = rows.map { it.toFlag() }.sortedByDescending { it.lastReplyAt },
        issue = if (missingStickies.isEmpty() && !truncated) null else FlagRefreshIssue(missingStickies, truncated),
        coverage = domainCoverage,
        refreshStatus = status,
        cachedAt = (rows.map { it.fetchedAt } + coverage.mapNotNull { it.lastSuccessAt }).minOrNull(),
    )
}
