package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.FlagFailureKind
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.network.FlagReadDeadlineException
import java.time.Clock

/** Accumulates only complete source partitions. Collection cancellation never discards completed work. */
internal class FlagRefreshCollection(private val clock: Clock, private val startedOrder: Long) {
    var categories: List<Category>? = null
    private val outcomes = linkedMapOf<Pair<Int, FlagSource>, CategoryRefreshOutcome>()
    private val retained = mutableSetOf<Pair<Int, FlagSource>>()

    fun retain(cat: Int, source: FlagSource) { retained += cat to source }
    var failure: Throwable? = null
        private set

    @Synchronized fun record(outcome: CategoryRefreshOutcome) {
        outcomes[outcome.cat to outcome.source] = outcome
    }

    @Synchronized fun failed(error: Throwable) {
        val kind = error.toFlagFailureKind()
        when {
            failure == null || kind == FlagFailureKind.SESSION -> failure = error
            kind == FlagFailureKind.BLOCKED && failure?.toFlagFailureKind() != FlagFailureKind.SESSION -> {
                failure = error
            }
        }
    }

    @Synchronized fun finish(deadline: Boolean): FlagRefreshBatch {
        if (deadline) failed(FlagReadDeadlineException())
        val cats = categories
        val completed = if (cats == null) emptyList() else cats.flatMap { category ->
            listOf(FlagSource.BUCKET, FlagSource.STICKY).mapNotNull { source ->
                if ((category.id to source) in retained) return@mapNotNull null
                outcomes[category.id to source] ?: CategoryRefreshOutcome(
                    category.id, source, receivedAt = clock.instant(),
                    failure = if (deadline) FlagFailureKind.DEADLINE else
                        failure?.toFlagFailureKind() ?: FlagFailureKind.UNKNOWN,
                    readOrder = startedOrder,
                )
            }
        }
        return FlagRefreshBatch(cats?.map { it.id }, completed, clock.instant(), failure,
            cats.orEmpty().associate { it.id to it.name })
    }
}
