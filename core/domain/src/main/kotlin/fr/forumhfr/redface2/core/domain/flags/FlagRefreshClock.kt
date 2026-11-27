package fr.forumhfr.redface2.core.domain.flags

import javax.inject.Inject

/** Process-local clock for the flags cadence. Wall time is used only to display a retry time. */
class FlagRefreshClock @Inject constructor() {
    fun nowNanos(): Long = System.nanoTime()
}
