package fr.forumhfr.redface2

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LastCrashReportTest {
    @get:Rule val folder = TemporaryFolder()

    private val environment = "version=0.71.0 build=75 channel=beta\nAndroid API=34 manufacturer=Google model=Pixel"

    @Test
    fun `capture is written before the original handler receives the same thread and exception`() {
        val report = LastCrashReport(folder.root, environment)
        val thread = Thread("editor-main")
        val cause = IOException("cause message").apply {
            stackTrace = arrayOf(StackTraceElement("Parser", "parse", "Parser.kt", 7))
        }
        val failure = IllegalStateException("editor failure", cause).apply {
            stackTrace = arrayOf(StackTraceElement("Editor", "open", "Editor.kt", 19))
        }
        var calls = 0
        val before = System.currentTimeMillis()

        report.handler { receivedThread, receivedError ->
            calls++
            assertSame(thread, receivedThread)
            assertSame(failure, receivedError)
            assertTrue(File(folder.root, "last-crash").isFile)
        }.uncaughtException(thread, failure)

        assertEquals(1, calls)
        val capture = checkNotNull(LastCrashReport(folder.root, environment).consume())
        assertTrue(capture.timestampMillis in before..System.currentTimeMillis())
        assertEquals(
            "$environment\nthread=editor-main\njava.lang.IllegalStateException: editor failure\n" +
                "\tat Editor.open(Editor.kt:19)\nCaused by: java.io.IOException: cause message\n" +
                "\tat Parser.parse(Parser.kt:7)\n",
            capture.details,
        )
    }

    @Test
    fun `write failure still delegates and preserves the previous capture`() {
        val report = LastCrashReport(folder.root, environment)
        val thread = Thread.currentThread()
        report.handler(null).uncaughtException(thread, IllegalStateException("saved"))
        // A directory in place of the temporary file forces opening the output stream to fail.
        folder.newFolder("last-crash.tmp")
        val failure = IllegalStateException("write failed")
        var calls = 0

        report.handler { receivedThread, receivedError ->
            calls++
            assertSame(thread, receivedThread)
            assertSame(failure, receivedError)
        }.uncaughtException(thread, failure)

        assertEquals(1, calls)
        assertTrue(checkNotNull(LastCrashReport(folder.root, environment).consume()).details.contains("saved"))
    }

    @Test
    fun `UTF-8 file and a long message are truncated`() {
        val report = LastCrashReport(folder.root, environment)
        val failure = IllegalStateException("é".repeat(60_000) + "message end").apply {
            stackTrace = Array(100) { index ->
                StackTraceElement("Frame${"界".repeat(140)}", "open", "Editor.kt", index)
            }
        }

        report.handler(null).uncaughtException(Thread.currentThread(), failure)

        val bytes = File(folder.root, "last-crash").length()
        assertTrue("$bytes bytes", bytes in 1..16_384)
        val text = checkNotNull(LastCrashReport(folder.root, environment).consume()).details
        assertFalse(text.contains("message end"))
        assertTrue(text.endsWith("... crash report truncated\n"))
        assertTrue(text.contains("Editor.kt:0"))
        assertFalse(text.contains('\uFFFD'))
    }

    @Test
    fun `a fresh startup consumes only the last capture and deletes it`() {
        val report = LastCrashReport(folder.root, environment)
        val handler = report.handler(null)
        val thread = Thread.currentThread()
        handler.uncaughtException(thread, IllegalStateException("first crash"))
        handler.uncaughtException(thread, IllegalArgumentException("latest crash"))

        assertNull(report.consume())
        val capture = checkNotNull(LastCrashReport(folder.root, environment).consume())

        assertTrue(capture.details.contains("latest crash"))
        assertFalse(capture.details.contains("first crash"))
        assertTrue(folder.root.list().orEmpty().isEmpty())
        assertNull(LastCrashReport(folder.root, environment).consume())
    }
}
