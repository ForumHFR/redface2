package fr.forumhfr.redface2.core.database.stats

import androidx.room.Embedded
import androidx.room.Entity

data class NetworkDimensionsColumns(
    val hourUtc: Long,
    val versionCode: Int,
    val target: String,
    val role: String,
    val visibility: String,
    val trigger: String,
)

@Entity(
    tableName = "network_requests",
    primaryKeys = ["hourUtc", "versionCode", "target", "role", "visibility", "trigger", "status"],
)
data class NetworkRequestEntity(
    @Embedded val dimensions: NetworkDimensionsColumns,
    val status: String,
    val requests: Long,
    val bodyBytesIn: Long,
    val bodyBytesOut: Long,
    val incompleteBodies: Long,
)

@Entity(
    tableName = "network_no_requests",
    primaryKeys = ["hourUtc", "versionCode", "target", "role", "visibility", "trigger", "reason"],
)
data class NoRequestEntity(
    @Embedded val dimensions: NetworkDimensionsColumns,
    val reason: String,
    val count: Long,
)

@Entity(tableName = "network_peaks", primaryKeys = ["hourUtc", "versionCode", "peakGroup"])
data class NetworkPeakEntity(
    val hourUtc: Long,
    val versionCode: Int,
    val peakGroup: String,
    val perSecond: Long,
    val perMinute: Long,
    val inFlight: Long,
)

data class NetworkStatsRows(
    val requests: List<NetworkRequestEntity>,
    val noRequests: List<NoRequestEntity>,
    val peaks: List<NetworkPeakEntity>,
)
