package fr.forumhfr.redface2.core.database.stats

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

private const val UTC_DAY_START = "(hourUtc - (hourUtc % 24 + 24) % 24)"
private const val PERIOD = "hourUtc >= :fromHour AND hourUtc < :untilHour"
private const val DIMENSIONS = "versionCode, target, role, visibility, trigger"
private const val DIMENSION_KEY = "hourUtc = :hourUtc AND versionCode = :versionCode AND target = :target " +
    "AND role = :role AND visibility = :visibility AND trigger = :trigger"

@Dao
abstract class NetworkStatsDao {
    @Transaction
    open suspend fun merge(rows: NetworkStatsRows) {
        rows.requests.forEach { row ->
            if (insert(row) == -1L) {
                val key = row.dimensions
                addRequest(key.hourUtc, key.versionCode, key.target, key.role, key.visibility, key.trigger,
                    row.status, row.requests, row.bodyBytesIn, row.bodyBytesOut, row.incompleteBodies)
            }
        }
        rows.noRequests.forEach { row ->
            if (insert(row) == -1L) {
                val key = row.dimensions
                addNoRequest(key.hourUtc, key.versionCode, key.target, key.role, key.visibility, key.trigger,
                    row.reason, row.count)
            }
        }
        rows.peaks.forEach { row ->
            if (insert(row) == -1L) {
                maximize(row.hourUtc, row.versionCode, row.peakGroup, row.perSecond, row.perMinute, row.inFlight)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: NetworkRequestEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: NoRequestEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(row: NetworkPeakEntity): Long

    @Query(
        "UPDATE network_requests SET requests = requests + :requests, bodyBytesIn = bodyBytesIn + :bodyBytesIn, " +
            "bodyBytesOut = bodyBytesOut + :bodyBytesOut, incompleteBodies = incompleteBodies + :incompleteBodies " +
            "WHERE " + DIMENSION_KEY + " AND status = :status",
    )
    @Suppress("LongParameterList") // One named bind parameter per key column and counter.
    protected abstract suspend fun addRequest(
        hourUtc: Long, versionCode: Int, target: String, role: String, visibility: String, trigger: String,
        status: String, requests: Long, bodyBytesIn: Long, bodyBytesOut: Long, incompleteBodies: Long,
    )

    @Query("UPDATE network_no_requests SET count = count + :count WHERE " + DIMENSION_KEY + " AND reason = :reason")
    @Suppress("LongParameterList") // One named bind parameter per key column and counter.
    protected abstract suspend fun addNoRequest(
        hourUtc: Long, versionCode: Int, target: String, role: String, visibility: String, trigger: String,
        reason: String, count: Long,
    )

    @Query(
        "UPDATE network_peaks SET perSecond = MAX(perSecond, :perSecond), perMinute = MAX(perMinute, :perMinute), " +
            "inFlight = MAX(inFlight, :inFlight) WHERE hourUtc = :hourUtc AND versionCode = :versionCode " +
            "AND peakGroup = :peakGroup",
    )
    @Suppress("LongParameterList") // Room binds the key and each independent peak.
    protected abstract suspend fun maximize(
        hourUtc: Long, versionCode: Int, peakGroup: String, perSecond: Long, perMinute: Long, inFlight: Long,
    )

    @Transaction
    open suspend fun readDays(fromHour: Long, untilHour: Long): NetworkStatsRows =
        NetworkStatsRows(requestDays(fromHour, untilHour), noRequestDays(fromHour, untilHour),
            peakDays(fromHour, untilHour))

    @Query(
        "SELECT " + UTC_DAY_START + " AS hourUtc, " + DIMENSIONS + ", status, SUM(requests) AS requests, " +
            "SUM(bodyBytesIn) AS bodyBytesIn, SUM(bodyBytesOut) AS bodyBytesOut, " +
            "SUM(incompleteBodies) AS incompleteBodies FROM network_requests WHERE " + PERIOD +
            " GROUP BY " + UTC_DAY_START + ", " + DIMENSIONS + ", status",
    )
    protected abstract suspend fun requestDays(fromHour: Long, untilHour: Long): List<NetworkRequestEntity>

    @Query(
        "SELECT " + UTC_DAY_START + " AS hourUtc, " + DIMENSIONS + ", reason, SUM(count) AS count " +
            "FROM network_no_requests WHERE " + PERIOD +
            " GROUP BY " + UTC_DAY_START + ", " + DIMENSIONS + ", reason",
    )
    protected abstract suspend fun noRequestDays(fromHour: Long, untilHour: Long): List<NoRequestEntity>

    @Query(
        "SELECT " + UTC_DAY_START + " AS hourUtc, versionCode, peakGroup, MAX(perSecond) AS perSecond, " +
            "MAX(perMinute) AS perMinute, MAX(inFlight) AS inFlight FROM network_peaks WHERE " + PERIOD +
            " GROUP BY " + UTC_DAY_START + ", versionCode, peakGroup",
    )
    protected abstract suspend fun peakDays(fromHour: Long, untilHour: Long): List<NetworkPeakEntity>

    @Query(
        "SELECT MIN(firstHour) FROM (" +
            "SELECT MIN(hourUtc) AS firstHour FROM network_requests UNION ALL " +
            "SELECT MIN(hourUtc) FROM network_no_requests UNION ALL SELECT MIN(hourUtc) FROM network_peaks)",
    )
    abstract suspend fun firstHour(): Long?

    @Transaction
    open suspend fun purge(beforeHour: Long) {
        purgeRequests(beforeHour)
        purgeNoRequests(beforeHour)
        purgePeaks(beforeHour)
    }

    @Query("DELETE FROM network_requests WHERE hourUtc < :beforeHour")
    protected abstract suspend fun purgeRequests(beforeHour: Long)

    @Query("DELETE FROM network_no_requests WHERE hourUtc < :beforeHour")
    protected abstract suspend fun purgeNoRequests(beforeHour: Long)

    @Query("DELETE FROM network_peaks WHERE hourUtc < :beforeHour")
    protected abstract suspend fun purgePeaks(beforeHour: Long)

    @Transaction
    open suspend fun reset() {
        deleteRequests()
        deleteNoRequests()
        deletePeaks()
    }

    @Query("DELETE FROM network_requests")
    protected abstract suspend fun deleteRequests()

    @Query("DELETE FROM network_no_requests")
    protected abstract suspend fun deleteNoRequests()

    @Query("DELETE FROM network_peaks")
    protected abstract suspend fun deletePeaks()
}
