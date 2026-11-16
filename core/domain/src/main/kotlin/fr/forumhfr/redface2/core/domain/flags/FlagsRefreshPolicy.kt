package fr.forumhfr.redface2.core.domain.flags

/** Stable DataStore IDs; unknown future values retain the baseline behaviour. */
enum class FlagsRefreshStrategy(val id: String) {
    BASELINE("baseline"), LEGACY("legacy"), GLOBAL_ONLY("global-only"), GLOBAL_FIRST("global-first");

    companion object {
        fun fromId(id: String?): FlagsRefreshStrategy = entries.find { it.id == id } ?: BASELINE
    }
}

data class FlagsRefreshPolicy(
    val strategy: FlagsRefreshStrategy = FlagsRefreshStrategy.BASELINE,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val applicationRetry: Boolean = true,
) {
    init { require(concurrency in MIN_CONCURRENCY..MAX_CONCURRENCY) }
    val effectiveConcurrency: Int? get() = concurrency.takeUnless { strategy == FlagsRefreshStrategy.LEGACY }
    fun diagnosticLine(): String = "strategy=${strategy.id} N=${effectiveConcurrency ?: "unbounded"} " +
        "applicationRetry=$applicationRetry"

    companion object {
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 20
        const val DEFAULT_CONCURRENCY = 5
    }
}
