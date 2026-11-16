package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import java.time.Instant

/** A catalogue source, including successful empty lists and known but unverified categories. */
data class FlagCategoryCoverage(
    val cat: Int,
    val source: FlagSource,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant,
    val failure: FlagFailureKind? = null,
)

data class FlagCoverage(val categories: List<FlagCategoryCoverage> = emptyList()) {
    /**
     * Category zero is a neutral observation marker, never a verified HFR category.
     * Room encodes its source as LEGACY_UNKNOWN so baseline v23 builds can read it.
     */
    val isGlobalObservation: Boolean get() = categories.any {
        it.source == FlagSource.GLOBAL || (it.cat == 0 && it.source == FlagSource.LEGACY_UNKNOWN)
    }

    val isComplete: Boolean
        get() = !isGlobalObservation && categories.isNotEmpty() &&
            categories.all { it.failure == null && it.lastSuccessAt != null } &&
            categories.map { it.cat }.distinct().all(::covers)

    /** A missing bucket or sticky source cannot certify absence for this category. */
    fun covers(cat: Int): Boolean = listOf(FlagSource.BUCKET, FlagSource.STICKY).all { source ->
        covers(cat, source)
    }

    fun covers(cat: Int, source: FlagSource): Boolean = categories.any {
        it.cat == cat && it.source == source && it.failure == null && it.lastSuccessAt != null
    }

    val oldestSuccessAt: Instant? get() = categories.mapNotNull { it.lastSuccessAt }.minOrNull()
    val lastAttemptAt: Instant? get() = categories.maxOfOrNull { it.lastAttemptAt }
}

enum class FlagRefreshStatus { UNVERIFIED, COMPLETE, PARTIAL, FAILED }
