package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeService
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: DiagnosticsLog,
    private val probe: NetworkProbeService,
) : ViewModel() {
    val entries = log.entries
    val probeState = probe.state
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

    fun clear() = log.clear()

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
