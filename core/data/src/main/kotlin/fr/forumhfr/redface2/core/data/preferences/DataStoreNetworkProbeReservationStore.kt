package fr.forumhfr.redface2.core.data.preferences

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeReservationStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Device-wide, not scoped to an HFR session; cancelling never refunds the minute. */
@Singleton
class DataStoreNetworkProbeReservationStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val time: () -> ProbeReservationTime,
) : NetworkProbeReservationStore {
    @Inject constructor(
        @UserPreferencesDataStore dataStore: DataStore<Preferences>,
        @ApplicationContext context: Context,
    ) : this(dataStore, { readReservationTime(context) })

    override suspend fun remainingSeconds(): Long = remaining(dataStore.data.first(), time())

    override suspend fun reserve(): Long {
        var remaining = 0L
        dataStore.edit { preferences ->
            val now = time()
            remaining = remaining(preferences, now)
            if (remaining == 0L) {
                preferences[LAST_START_ELAPSED] = now.elapsedMillis
                preferences[LAST_START_BOOT] = now.bootCount
            }
        }
        return remaining
    }

    private fun remaining(preferences: Preferences, now: ProbeReservationTime): Long {
        val lastBoot = preferences[LAST_START_BOOT]
        // Across boots, conservatively wait until the new boot is a minute old. This cannot
        // shorten an existing reservation, even when the wall clock changed while powered off.
        val start = if (lastBoot == now.bootCount) preferences[LAST_START_ELAPSED] else 0L
        return if (lastBoot == null) 0 else NetworkProbeReservationStore.remainingSeconds(start, now.elapsedMillis)
    }

    private companion object {
        val LAST_START_ELAPSED = longPreferencesKey("network_probe_last_start_elapsed_ms")
        val LAST_START_BOOT = intPreferencesKey("network_probe_last_start_boot")
    }
}

internal data class ProbeReservationTime(val bootCount: Int, val elapsedMillis: Long)

private fun readReservationTime(context: Context): ProbeReservationTime {
    val boot = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (error: SecurityException) {
        throw IOException("Probe reservation clock unavailable", error)
    }
    if (boot < 0) throw IOException("Probe reservation clock unavailable")
    return ProbeReservationTime(boot, SystemClock.elapsedRealtime())
}
