package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.database.entities.FlagTopicEntity
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
    val globalOnly: Boolean = false,
    val observations: List<Flag> = emptyList(),
    // A targeted lookup sees the catalogue but does not read its other categories.
    val unverifiedCategoryIds: List<Int> = emptyList(),
    // Only inventory-wide batches may retire the global coverage marker.
    val resolutionCat: Int? = null,
) {
    val isEmptyGlobalSuccess: Boolean get() = globalOnly && observations.isEmpty() && cause == null
}

internal fun mergeFlagSnapshot(
    old: FlagCacheSnapshot,
    userId: String,
    type: FlagType,
    batch: FlagRefreshBatch,
): FlagCacheSnapshot {
    if (batch.globalOnly) return mergeGlobalFlagSnapshot(old, userId, type, batch)
    val outcomes = batch.outcomes.ifEmpty {
        val cats = (old.rows.map { it.cat } + old.coverage.map { it.cat }).distinct().filter { it > 0 }
        cats.flatMap { cat ->
            listOf(FlagSource.BUCKET, FlagSource.STICKY).map { source ->
                CategoryRefreshOutcome(cat, source, receivedAt = batch.attemptedAt,
                    failure = batch.cause?.toFlagFailureKind())
            }
        }
    }
    val coverage = old.coverage.filter { previous ->
        (batch.categoryIds == null || previous.cat in batch.categoryIds) &&
            (!previous.isGlobalMarker() || batch.resolutionCat != null) &&
            outcomes.none { it.cat == previous.cat && it.source == previous.source }
    } + outcomes.map { outcome ->
        val previous = old.coverage.find { it.cat == outcome.cat && it.source == outcome.source }
        FlagCategoryCoverageEntity(userId, type, outcome.cat, outcome.source,
            if (outcome.failure == null) outcome.receivedAt else previous?.lastSuccessAt,
            outcome.receivedAt, outcome.failure)
    }
    val unverified = batch.unverifiedCategoryIds.flatMap { cat ->
        listOf(FlagSource.BUCKET, FlagSource.STICKY).mapNotNull { source ->
            if (coverage.any { it.cat == cat && it.source == source }) null else
                FlagCategoryCoverageEntity(userId, type, cat, source, null, batch.attemptedAt, null)
        }
    }
    val successful = outcomes.filter { it.failure == null }.map { it.cat to it.source }.toSet()
    val retained = old.rows.filter { row ->
        belongsToCatalogue(row.cat, batch.categoryIds) && !row.wasReconciled(successful, coverage)
    }
    val incoming = outcomes.filter { it.failure == null }.flatMap { outcome ->
        outcome.flags.map { it.toEntity(userId, outcome.receivedAt, outcome.source) }
    }
    val rows = (retained + incoming)
        .associateBy { it.cat to it.topicId }.values.toList()
    return FlagCacheSnapshot(rows, coverage + unverified)
}

internal fun FlagCacheSnapshot.toResult(): FlagsResult.Success {
    val domainCoverage = FlagCoverage(coverage.map {
        FlagCategoryCoverage(it.cat, it.source, it.lastSuccessAt, it.lastAttemptAt, it.failure)
    })
    val missingStickies = coverage.filter { it.source == FlagSource.STICKY && it.failure != null }
        .map { FlagRefreshCategory(it.cat, null) }
    val truncated = coverage.any { it.failure == FlagFailureKind.PAGE_CAP }
    return FlagsResult.Success(
        flags = rows.map { it.toFlag() }.sortedByDescending { it.lastReplyAt },
        issue = if (missingStickies.isEmpty() && !truncated) null else FlagRefreshIssue(missingStickies, truncated),
        coverage = domainCoverage,
        refreshStatus = refreshStatus(domainCoverage),
        cachedAt = (rows.map { it.fetchedAt } + coverage.mapNotNull { it.lastSuccessAt }).minOrNull(),
    )
}

private fun FlagCacheSnapshot.refreshStatus(domainCoverage: FlagCoverage): FlagRefreshStatus {
    val bucketsSucceeded = coverage.any {
        it.source == FlagSource.BUCKET && it.failure == null && it.lastSuccessAt != null
    }
    return when {
        coverage.isEmpty() -> FlagRefreshStatus.UNVERIFIED
        domainCoverage.isComplete -> FlagRefreshStatus.COMPLETE
        coverage.any { it.isGlobalMarker() && it.failure != null } -> FlagRefreshStatus.FAILED
        coverage.any { it.failure != null } && !bucketsSucceeded -> FlagRefreshStatus.FAILED
        domainCoverage.isGlobalObservation -> FlagRefreshStatus.PARTIAL
        bucketsSucceeded -> FlagRefreshStatus.PARTIAL
        else -> FlagRefreshStatus.FAILED
    }
}

private fun belongsToCatalogue(cat: Int, categoryIds: List<Int>?): Boolean = categoryIds == null || cat in categoryIds

private fun FlagTopicEntity.wasReconciled(
    successful: Set<Pair<Int, FlagSource>>,
    coverage: List<FlagCategoryCoverageEntity>,
): Boolean {
    if (source == FlagSource.LEGACY_UNKNOWN) {
        // Both sources must have succeeded since migration. Room also encodes global observations
        // as LEGACY_UNKNOWN: historical success cannot retire an observation received at or after it.
        val bucketSuccess = coverage.firstOrNull { it.cat == cat && it.source == FlagSource.BUCKET }
            ?.lastSuccessAt
        val stickySucceeded = coverage.any {
            it.cat == cat && it.source == FlagSource.STICKY && it.lastSuccessAt != null
        }
        return stickySucceeded && bucketSuccess != null &&
            ((cat to FlagSource.BUCKET) in successful || fetchedAt.isBefore(bucketSuccess))
    }
    return (cat to source) in successful || (source == FlagSource.GLOBAL && (cat to FlagSource.BUCKET) in successful)
}
