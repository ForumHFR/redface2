package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import java.time.Instant

/** One attempted source in the catalogue used by this snapshot (including successful empty lists). */
data class FlagCategoryCoverage(
    val cat: Int,
    val source: FlagSource,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant,
    val failure: FlagFailureKind? = null,
)

data class FlagCoverage(val categories: List<FlagCategoryCoverage> = emptyList()) {
    val isComplete: Boolean
        get() = categories.isNotEmpty() && categories.all { it.failure == null && it.lastSuccessAt != null } &&
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
