package fr.forumhfr.redface2.core.domain.diagnostics

/** Atomically accepts a call for a refresh or leaves its detail to the immediate quota. */
internal class HfrCallPublication {
    private val calls = linkedMapOf<Long, HfrCallSummary>()
    private var active = true

    @Synchronized
    fun call(summary: HfrCallSummary): Boolean {
        if (!active) return false
        calls.putIfAbsent(summary.id, summary)
        return true
    }

    @Synchronized
    fun isActive(): Boolean = active

    @Synchronized
    fun finish(): List<HfrCallSummary>? {
        if (!active) return null
        active = false
        return calls.values.toList().also { calls.clear() }
    }
}
