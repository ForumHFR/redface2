package fr.forumhfr.redface2.feature.settings

import android.content.res.Resources
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkPeaks
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger

internal enum class NetworkStatsTraffic { API, PAGES, IMAGES, THIRD_PARTIES, PROBE }

internal data class NetworkStatsMetrics(
    val traffic: Map<NetworkStatsTraffic, Long> = emptyMap(),
    val errors: Map<NetworkStatus, Long> = emptyMap(),
    val unsent: Map<NetworkKind, Long> = emptyMap(),
    val transfers: NetworkRequestCounts = NetworkRequestCounts(),
    val peaks: Map<NetworkPeakGroup, NetworkPeaks> = emptyMap(),
    val actions: Map<NetworkAction, Long> = emptyMap(),
    val flags: Map<NetworkTrigger, FlagRefreshMetrics> = emptyMap(),
)

internal data class FlagRefreshMetrics(val admitted: Long, val ignored: Long, val requests: Long)

internal fun NetworkStatsTotals.versionCodes(): Set<Int> = buildSet {
    requests.keys.forEach { add(it.dimensions.versionCode) }
    noRequests.keys.forEach { add(it.dimensions.versionCode) }
    peaks.keys.forEach { add(it.versionCode) }
    actions.keys.forEach { add(it.versionCode) }
}

internal fun NetworkStatsTotals.toMetrics(): NetworkStatsMetrics {
    val requestsByTrigger = requests.entries.groupingBy { it.key.dimensions.trigger }
        .fold(0L) { count, (_, row) -> count + row.requests }
    val actionsByTrigger = actions.entries.groupingBy { it.key.action to it.key.trigger }
        .fold(0L) { count, (_, value) -> count + value }
    return NetworkStatsMetrics(
        traffic = requests.entries.groupingBy { it.key.dimensions.target.traffic() }
            .fold(0L) { count, (_, row) -> count + row.requests },
        errors = requests.entries.groupingBy { it.key.status }
            .fold(0L) { count, (_, row) -> count + row.requests },
        unsent = noRequests.entries.groupingBy { it.key.dimensions.network }
            .fold(0L) { count, (_, value) -> count + value },
        transfers = requests.values.fold(NetworkRequestCounts(), NetworkRequestCounts::plus),
        peaks = peaks.entries.groupingBy { it.key.group }
            .fold(NetworkPeaks(0, 0, 0)) { max, (_, value) -> max.max(value) },
        actions = actions.entries.groupingBy { it.key.action }.fold(0L) { count, (_, value) -> count + value },
        flags = NetworkTrigger.entries.filter { it.flags != null }.associateWith { trigger ->
            FlagRefreshMetrics(
                actionsByTrigger[NetworkAction.FLAG_REFRESH_ADMITTED to trigger] ?: 0,
                actionsByTrigger[NetworkAction.FLAG_REFRESH_IGNORED to trigger] ?: 0,
                requestsByTrigger[trigger] ?: 0,
            )
        },
    )
}

private fun NetworkTarget.traffic(): NetworkStatsTraffic = when (this) {
    NetworkTarget.REST_CATALOGUE, NetworkTarget.REST_CATEGORY_PARTICIPATED, NetworkTarget.REST_CATEGORY_READ,
    NetworkTarget.REST_CATEGORY_FAVORITES, NetworkTarget.REST_CATEGORY_LAST, NetworkTarget.REST_SUBCATEGORIES,
    NetworkTarget.REST_GLOBAL_PARTICIPATED, NetworkTarget.REST_GLOBAL_READ, NetworkTarget.REST_GLOBAL_FAVORITES,
    NetworkTarget.REST_OTHER -> NetworkStatsTraffic.API
    NetworkTarget.HTML_FORUM1, NetworkTarget.HTML_FORUM2, NetworkTarget.HTML_MP_LIST, NetworkTarget.HTML_WRITE,
    NetworkTarget.HTML_OTHER, NetworkTarget.FAVORITE_SMILEYS -> NetworkStatsTraffic.PAGES
    NetworkTarget.FORUM_IMAGES -> NetworkStatsTraffic.IMAGES
    NetworkTarget.THIRD_PARTY_IMAGES, NetworkTarget.THIRD_PARTY_UPLOAD, NetworkTarget.OTHER_HOST ->
        NetworkStatsTraffic.THIRD_PARTIES
    NetworkTarget.PROBE -> NetworkStatsTraffic.PROBE
}

/** Builds the clipboard content from complete UTC day aggregates, using the same labels as the screen. */
internal fun Resources.formatNetworkStatsSummary(days: List<NetworkStatsDay>): String =
    days.sortedByDescending { it.dayUtc }.joinToString("\n\n") { day ->
        val dayMetrics = day.totals.toMetrics()
        buildList {
            add(day.dayUtc.toString())
            add(getString(R.string.network_stats_versions, day.totals.versionCodes().sorted().joinToString()))
            dayMetrics.sections(this@formatNetworkStatsSummary).forEach { section ->
                addAll(section.lines)
            }
        }.joinToString("\n")
    }

internal data class NetworkStatsSection(val title: Int, val lines: List<String>)

internal fun NetworkStatsMetrics.sections(resources: Resources) = listOf(
    NetworkStatsSection(R.string.network_stats_traffic, trafficLines(resources)),
    NetworkStatsSection(R.string.network_stats_peaks, peakLines(resources)),
    NetworkStatsSection(R.string.network_stats_flags, flagLines(resources)),
    NetworkStatsSection(R.string.network_stats_actions, actionLines(resources)),
)

private fun NetworkStatsMetrics.trafficLines(resources: Resources) = with(resources) {
    listOf(
        getString(R.string.network_stats_forum, apiRequests, pageRequests),
        getString(R.string.network_stats_forum_images, traffic[NetworkStatsTraffic.IMAGES] ?: 0L),
        getString(R.string.network_stats_third_parties, traffic[NetworkStatsTraffic.THIRD_PARTIES] ?: 0L),
        getString(R.string.network_stats_probe, traffic[NetworkStatsTraffic.PROBE] ?: 0L),
        getString(
            R.string.network_stats_errors, errors[NetworkStatus.FORBIDDEN] ?: 0L,
            errors[NetworkStatus.RATE_LIMITED] ?: 0L, errors[NetworkStatus.SERVER_ERROR] ?: 0L,
        ),
        getString(
            R.string.network_stats_unsent, unsent[NetworkKind.WIFI] ?: 0L, unsent[NetworkKind.CELLULAR] ?: 0L,
            unsent[NetworkKind.OTHER] ?: 0L, unsent[NetworkKind.UNKNOWN] ?: 0L,
        ),
        getString(R.string.network_stats_bytes, transfers.bodyBytesIn, transfers.bodyBytesOut),
        getString(R.string.network_stats_incomplete, transfers.incompleteBodies),
    )
}

private fun NetworkStatsMetrics.peakLines(resources: Resources) = NetworkPeakGroup.entries.map { group ->
    val peak = peaks[group] ?: NetworkPeaks(0, 0, 0)
    val label = when (group) {
        NetworkPeakGroup.FORUM -> R.string.network_stats_peak_forum
        NetworkPeakGroup.FORUM_IMAGES -> R.string.network_stats_peak_images
    }
    resources.getString(label, peak.perSecond, peak.perMinute, peak.inFlight)
}

private fun NetworkStatsMetrics.flagLines(resources: Resources) = with(resources) {
    buildList {
        add(getString(R.string.network_stats_flag_pulls, actions[NetworkAction.FLAG_PULL] ?: 0L))
        flagTriggerLabels.forEach { (trigger, label) ->
            val row = flags[trigger] ?: FlagRefreshMetrics(0, 0, 0)
            add(getString(
                R.string.network_stats_flag_row, getString(label), row.admitted, row.ignored,
                row.requests, ratio(row.requests, row.admitted),
            ))
        }
    }
}

private val flagTriggerLabels = mapOf(
    NetworkTrigger.LAUNCH to R.string.network_stats_trigger_launch,
    NetworkTrigger.RETURN_FROM_TOPIC to R.string.network_stats_trigger_return,
    NetworkTrigger.RESUME to R.string.network_stats_trigger_resume,
    NetworkTrigger.TAB to R.string.network_stats_trigger_tab,
    NetworkTrigger.PULL to R.string.network_stats_trigger_pull,
    NetworkTrigger.INITIAL_OBSERVE to R.string.network_stats_trigger_observe,
)

private fun NetworkStatsMetrics.actionLines(resources: Resources) = with(resources) {
    val pages = actions[NetworkAction.TOPIC_PAGE] ?: 0L
    listOf(
        getString(R.string.network_stats_topic_pages, pages),
        getString(R.string.network_stats_lists, actions[NetworkAction.LIST] ?: 0L),
        getString(R.string.network_stats_requests_per_page, ratio(apiRequests + pageRequests, pages)),
        getString(R.string.network_stats_writes, actions[NetworkAction.WRITE] ?: 0L),
        getString(
            R.string.network_stats_foreground, actions[NetworkAction.FOREGROUND_SESSION] ?: 0L,
            actions[NetworkAction.FOREGROUND_SECONDS] ?: 0L,
        ),
    )
}

private val NetworkStatsMetrics.apiRequests get() = traffic[NetworkStatsTraffic.API] ?: 0L
private val NetworkStatsMetrics.pageRequests get() = traffic[NetworkStatsTraffic.PAGES] ?: 0L

private fun Resources.ratio(numerator: Long, denominator: Long): String = if (denominator == 0L) {
    getString(R.string.network_stats_no_ratio)
} else {
    getString(R.string.network_stats_ratio, numerator.toDouble() / denominator)
}
