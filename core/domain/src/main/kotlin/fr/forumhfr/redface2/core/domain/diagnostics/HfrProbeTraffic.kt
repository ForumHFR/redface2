package fr.forumhfr.redface2.core.domain.diagnostics

import javax.inject.Inject
import javax.inject.Singleton

/** Counts ordinary HFR calls only; probe calls use a dedicated listener and never enter here. */
@Singleton
class HfrProbeTraffic @Inject constructor() {
    private var generation = 0L
    private var active = 0

    @Synchronized
    fun started() { generation++; active++ }

    @Synchronized
    fun finished() { active-- }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(generation, active > 0)

    @Synchronized
    fun disturbed(since: Snapshot): Boolean = since.alreadyActive || generation != since.generation

    data class Snapshot(val generation: Long, val alreadyActive: Boolean)
}
