package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class NetworkStatsPeriod(val duration: Duration) {
    DAY(Duration.ofHours(24)), WEEK(Duration.ofDays(7)), MONTH(Duration.ofDays(30)),
}

internal enum class NetworkStatsDialog { SUMMARY, RESET }

internal data class NetworkStatsUiState(
    val period: NetworkStatsPeriod = NetworkStatsPeriod.DAY,
    val loading: Boolean = true,
    val recordedSince: Instant? = null,
    val versions: Set<Int> = emptySet(),
    val metrics: NetworkStatsMetrics = NetworkStatsMetrics(),
    val history: List<NetworkStatsDay> = emptyList(),
    val historyDates: ClosedRange<LocalDate>? = null,
    val truncatedHistoryDay: LocalDate? = null,
    val dialog: NetworkStatsDialog? = null,
    val resetFailed: Boolean = false,
) {
    val summaryDays: List<NetworkStatsDay> =
        if (truncatedHistoryDay == null) history else history.filterNot { it.dayUtc == truncatedHistoryDay }
    val canCopy: Boolean get() = !loading && summaryDays.isNotEmpty()
    val canReset: Boolean get() = !loading && recordedSince != null
}

internal sealed interface NetworkStatsIntent {
    data class SelectPeriod(val period: NetworkStatsPeriod) : NetworkStatsIntent
    data object PreviewSummary : NetworkStatsIntent
    data object CopySummary : NetworkStatsIntent
    data object AskReset : NetworkStatsIntent
    data object Reset : NetworkStatsIntent
    data object DismissDialog : NetworkStatsIntent
}

internal sealed interface NetworkStatsEffect {
    data class CopySummary(val days: List<NetworkStatsDay>) : NetworkStatsEffect
}

@HiltViewModel
internal class NetworkStatsViewModel @Inject constructor(
    private val repository: NetworkStatsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkStatsUiState())
    val state = _state.asStateFlow()

    private val _effects = Channel<NetworkStatsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var resetJob: Job? = null

    init {
        selectPeriod(_state.value.period)
    }

    fun submit(intent: NetworkStatsIntent) {
        val current = _state.value
        when (intent) {
            is NetworkStatsIntent.SelectPeriod -> if (current.dialog == null && resetJob?.isActive != true) {
                selectPeriod(intent.period)
            }
            NetworkStatsIntent.PreviewSummary -> if (current.canCopy) {
                _state.value = current.copy(dialog = NetworkStatsDialog.SUMMARY)
            }
            NetworkStatsIntent.CopySummary -> if (current.dialog == NetworkStatsDialog.SUMMARY) {
                _effects.trySend(NetworkStatsEffect.CopySummary(current.summaryDays))
                _state.value = current.copy(dialog = null)
            }
            NetworkStatsIntent.AskReset -> if (current.canReset) {
                _state.value = current.copy(dialog = NetworkStatsDialog.RESET)
            }
            NetworkStatsIntent.Reset -> if (current.dialog == NetworkStatsDialog.RESET) reset(current)
            NetworkStatsIntent.DismissDialog -> _state.value = current.copy(dialog = null)
        }
    }

    private fun selectPeriod(period: NetworkStatsPeriod) {
        loadJob?.cancel()
        _state.value = _state.value.copy(period = period, loading = true, resetFailed = false)
        loadJob = viewModelScope.launch { load(period) }
    }

    private suspend fun load(period: NetworkStatsPeriod) {
        // Include the current UTC bucket and count backwards in whole stored hours.
        val currentHour = clock.instant().truncatedTo(ChronoUnit.HOURS)
        val until = currentHour.plus(1, ChronoUnit.HOURS)
        val historyStart = until.minus(NetworkStatsPeriod.MONTH.duration)
        val historyStartUtc = historyStart.atOffset(ZoneOffset.UTC)
        val firstHistoryDate = historyStartUtc.toLocalDate()
        val history = repository.read(historyStart, until)
        val selected = if (period == NetworkStatsPeriod.MONTH) history else {
            repository.read(until.minus(period.duration), until)
        }
        _state.value = _state.value.copy(
            loading = false,
            recordedSince = selected.recordedSince,
            versions = selected.versions,
            metrics = selected.period.toMetrics(),
            history = history.days,
            historyDates = firstHistoryDate..currentHour.atOffset(ZoneOffset.UTC).toLocalDate(),
            truncatedHistoryDay = firstHistoryDate.takeIf { historyStartUtc.hour != 0 },
        )
    }

    private fun reset(current: NetworkStatsUiState) {
        _state.value = current.copy(dialog = null, loading = true, resetFailed = false)
        resetJob = viewModelScope.launch {
            if (repository.reset()) {
                load(current.period)
            } else {
                _state.value = current.copy(dialog = null, resetFailed = true)
            }
        }
    }
}
