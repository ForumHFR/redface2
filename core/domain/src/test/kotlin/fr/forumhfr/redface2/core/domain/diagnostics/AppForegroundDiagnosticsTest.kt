package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundDiagnosticsTest {
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
