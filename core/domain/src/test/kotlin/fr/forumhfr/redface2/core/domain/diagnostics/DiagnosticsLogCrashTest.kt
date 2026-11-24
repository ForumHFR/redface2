package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogCrashTest {
    @Test
    fun `startup crash survives ring buffer turnover until cleared`() {
        val log = DiagnosticsLog()
        val capacity = DiagnosticsLog.CAPACITY
        repeat(capacity) { log.record(DiagnosticsLog.Level.INFO, "HfrCall", "startup $it") }
        log.recordCrash(0, "editor failed")
        repeat(capacity) { log.record(DiagnosticsLog.Level.INFO, "HfrCall", "event $it") }

        assertEquals(capacity, log.entries.value.size)
        assertEquals(DiagnosticsLog.CRASH_TAG, log.entries.value.first().tag)
        assertEquals("event 1", log.entries.value[1].message)
        assertEquals("event ${capacity - 1}", log.entries.value.last().message)
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun `clear discards a crash import still in flight`() {
        val log = DiagnosticsLog()
        log.clear()
        log.recordCrash(0, "editor failed")
        log.record(DiagnosticsLog.Level.INFO, "HfrCall", "event after clear")

        assertEquals("event after clear", log.entries.value.single().message)
    }
}
