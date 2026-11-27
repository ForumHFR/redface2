package fr.forumhfr.redface2.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.format.FormatStyle

@Composable
fun NetworkStatsScreen(onBack: () -> Unit) {
    val viewModel: NetworkStatsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel, context, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NetworkStatsEffect.CopySummary -> {
                    val summary = resources.formatNetworkStatsSummary(effect.days)
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(resources.getString(R.string.network_stats_title), summary),
                    )
                }
            }
        }
    }
    NetworkStatsContent(state, viewModel::submit, onBack)
}

@Composable
internal fun NetworkStatsContent(
    state: NetworkStatsUiState,
    onIntent: (NetworkStatsIntent) -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val sections = remember(resources, locale, state.metrics) { state.metrics.sections(resources) }
    val days = remember(state.historyDates, state.history) { state.historyRows() }
    val maxDay = days.maxOfOrNull(HistoryRow::requests)?.coerceAtLeast(1L) ?: 1L

    Scaffold(
        topBar = { SettingsSubPageTopBar(stringResource(R.string.network_stats_title), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { NetworkStatsHeader(state, dateFormat, onIntent) }
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else {
                items(sections, key = { it.title }) { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NetworkStatsHeading(stringResource(section.title))
                        section.lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(bottom = 12.dp))
                    NetworkStatsHeading(stringResource(R.string.network_stats_history))
                }
                items(days, key = { it.date.toEpochDay() }) { day ->
                    NetworkStatsHistoryRow(day, dateFormat, maxDay)
                }
            }
        }
    }
    NetworkStatsDialogs(state, onIntent)
}

@Composable
private fun NetworkStatsHeader(
    state: NetworkStatsUiState,
    dateFormat: DateTimeFormatter,
    onIntent: (NetworkStatsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val since = state.recordedSince
        if (since != null) {
            val date = dateFormat.format(since.atOffset(ZoneOffset.UTC))
            Text(stringResource(R.string.network_stats_since, date))
            if (state.versions.isNotEmpty()) {
                Text(stringResource(R.string.network_stats_versions, state.versions.sorted().joinToString()))
            }
        } else if (!state.loading) {
            Text(stringResource(R.string.network_stats_empty))
        }
        Text(stringResource(R.string.network_stats_local), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetworkStatsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.period == period,
                    onClick = { onIntent(NetworkStatsIntent.SelectPeriod(period)) },
                    label = { Text(stringResource(period.label)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onIntent(NetworkStatsIntent.PreviewSummary) },
                enabled = state.canCopy,
            ) { Text(stringResource(R.string.network_stats_copy)) }
            TextButton(
                onClick = { onIntent(NetworkStatsIntent.AskReset) },
                enabled = state.canReset,
            ) { Text(stringResource(R.string.network_stats_reset)) }
        }
        if (state.resetFailed) {
            Text(stringResource(R.string.network_stats_reset_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NetworkStatsDialogs(state: NetworkStatsUiState, onIntent: (NetworkStatsIntent) -> Unit) {
    val resources = LocalResources.current
    val dismiss = { onIntent(NetworkStatsIntent.DismissDialog) }
    when (state.dialog) {
        NetworkStatsDialog.SUMMARY -> {
            val summary = remember(resources, resources.configuration.locales[0], state.summaryDays) {
                resources.formatNetworkStatsSummary(state.summaryDays)
            }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.network_stats_preview)) },
                text = { Text(summary, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = {
                    TextButton(onClick = { onIntent(NetworkStatsIntent.CopySummary) }) {
                        Text(stringResource(R.string.network_stats_copy))
                    }
                },
                dismissButton = { NetworkStatsCancelButton(dismiss) },
            )
        }
        NetworkStatsDialog.RESET -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.network_stats_reset)) },
            text = { Text(stringResource(R.string.network_stats_reset_confirmation)) },
            confirmButton = {
                TextButton(onClick = { onIntent(NetworkStatsIntent.Reset) }) {
                    Text(stringResource(R.string.network_stats_reset))
                }
            },
            dismissButton = { NetworkStatsCancelButton(dismiss) },
        )
        null -> Unit
    }
}

@Composable
private fun NetworkStatsCancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.network_stats_cancel)) }
}

@Composable
private fun NetworkStatsHeading(title: String) {
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun NetworkStatsHistoryRow(day: HistoryRow, dateFormat: DateTimeFormatter, maxDay: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.network_stats_daily_requests, dateFormat.format(day.date), day.requests),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { day.requests.toFloat() / maxDay },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        if (day.versions.isNotEmpty()) {
            Text(
                stringResource(R.string.network_stats_versions, day.versions.sorted().joinToString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class HistoryRow(val date: LocalDate, val requests: Long, val versions: Set<Int>)

private fun NetworkStatsUiState.historyRows(): List<HistoryRow> {
    val dates = historyDates ?: return emptyList()
    val recorded = history.associateBy(NetworkStatsDay::dayUtc)
    val dayCount = ChronoUnit.DAYS.between(dates.start, dates.endInclusive).toInt() + 1
    return List(dayCount) { daysAgo ->
        val date = dates.endInclusive.minusDays(daysAgo.toLong())
        val totals = recorded[date]?.totals
        HistoryRow(date, totals?.forumRequests() ?: 0L, totals?.versionCodes().orEmpty())
    }
}

private val NetworkStatsPeriod.label: Int
    get() = when (this) {
        NetworkStatsPeriod.DAY -> R.string.network_stats_day
        NetworkStatsPeriod.WEEK -> R.string.network_stats_week
        NetworkStatsPeriod.MONTH -> R.string.network_stats_month
    }
