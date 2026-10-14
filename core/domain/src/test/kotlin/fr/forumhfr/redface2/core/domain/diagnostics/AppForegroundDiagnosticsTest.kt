package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundDiagnosticsTest {
    @Test
    fun `cold start records foreground time and snapshot uses the injected boot clock`() {
        var now = 12_600_000_000L
        val log = DiagnosticsLog()
        val diagnostics = AppForegroundDiagnostics(log, DiagnosticsClock { now })
        assertNull(diagnostics.snapshot().foregroundS)
        diagnostics.foregrounded()
        assertEquals(DiagnosticsTimingSnapshot(12, 0), diagnostics.snapshot())
        now = 17_900_000_000L
        assertEquals(DiagnosticsTimingSnapshot(17, 5), diagnostics.snapshot())
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `resume resets foreground time but rotation and duplicate starts do not`() {
        var now = 10_000_000_000L
        val diagnostics = AppForegroundDiagnostics(DiagnosticsLog(), DiagnosticsClock { now })
        diagnostics.foregrounded()
        now = 20_000_000_000L
        diagnostics.backgrounded()
        now = 70_000_000_000L
        diagnostics.foregrounded()
        now = 72_999_000_000L
        val captured = diagnostics.snapshot()
        assertEquals(DiagnosticsTimingSnapshot(72, 2), captured)
        // MainActivity skips backgrounded on a configuration change, so the replacement's
        // foregrounded() keeps the original foreground start (no reset on rotation).
        now = 80_000_000_000L
        diagnostics.foregrounded()
        assertEquals(DiagnosticsTimingSnapshot(80, 10), diagnostics.snapshot())
        assertEquals(DiagnosticsTimingSnapshot(72, 2), captured)
    }

    @Test
    fun `cold start and duplicate starts are silent and warm resume uses monotonic time`() {
        val log = DiagnosticsLog()
        val diagnostics = AppForegroundDiagnostics(log)
        diagnostics.foregrounded(0)
        assertTrue(log.entries.value.isEmpty())
        diagnostics.backgrounded(1_000_000)
        diagnostics.foregrounded(51_000_000)
        diagnostics.foregrounded(52_000_000)
        assertEquals("backgroundMs=50", log.entries.value.single().message)
    }
}
