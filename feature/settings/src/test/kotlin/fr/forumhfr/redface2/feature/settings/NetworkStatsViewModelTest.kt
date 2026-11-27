package fr.forumhfr.redface2.feature.settings

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionKey
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsDay
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsReport
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsRepository
import fr.forumhfr.redface2.core.domain.stats.NetworkStatsTotals
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStatsViewModelTest {
    private val now = Instant.parse("2026-11-27T12:15:00Z")
    private val until = Instant.parse("2026-11-27T13:00:00Z")
    private val fromHistory = Instant.parse("2026-10-28T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dailyTotals = NetworkStatsTotals(actions = mapOf(NetworkActionKey(72, NetworkAction.TOPIC_PAGE) to 9L))
    private val periodTotals = NetworkStatsTotals(actions = mapOf(NetworkActionKey(72, NetworkAction.TOPIC_PAGE) to 2L))
    private val history = listOf(NetworkStatsDay(LocalDate.parse("2026-11-25"), dailyTotals))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `periods cover exact hourly buckets and export requires a preview`() = runTest {
        val repository = FakeRepository()
        val model = NetworkStatsViewModel(repository, clock)

        assertFalse(model.state.value.loading)
        assertEquals(NetworkStatsPeriod.DAY, model.state.value.period)
        for (period in NetworkStatsPeriod.entries) {
            val before = repository.ranges.size
            model.submit(NetworkStatsIntent.SelectPeriod(period))
            val historyRange = fromHistory to until
            val selectedRange = until.minus(period.duration) to until
            assertEquals(listOf(historyRange, selectedRange).distinct(), repository.ranges.drop(before))
            assertEquals(period.duration.toHours(), ChronoUnit.HOURS.between(selectedRange.first, selectedRange.second))
            val expected = if (period == NetworkStatsPeriod.MONTH) 9L else 2L
            assertEquals(expected, model.state.value.metrics.actions[NetworkAction.TOPIC_PAGE])
            assertEquals(setOf(71, 72), model.state.value.versions)
            assertEquals(history, model.state.value.history)
        }
        assertEquals(fromHistory to until, repository.ranges.last())
        assertEquals(LocalDate.parse("2026-10-28")..LocalDate.parse("2026-11-27"), model.state.value.historyDates)

        model.effects.test {
            model.submit(NetworkStatsIntent.CopySummary)
            expectNoEvents()
            model.submit(NetworkStatsIntent.PreviewSummary)
            assertEquals(NetworkStatsDialog.SUMMARY, model.state.value.dialog)
            expectNoEvents()
            model.submit(NetworkStatsIntent.CopySummary)
            assertEquals(NetworkStatsEffect.CopySummary(history), awaitItem())
            assertNull(model.state.value.dialog)
        }
    }

    @Test
    fun `export omits the oldest UTC date only if the history starts after midnight`() = runTest {
        for (hour in listOf(22, 23)) {
            val time = Instant.parse("2026-11-27T${hour}:15:00Z")
            val end = time.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
            val start = end.minus(Duration.ofHours(720))
            val oldest = NetworkStatsDay(start.atOffset(ZoneOffset.UTC).toLocalDate(), dailyTotals)
            val latest = NetworkStatsDay(LocalDate.parse("2026-11-27"), dailyTotals)
            val allDays = listOf(oldest, latest)
            val expectedExport = if (hour == 22) listOf(latest) else allDays
            val repository = FakeRepository(historyStart = start, days = allDays)
            val model = NetworkStatsViewModel(repository, Clock.fixed(time, ZoneOffset.UTC))
            model.submit(NetworkStatsIntent.SelectPeriod(NetworkStatsPeriod.MONTH))

            assertEquals(start to end, repository.ranges.last())
            assertEquals(allDays, model.state.value.history)
            assertEquals(oldest.dayUtc..latest.dayUtc, model.state.value.historyDates)
            model.effects.test {
                model.submit(NetworkStatsIntent.PreviewSummary)
                assertEquals(NetworkStatsDialog.SUMMARY, model.state.value.dialog)
                assertEquals(expectedExport, model.state.value.summaryDays)
                model.submit(NetworkStatsIntent.CopySummary)
                assertEquals(NetworkStatsEffect.CopySummary(expectedExport), awaitItem())
            }
        }
    }

    @Test
    fun `reset requires confirmation and reloads the selected period on success`() = runTest {
        val repository = FakeRepository()
        val model = NetworkStatsViewModel(repository, clock)
        model.submit(NetworkStatsIntent.SelectPeriod(NetworkStatsPeriod.WEEK))
        model.submit(NetworkStatsIntent.Reset)
        assertEquals(0, repository.resets)
        model.submit(NetworkStatsIntent.AskReset)
        assertEquals(NetworkStatsDialog.RESET, model.state.value.dialog)
        model.submit(NetworkStatsIntent.DismissDialog)
        assertEquals(0, repository.resets)
        model.submit(NetworkStatsIntent.AskReset)
        model.submit(NetworkStatsIntent.Reset)
        assertEquals(1, repository.resets)
        assertNull(model.state.value.recordedSince)
        assertTrue(model.state.value.history.isEmpty())
        assertEquals(NetworkStatsPeriod.WEEK, model.state.value.period)
        assertEquals(until.minus(Duration.ofDays(7)) to until, repository.ranges.last())
    }

    @Test
    fun `failed reset keeps the report and shows an error`() = runTest {
        val model = NetworkStatsViewModel(FakeRepository(resetSucceeds = false), clock)
        model.submit(NetworkStatsIntent.AskReset)
        model.submit(NetworkStatsIntent.Reset)
        assertEquals(history, model.state.value.history)
        assertTrue(model.state.value.resetFailed)
        assertNull(model.state.value.dialog)
    }

    private inner class FakeRepository(
        private val resetSucceeds: Boolean = true,
        private val historyStart: Instant = fromHistory,
        private val days: List<NetworkStatsDay> = history,
    ) : NetworkStatsRepository {
        val ranges = mutableListOf<Pair<Instant, Instant>>()
        var resets = 0

        override suspend fun read(from: Instant, until: Instant): NetworkStatsReport {
            ranges += from to until
            if (resets != 0 && resetSucceeds) return NetworkStatsReport()
            return NetworkStatsReport(
                recordedSince = fromHistory.minus(Duration.ofDays(7)),
                period = if (from == historyStart) dailyTotals else periodTotals,
                days = if (from == historyStart) days else emptyList(),
                versions = setOf(71, 72),
            )
        }

        override suspend fun reset(): Boolean {
            resets++
            return resetSucceeds
        }
    }
}
