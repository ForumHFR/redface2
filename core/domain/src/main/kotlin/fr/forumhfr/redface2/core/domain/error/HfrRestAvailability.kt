package fr.forumhfr.redface2.core.domain.error

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface HfrRestAvailability {
    /** The current cooldown deadline, cleared on expiry even when no request is made. */
    val blockedUntil: Flow<Instant?>

    /** Diagnostics stays blocked until an ordinary REST request confirms recovery. */
    val probeBlocked: Flow<Boolean>

    /** Diagnostics traffic cannot claim the single REST recovery request. */
    suspend fun checkProbeAllowed()

    object Available : HfrRestAvailability {
        override val blockedUntil: Flow<Instant?> = flowOf(null)
        override val probeBlocked: Flow<Boolean> = flowOf(false)
        override suspend fun checkProbeAllowed() = Unit
    }
}

class HfrRestBlockedException(val statusCode: Int? = null) : IOException(
    statusCode?.let { "HFR REST refused the request (HTTP $it)" } ?: "HFR REST traffic is paused",
)
