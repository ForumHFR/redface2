package fr.forumhfr.redface2.core.domain.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** A generation detects even a refresh that starts AND finishes during an unrelated call. */
@Singleton
class HfrRefreshActivity @Inject constructor() {
    private var generation = 0L
    private var active = 0

    @Synchronized
    fun begin(): DiagnosticsSubscription {
        generation++
        active++
        val closed = AtomicBoolean()
        return DiagnosticsSubscription { if (closed.compareAndSet(false, true)) end() }
    }

    @Synchronized
    fun idleGeneration(): Long? = generation.takeIf { active == 0 }

    @Synchronized
    private fun end() { active-- }
}
