package fr.forumhfr.redface2.core.domain.diagnostics

import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagRefreshEnvironmentTest {
    private var now = 0L
    private val clock = DiagnosticsClock { now }
    private val log = DiagnosticsLog()
    private val foreground = AppForegroundDiagnostics(log, clock)
    private val network = FakeNetwork()
    private val activity = HfrRefreshActivity()
    private val diagnostics = FlagRefreshDiagnostics(log, network, foreground, clock, activity)

    @Test
    fun `unknown start foreground and background transitions and end state use relative monotonic time`() {
        val trace = begin()
        assertTrue(log.entries.value.last().message.contains("foregroundStart=unknown"))
        at(10) { foreground.foregrounded() }
        at(270) { foreground.backgrounded() }
        at(280) { network.emit(NetworkDiagnosticsEventKind.LOST) }
        at(290) { foreground.foregrounded() }
        at(300) { trace.finish() }
        val summary = log.entries.value.last().message
        assertTrue(summary, summary.contains("foregroundStart=unknown foregroundEnd=foreground"))
        assertTrue(summary, summary.contains("foregroundTransitions=[10:foreground;270:background;290:foreground]"))
        assertTrue(summary, summary.contains("networkEvents=[280:lost transport=unknown validated=?]"))
        assertTrue(summary, summary.contains("dataSaver=enabled batterySaver=1"))
        assertEquals(1, network.closes)
        assertEquals(1L, activity.idleGeneration())
    }

    @Test
    fun `foreground start and background end remain distinct from the frozen initial timing`() {
        foreground.foregrounded()
        val trace = begin()
        at(2_550) { foreground.backgrounded() }
        trace.finish()
        val summary = log.entries.value.last().message
        assertTrue(summary, summary.contains("foregroundStart=foreground foregroundEnd=background"))
        assertTrue(summary, summary.contains("foregroundTransitions=[2550:background]"))
        assertTrue(summary, summary.endsWith("uptimeS=0 foregroundS=0"))
    }

    @Test
    fun `network timeline is capped at sixteen and closed traces ignore late callbacks`() {
        val trace = begin()
        repeat(20) { index -> at(index.toLong()) { network.emit(NetworkDiagnosticsEventKind.AVAILABLE) } }
        trace.finish()
        val summary = log.entries.value.last().message
        assertEquals(16, Regex(":available ").findAll(summary).count())
        assertTrue(summary, summary.contains("networkEventsOmitted=4"))
        assertFalse(summary.contains("16:available"))
        network.emit(NetworkDiagnosticsEventKind.LOST)
        foreground.foregrounded()
        trace.finish()
        assertEquals(summary, log.entries.value.last().message)
        assertEquals(1, network.closes)
    }

    @Test
    fun `foreground timeline is bounded too and duplicate lifecycle callbacks are silent`() {
        foreground.foregrounded()
        val trace = begin()
        repeat(10) {
            foreground.backgrounded()
            foreground.backgrounded()
            foreground.foregrounded()
            foreground.foregrounded()
        }
        trace.finish()
        val summary = log.entries.value.last().message
        assertTrue(summary, summary.contains("foregroundOmitted=4"))
        assertEquals(16, Regex("0:(foreground|background)").findAll(summary).count())
    }

    @Test
    fun `failed observer registration and close cannot leak errors or keep refresh activity alive`() {
        network.failObservation = true
        val first = begin()
        first.finish()
        network.failObservation = false
        network.failClose = true
        begin().finish()
        assertEquals(2L, activity.idleGeneration())
        assertFalse(log.entries.value.toString().contains("Secret"))
        assertTrue(log.entries.value.last().message.contains("foregroundEnd=unknown"))
    }

    private fun begin(): FlagRefreshTrace = diagnostics.begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))

    private fun at(ms: Long, action: () -> Unit) { now = ms * 1_000_000L; action() }

    private class FakeNetwork : NetworkDiagnosticsContextProvider {
        private var listener: ((NetworkDiagnosticsEvent) -> Unit)? = null
        var closes = 0
        var failObservation = false
        var failClose = false

        override fun snapshot(): NetworkDiagnosticsContext = NetworkDiagnosticsContext(setOf(NetworkTransport.WIFI))
        override fun restrictions(): NetworkDiagnosticsRestrictions =
            NetworkDiagnosticsRestrictions(DiagnosticsDataSaver.ENABLED, batterySaver = true)

        override fun observe(listener: (NetworkDiagnosticsEvent) -> Unit): DiagnosticsSubscription {
            check(!failObservation) { "Secret registration" }
            this.listener = listener
            return DiagnosticsSubscription {
                closes++
                check(!failClose) { "Secret close" }
            }
        }

        fun emit(kind: NetworkDiagnosticsEventKind) { listener?.invoke(NetworkDiagnosticsEvent(kind)) }
    }
}
