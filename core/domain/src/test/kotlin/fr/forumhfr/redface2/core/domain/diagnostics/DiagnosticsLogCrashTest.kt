package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
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

    @Test
    fun `clear confirms the deletion only when the scheduled work reports success`() {
        val log = DiagnosticsLog()
        val results = mutableListOf<(Boolean) -> Unit>()
        log.registerCrashReportClear { results += it }
        log.recordCrash(0, "editor failed")
        assertEquals(DiagnosticsLog.CrashReportClear.IDLE, log.crashReportClear.value.status)

        log.clear()

        assertTrue(log.entries.value.isEmpty())
        assertEquals(DiagnosticsLog.CrashReportClear.CLEARING, log.crashReportClear.value.status)
        results.single()(true)
        assertEquals(DiagnosticsLog.CrashReportClear.CLEARED, log.crashReportClear.value.status)
    }

    @Test
    fun `a failed deletion stays visible until a retry succeeds and keeps newer entries`() {
        val log = DiagnosticsLog()
        val results = mutableListOf<(Boolean) -> Unit>()
        log.registerCrashReportClear { results += it }

        log.clear()
        results.removeAt(0)(false)
        assertEquals(DiagnosticsLog.CrashReportClear.FAILED, log.crashReportClear.value.status)

        log.record(DiagnosticsLog.Level.INFO, "HfrCall", "event after clear")
        log.retryCrashReportClear()
        assertEquals(DiagnosticsLog.CrashReportClear.CLEARING, log.crashReportClear.value.status)
        // A second request while one is in flight is ignored.
        log.retryCrashReportClear()
        results.single()(true)

        assertEquals(DiagnosticsLog.CrashReportClear.CLEARED, log.crashReportClear.value.status)
        assertEquals("event after clear", log.entries.value.single().message)
    }

    @Test
    fun `an outcome checked before a second click cannot settle that click when it is published after`() {
        val log = DiagnosticsLog()
        val results = mutableListOf<(Boolean) -> Unit>()
        log.registerCrashReportClear { results += it }
        log.clear()

        // A's deletion ends and A checks that it is still the pending request.
        val checkedByA = log.crashReportClear.value
        assertEquals(DiagnosticsLog.CrashReportClear.CLEARING, checkedByA.status)
        // B clicks before A publishes: the status is CLEARING again, for another request.
        log.clear()
        val pendingB = log.crashReportClear.value
        assertEquals(DiagnosticsLog.CrashReportClearState(checkedByA.request + 1, checkedByA.status), pendingB)
        // A publishes what it checked.
        assertFalse(log.settleCrashReportClear(checkedByA, deleted = true))

        assertEquals(pendingB, log.crashReportClear.value)
        results[1](false)
        assertEquals(pendingB.copy(status = DiagnosticsLog.CrashReportClear.FAILED), log.crashReportClear.value)
        // A's own late callback changes nothing either.
        results[0](true)
        assertEquals(DiagnosticsLog.CrashReportClear.FAILED, log.crashReportClear.value.status)
    }

    @Test
    fun `unknown classes files and methods become anonymous and consecutive omissions are grouped`() {
        // JUnit stands for any class outside the known packages, such as a name shaped by user data.
        val known = "fr.forumhfr.redface2.core.network.CookieJar"
        val cause = AssumptionViolatedException("private").apply {
            stackTrace = arrayOf(StackTraceElement("Alice", "Alice", "Alice.kt", 1))
        }
        val failure = AssumptionViolatedException("private", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement(known, "load", "Cookie.kt", 42),
                StackTraceElement("Alice", "open", "Alice.kt", 1),
                StackTraceElement("com.example.Alice", "Alice", "Alice.java", 2),
                StackTraceElement(known, "load", "Alice.txt", 3),
                StackTraceElement(known, "load", "Alice", 4),
                StackTraceElement(known, "load", null, 5),
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
                StackTraceElement("javafake.Alice", "run", "Alice.kt", 6),
            )
        }
        val lines = mutableListOf<String>()
        val omittedMessage = "message omitted (${failure.message!!.length} chars)"

        CrashReportStack.write(Thread.currentThread(), Thread.currentThread(), failure) { lines.add(it) }

        assertEquals(
            listOf(
                "thread=main",
                "<omitted exception class>: $omittedMessage",
                "\tat $known.load(Cookie.kt:42)",
                "\tat <omitted frame> (x5)",
                "\tat java.lang.Thread.run(Thread.java:1012)",
                "\tat <omitted frame>",
                "Caused by: <omitted exception class>: $omittedMessage",
                "\tat <omitted frame>",
            ),
            lines,
        )
    }

    @Test
    fun `an exhausted sink stops the report while flushing omitted frames`() {
        val failure = IllegalStateException().apply {
            stackTrace = arrayOf(
                StackTraceElement("Alice", "open", "Alice.kt", 1),
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 1012),
            )
        }
        val lines = mutableListOf<String>()

        CrashReportStack.write(Thread.currentThread(), Thread.currentThread(), failure) {
            lines.add(it)
            lines.size < 3
        }

        assertEquals("\tat <omitted frame>", lines.last())
        assertEquals(3, lines.size)
    }
}
