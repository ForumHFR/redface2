package fr.forumhfr.redface2.core.data.stats

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.database.stats.NetworkActionEntity
import fr.forumhfr.redface2.core.database.stats.NetworkDimensionsColumns
import fr.forumhfr.redface2.core.database.stats.NetworkPeakEntity
import fr.forumhfr.redface2.core.database.stats.NetworkRequestEntity
import fr.forumhfr.redface2.core.database.stats.NetworkStatsDatabase
import fr.forumhfr.redface2.core.database.stats.NetworkStatsRows
import fr.forumhfr.redface2.core.database.stats.NoRequestEntity
import fr.forumhfr.redface2.core.domain.diagnostics.AppVisibility
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkKind
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionKey
import fr.forumhfr.redface2.core.domain.stats.NetworkDimensions
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakGroup
import fr.forumhfr.redface2.core.domain.stats.NetworkPeakKey
import fr.forumhfr.redface2.core.domain.stats.NetworkPeaks
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestCounts
import fr.forumhfr.redface2.core.domain.stats.NetworkRequestKey
import fr.forumhfr.redface2.core.domain.stats.NetworkRole
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsStart.Companion.SECONDS_PER_HOUR
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsReport
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import fr.forumhfr.redface2.core.domain.stats.NetworkStatus
import fr.forumhfr.redface2.core.domain.stats.NetworkTarget
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.domain.stats.NoRequestKey
import fr.forumhfr.redface2.core.domain.stats.NoRequestReason
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

internal interface NetworkStatsStore {
    suspend fun merge(hours: Map<Long, NetworkStatsTotals>)
    suspend fun purge(beforeHour: Long)
    suspend fun read(fromHour: Long, untilHour: Long): NetworkStatsReport
    suspend fun reset()
}

internal class RoomNetworkStatsStore @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkStatsStore {
    private val dao by lazy { NetworkStatsDatabase.open(context).statsDao() }

    override suspend fun merge(hours: Map<Long, NetworkStatsTotals>) {
        val requests = mutableListOf<NetworkRequestEntity>()
        val noRequests = mutableListOf<NoRequestEntity>()
        val peaks = mutableListOf<NetworkPeakEntity>()
        val actions = mutableListOf<NetworkActionEntity>()
        hours.forEach { (hour, totals) ->
            totals.requests.forEach { (key, counts) ->
                requests += NetworkRequestEntity(key.dimensions.columns(hour), key.status.name, counts.requests,
                    counts.bodyBytesIn, counts.bodyBytesOut, counts.incompleteBodies)
            }
            totals.noRequests.forEach { (key, count) ->
                noRequests += NoRequestEntity(key.dimensions.columns(hour), key.reason.name, count)
            }
            totals.peaks.forEach { (key, peak) ->
                peaks += NetworkPeakEntity(hour, key.versionCode, key.group.name, peak.perSecond, peak.perMinute,
                    peak.inFlight)
            }
            totals.actions.forEach { (key, count) ->
                actions += NetworkActionEntity(hour, key.versionCode, key.action.name, key.trigger.name, count)
            }
        }
        dao.merge(NetworkStatsRows(requests, noRequests, peaks, actions))
    }

    override suspend fun purge(beforeHour: Long) {
        dao.purge(beforeHour)
    }

    override suspend fun reset() {
        dao.reset()
    }

    override suspend fun read(fromHour: Long, untilHour: Long): NetworkStatsReport {
        val rows = dao.readDays(fromHour, untilHour)
        val days = mutableMapOf<Long, TotalsBuilder>()
        val period = TotalsBuilder()
        rows.requests.forEach { row ->
            val key = NetworkRequestKey(row.dimensions.toDomain(), NetworkStatus.valueOf(row.status))
            val counts = NetworkRequestCounts(row.requests, row.bodyBytesIn, row.bodyBytesOut, row.incompleteBodies)
            days.getOrPut(row.dimensions.hourUtc, ::TotalsBuilder).requests[key] = counts
            period.requests.merge(key, counts, NetworkRequestCounts::plus)
        }
        rows.noRequests.forEach { row ->
            val key = NoRequestKey(row.dimensions.toDomain(), NoRequestReason.valueOf(row.reason))
            days.getOrPut(row.dimensions.hourUtc, ::TotalsBuilder).noRequests[key] = row.count
            period.noRequests.merge(key, row.count, Long::plus)
        }
        rows.peaks.forEach { row ->
            val key = NetworkPeakKey(row.versionCode, NetworkPeakGroup.valueOf(row.peakGroup))
            val peaks = NetworkPeaks(row.perSecond, row.perMinute, row.inFlight)
            days.getOrPut(row.hourUtc, ::TotalsBuilder).peaks[key] = peaks
            period.peaks.merge(key, peaks, NetworkPeaks::max)
        }
        rows.actions.forEach { row ->
            val key = NetworkActionKey(
                row.versionCode, NetworkAction.valueOf(row.action), NetworkTrigger.valueOf(row.trigger),
            )
            days.getOrPut(row.hourUtc, ::TotalsBuilder).actions[key] = row.count
            period.actions.merge(key, row.count, Long::plus)
        }
        return NetworkStatsReport(
            recordedSince = dao.firstHour()?.let { Instant.ofEpochSecond(it * SECONDS_PER_HOUR) },
            versions = dao.versions().toSet(),
            period = period.build(),
            days = days.toSortedMap(reverseOrder()).map { (hour, totals) ->
                NetworkStatsDay(LocalDate.ofEpochDay(hour / HOURS_PER_DAY), totals.build())
            },
        )
    }

    private fun NetworkDimensions.columns(hour: Long) =
        NetworkDimensionsColumns(hour, versionCode, target.name, role.name, visibility.name, trigger.name, network.name)

    private fun NetworkDimensionsColumns.toDomain() = NetworkDimensions(
        versionCode, NetworkTarget.valueOf(target), NetworkRole.valueOf(role), AppVisibility.valueOf(visibility),
        NetworkTrigger.valueOf(trigger), NetworkKind.valueOf(network),
    )

    private class TotalsBuilder {
        val requests = mutableMapOf<NetworkRequestKey, NetworkRequestCounts>()
        val noRequests = mutableMapOf<NoRequestKey, Long>()
        val peaks = mutableMapOf<NetworkPeakKey, NetworkPeaks>()
        val actions = mutableMapOf<NetworkActionKey, Long>()
        fun build() = NetworkStatsTotals(requests, noRequests, peaks, actions)
    }

    private companion object {
        const val HOURS_PER_DAY = 24L
    }
}
