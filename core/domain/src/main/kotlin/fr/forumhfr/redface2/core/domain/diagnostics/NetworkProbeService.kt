package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.error.HfrRestBlockedException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class NetworkProbeStatus {
    IDLE, RUNNING, COMPLETE, CANCELLED, REFRESH_ACTIVE, COOLDOWN, BLOCKED, STORAGE_ERROR, FAILED,
}

data class NetworkProbeState(
    val status: NetworkProbeStatus = NetworkProbeStatus.IDLE,
    val waveSize: Int? = null,
    val waves: List<NetworkProbeWave> = emptyList(),
) {
    val running: Boolean get() = status == NetworkProbeStatus.RUNNING
    val disturbed: Boolean get() = waves.any { it.disturbed }
}

/** One process-wide admission owner; persistent reservation survives screen and process recreation. */
@Singleton
class NetworkProbeService @Inject constructor(
    private val runner: NetworkProbeRunner,
    private val reservations: NetworkProbeReservationStore,
    private val activity: HfrRefreshActivity,
    private val log: DiagnosticsLog,
    private val restAvailability: HfrRestAvailability = HfrRestAvailability.Available,
) {
    private val execution = Mutex()
    private val mutableState = MutableStateFlow(NetworkProbeState())
    val state = mutableState.asStateFlow()
    val probeBlocked = restAvailability.probeBlocked

    suspend fun remainingSeconds(): Long = reservations.remainingSeconds()

    suspend fun run() {
        if (!execution.tryLock()) return
        try {
            restAvailability.checkProbeAllowed()
            runAdmitted()
        } catch (_: HfrRestBlockedException) {
            mutableState.value = NetworkProbeState(NetworkProbeStatus.BLOCKED)
        } catch (_: IOException) {
            mutableState.value = NetworkProbeState(NetworkProbeStatus.STORAGE_ERROR)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun runAdmitted() {
        val lease = activity.tryBeginProbe()
        if (lease == null) {
            mutableState.value = NetworkProbeState(NetworkProbeStatus.REFRESH_ACTIVE)
        } else {
            try {
                if (reservations.reserve() > 0) {
                    mutableState.value = NetworkProbeState(NetworkProbeStatus.COOLDOWN)
                } else {
                    execute()
                }
            } finally {
                lease.close()
            }
        }
    }

    private suspend fun execute() {
        mutableState.value = NetworkProbeState(NetworkProbeStatus.RUNNING)
        var terminal = NetworkProbeStatus.COMPLETE
        try {
            runner.run(
                onWaveStarted = { mutableState.value = mutableState.value.copy(waveSize = it) },
                onWaveFinished = ::waveFinished,
            )
        } catch (cancelled: CancellationException) {
            terminal = NetworkProbeStatus.CANCELLED
            throw cancelled
        } catch (_: HfrRestBlockedException) {
            terminal = NetworkProbeStatus.BLOCKED
        } catch (_: IOException) {
            terminal = NetworkProbeStatus.FAILED
        } finally {
            val snapshot = mutableState.value
            log.record(DiagnosticsLog.Level.INFO, TAG,
                "summary outcome=${terminal.name.lowercase()} waves=${snapshot.waves.size} " +
                    "requests=${snapshot.waves.sumOf { it.results.size }} disturbed=${snapshot.disturbed}")
            mutableState.value = snapshot.copy(status = terminal, waveSize = null)
        }
    }

    private fun waveFinished(wave: NetworkProbeWave) {
        mutableState.value = mutableState.value.copy(waves = mutableState.value.waves + wave)
        log.record(DiagnosticsLog.Level.INFO, TAG,
            "wave size=${wave.size} successes=${wave.successes} failures=${wave.failures} " +
                "medianConnectionMs=${wave.medianConnectionMs ?: "-"} disturbed=${wave.disturbed} " +
                "results=[${wave.results.joinToString(";") { it.line() }}] " +
                "disturbedBy=[${wave.disturbedBy.joinToString(",") { it.label }}]")
    }

    companion object { const val TAG = "NetworkProbe" }
}
