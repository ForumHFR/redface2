package fr.forumhfr.redface2.core.domain.stats

import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import java.time.Instant
import java.time.LocalDate

enum class NetworkTarget {
    REST_CATALOGUE, REST_CATEGORY_PARTICIPATED, REST_CATEGORY_READ, REST_CATEGORY_FAVORITES,
    REST_CATEGORY_LAST, REST_SUBCATEGORIES, REST_GLOBAL_PARTICIPATED, REST_GLOBAL_READ, REST_GLOBAL_FAVORITES,
    REST_OTHER, HTML_FORUM1, HTML_FORUM2, HTML_MP_LIST, HTML_WRITE, HTML_OTHER,
    FAVORITE_SMILEYS, FORUM_IMAGES, THIRD_PARTY_IMAGES, THIRD_PARTY_UPLOAD, PROBE, OTHER_HOST,
}

enum class NetworkRole { AUTH, ANON, MUTATION, UPLOAD, IMAGE, SMILEY, PROBE }

enum class NetworkStatus { SUCCESS, REDIRECT, FORBIDDEN, RATE_LIMITED, OTHER_CLIENT_ERROR, SERVER_ERROR, NO_RESPONSE }

enum class NoRequestReason { CANCELLED, DNS, CONNECT, TCP_TIMEOUT, TLS, OTHER_IO }

enum class NetworkPeakGroup { FORUM, FORUM_IMAGES, PROBE }

enum class NetworkTrigger(val flags: FlagRefreshTrigger? = null) {
    LAUNCH(FlagRefreshTrigger.LAUNCH),
    RETURN_FROM_TOPIC(FlagRefreshTrigger.RETURN_FROM_TOPIC),
    RESUME(FlagRefreshTrigger.RESUME),
    TAB(FlagRefreshTrigger.TAB),
    PULL(FlagRefreshTrigger.PULL),
    INITIAL_OBSERVE(FlagRefreshTrigger.INITIAL_OBSERVE),
    MP_BADGE,
    NONE,
    ;

    companion object {
        private val byFlag = entries.filter { it.flags != null }.associateBy { it.flags }

        fun fromFlags(trigger: FlagRefreshTrigger): NetworkTrigger = byFlag[trigger] ?: NONE
    }
}

data class NetworkDimensions(
    val versionCode: Int,
    val target: NetworkTarget,
    val role: NetworkRole,
    val visibility: AppVisibility,
    val trigger: NetworkTrigger,
    val network: NetworkKind = NetworkKind.UNKNOWN,
)

data class NetworkRequestCounts(
    val requests: Long = 0,
    val bodyBytesIn: Long = 0,
    val bodyBytesOut: Long = 0,
    val incompleteBodies: Long = 0,
) {
    operator fun plus(other: NetworkRequestCounts) = NetworkRequestCounts(
        requests + other.requests,
        bodyBytesIn + other.bodyBytesIn,
        bodyBytesOut + other.bodyBytesOut,
        incompleteBodies + other.incompleteBodies,
    )
}

data class NetworkPeaks(val perSecond: Long, val perMinute: Long, val inFlight: Long) {
    fun max(other: NetworkPeaks) = NetworkPeaks(
        maxOf(perSecond, other.perSecond),
        maxOf(perMinute, other.perMinute),
        maxOf(inFlight, other.inFlight),
    )
}

data class NetworkRequestKey(val dimensions: NetworkDimensions, val status: NetworkStatus)
data class NoRequestKey(val dimensions: NetworkDimensions, val reason: NoRequestReason)
data class NetworkPeakKey(val versionCode: Int, val group: NetworkPeakGroup)

enum class NetworkAction {
    TOPIC_PAGE, LIST, FLAG_PULL, FLAG_REFRESH_ADMITTED, FLAG_REFRESH_IGNORED, WRITE, FOREGROUND_SESSION,
    FOREGROUND_SECONDS,
}

data class NetworkActionKey(
    val versionCode: Int,
    val action: NetworkAction,
    val trigger: NetworkTrigger = NetworkTrigger.NONE,
)

fun interface NetworkActionRecorder {
    fun record(action: NetworkAction, trigger: NetworkTrigger, count: Long)

    fun record(action: NetworkAction) = record(action, NetworkTrigger.NONE, 1)

    companion object {
        val None = NetworkActionRecorder { _, _, _ -> }
    }
}

/** Keep a failing recorder out of the operation whose outcome it measures. */
fun NetworkActionRecorder.recordSafely(
    action: NetworkAction,
    trigger: NetworkTrigger = NetworkTrigger.NONE,
    count: Long = 1,
) {
    if (count > 0) runCatching { record(action, trigger, count) }
}

data class NetworkStatsTotals(
    val requests: Map<NetworkRequestKey, NetworkRequestCounts> = emptyMap(),
    val noRequests: Map<NoRequestKey, Long> = emptyMap(),
    val peaks: Map<NetworkPeakKey, NetworkPeaks> = emptyMap(),
    val actions: Map<NetworkActionKey, Long> = emptyMap(),
) {
    operator fun plus(other: NetworkStatsTotals) = NetworkStatsTotals(
        requests.mergedWith(other.requests, NetworkRequestCounts::plus),
        noRequests.mergedWith(other.noRequests, Long::plus),
        peaks.mergedWith(other.peaks, NetworkPeaks::max),
        actions.mergedWith(other.actions, Long::plus),
    )

    private fun <K, V : Any> Map<K, V>.mergedWith(other: Map<K, V>, combine: (V, V) -> V): Map<K, V> =
        toMutableMap().apply { other.forEach { (key, value) -> merge(key, value, combine) } }
}

data class NetworkStatsDay(val dayUtc: LocalDate, val totals: NetworkStatsTotals)

data class NetworkStatsReport(
    val recordedSince: Instant? = null,
    val period: NetworkStatsTotals = NetworkStatsTotals(),
    val days: List<NetworkStatsDay> = emptyList(),
    val versions: Set<Int> = emptySet(),
)

interface NetworkStatsRepository {
    /** UTC range [from, until), rounded outwards to the stored hours. */
    suspend fun read(from: Instant, until: Instant): NetworkStatsReport

    suspend fun reset(): Boolean
}

data class NetworkStatsProcess(val versionCode: Int, val isMain: Boolean) {
    companion object {
        fun forApp(versionCode: Int, processName: String?, packageName: String) =
            NetworkStatsProcess(versionCode, isMain = processName == packageName)
    }
}

data class NetworkStatsStart(
    val epochSecond: Long,
    val monotonicNanos: Long,
    val visibility: AppVisibility,
    val role: NetworkRole,
    val network: NetworkKind = NetworkKind.UNKNOWN,
) {
    val hourUtc: Long get() = Math.floorDiv(epochSecond, SECONDS_PER_HOUR)

    companion object {
        const val SECONDS_PER_HOUR = 3600L
    }
}

interface NetworkStatsRecorder {
    val isEnabled: Boolean

    fun started(start: NetworkStatsStart): CallRecording?

    interface CallRecording {
        fun sent(target: NetworkTarget, peakGroup: NetworkPeakGroup?, trigger: NetworkTrigger): WireRecording
        fun noRequest(target: NetworkTarget, reason: NoRequestReason, trigger: NetworkTrigger)
    }

    interface WireRecording {
        fun finish(status: NetworkStatus, counts: NetworkRequestCounts)
    }
}
