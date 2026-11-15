package fr.forumhfr.redface2.core.domain.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A generation detects even a refresh that starts AND finishes during an unrelated call. */
@Singleton
class HfrRefreshActivity @Inject constructor() {
    private var generation = 0L
    private var active = 0
    private val probeActive = MutableStateFlow(false)

    @Synchronized
    fun begin(): DiagnosticsSubscription {
        generation++
        active++
        val closed = AtomicBoolean()
        return DiagnosticsSubscription { if (closed.compareAndSet(false, true)) end() }
    }

    /** Admission and refresh ownership share the same monitor, closing the check/start race. */
    @Synchronized
    fun tryBeginProbe(): DiagnosticsSubscription? {
        if (active != 0 || probeActive.value) return null
        generation++
        probeActive.value = true
        val closed = AtomicBoolean()
        return DiagnosticsSubscription {
            if (closed.compareAndSet(false, true)) synchronized(this) { probeActive.value = false }
        }
    }

    /** A refresh requested during a probe waits without starting any flags transport. */
    suspend fun <T> withRefresh(block: suspend () -> T): T {
        var lease: DiagnosticsSubscription? = null
        while (lease == null) {
            probeActive.first { !it }
            lease = synchronized(this) { if (probeActive.value) null else begin() }
        }
        try {
            return block()
        } finally {
            lease.close()
        }
    }

    @Synchronized
    fun idleGeneration(): Long? = generation.takeIf { active == 0 && !probeActive.value }

    @Synchronized
    private fun end() { active-- }
}
