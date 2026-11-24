package fr.forumhfr.redface2.core.domain.diagnostics

/** Direct anonymous transport; deliberately independent of the flags read policy. */
interface NetworkProbeRunner {
    suspend fun run(onWaveStarted: (Int) -> Unit, onWaveFinished: (NetworkProbeWave) -> Unit)

    companion object {
        val WAVE_SIZES: List<Int> = listOf(1, 5, 10, 20)
        const val WAVE_TIMEOUT_MS = 12_000L
        // Extension point only: a witness needs an approved host and explicit consent (#984).
        const val WITNESS_ENABLED = false
    }
}

enum class NetworkProbeOutcome { OK, HTTP_ERROR, NETWORK_ERROR, DNS_ERROR, EXCHANGE_LIMIT, CANCELLED, DEADLINE }

enum class NetworkProbeDisturbance(val label: String) {
    HFR_TRAFFIC("hfr_traffic"), BACKGROUND("background"), NETWORK_CHANGE("network_change"),
}

/**
 * TCP/TLS describe the acquired route on success, observed failed phases otherwise.
 * TCP includes HTTP proxy CONNECT negotiation before TLS (exported as tcpScope).
 * Headers and first body byte are offsets from call start, not an exact HTTP TTFB.
 */
@Suppress("LongParameterList")
data class NetworkProbeRequestResult(
    val tcpMs: Long? = null,
    val tlsMs: Long? = null,
    val headersReceivedMs: Long? = null,
    val firstBodyByteMs: Long? = null,
    val totalMs: Long = 0,
    val outcome: NetworkProbeOutcome = NetworkProbeOutcome.CANCELLED,
    val errno: HfrConnectErrno? = null,
    val connectAttempts: Int = 0,
    val status: Int? = null,
) {
    fun line(): String = "tcpMs=${tcpMs ?: "-"} tlsMs=${tlsMs ?: "-"} " +
        "headersReceivedMs=${headersReceivedMs ?: "-"} firstBodyByteMs=${firstBodyByteMs ?: "-"} " +
        "totalMs=$totalMs outcome=${outcome.name.lowercase()} errno=${errno?.label ?: "-"} " +
        "connectAttempts=$connectAttempts status=${status ?: "-"} tcpScope=tcp_and_proxy_connect"
}

data class NetworkProbeWave(
    val size: Int,
    val results: List<NetworkProbeRequestResult>,
    val disturbedBy: Set<NetworkProbeDisturbance> = emptySet(),
) {
    init {
        require(size in NetworkProbeRunner.WAVE_SIZES)
        require(results.size == size)
    }

    val disturbed: Boolean get() = disturbedBy.isNotEmpty()
    val successes: Int get() = results.count { it.outcome == NetworkProbeOutcome.OK }
    val failures: Int get() = size - successes
    val medianConnectionMs: Long? get() {
        val times = results.filter { it.outcome == NetworkProbeOutcome.OK }.mapNotNull { result ->
            result.tcpMs?.let { it + (result.tlsMs ?: 0) }
        }.sorted()
        return if (times.isEmpty()) null else {
            val middle = times.size / 2
            if (times.size % 2 == 0) (times[middle - 1] + times[middle]) / 2 else times[middle]
        }
    }
}

interface NetworkProbeReservationStore {
    suspend fun remainingSeconds(): Long
    /** Atomically persists admission before returning zero. A failed write must throw. */
    suspend fun reserve(): Long

    companion object {
        const val COOLDOWN_MS = 60_000L
        fun remainingSeconds(lastStartMillis: Long?, nowMillis: Long): Long = lastStartMillis?.let {
            val elapsed = (nowMillis - it).coerceIn(0, COOLDOWN_MS)
            (COOLDOWN_MS - elapsed + 999) / 1000
        } ?: 0
    }
}
