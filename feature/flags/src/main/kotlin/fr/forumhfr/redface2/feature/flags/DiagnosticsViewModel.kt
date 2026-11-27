package fr.forumhfr.redface2.feature.flags

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import fr.forumhfr.redface2.core.domain.flags.FlagsRefreshPolicy
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: DiagnosticsLog,
    private val probe: NetworkProbeService,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val entries = log.entries
    val policy = preferences.observeFlagsRefreshPolicy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlagsRefreshPolicy())
    var policySaveFailed by mutableStateOf(false)
        private set
    val probeState = probe.state
    val restBlockedUntil = probe.restBlockedUntil
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private val mutableCooldownSeconds = MutableStateFlow<Long?>(null)
    val cooldownSeconds = mutableCooldownSeconds.asStateFlow()
    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshCooldown()
                delay(1_000)
            }
        }
    }

    val crashReportClear = log.crashReportClear

    fun clear() = log.clear()

    fun retryCrashReportClear() = log.retryCrashReportClear()

    fun savePolicy(policy: FlagsRefreshPolicy, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                preferences.setFlagsRefreshPolicy(policy)
                policySaveFailed = false
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                policySaveFailed = true
            }
        }
    }

    fun startProbe() {
        if (probeJob?.isActive == true || probeState.value.running) return
        probeJob = viewModelScope.launch {
            try {
                probe.run()
            } finally {
                refreshCooldown()
            }
        }
    }

    fun cancelProbe() { probeJob?.cancel() }

    private suspend fun refreshCooldown() {
        mutableCooldownSeconds.value = try {
            probe.remainingSeconds()
        } catch (_: IOException) {
            null // Disable admission until persistence is readable again.
        }
    }
}
