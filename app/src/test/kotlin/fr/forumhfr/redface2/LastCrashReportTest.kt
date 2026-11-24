package fr.forumhfr.redface2

import fr.forumhfr.redface2.core.domain.diagnostics.CrashReportRedactor
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
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
    private val reportFile get() = File(folder.root, "last-crash")

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
            assertTrue(reportFile.isFile)
        }.uncaughtException(thread, failure)

        assertEquals(1, calls)
        val capture = checkNotNull(LastCrashReport(folder.root, environment).importStartup())
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
        capture(report, IllegalStateException("saved"))
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
        assertTrue(checkNotNull(LastCrashReport(folder.root, environment).importStartup()).details.contains("saved"))
    }

    @Test
    fun `UTF-8 file and a long message are truncated`() {
        val report = LastCrashReport(folder.root, environment)
        val failure = IllegalStateException("é".repeat(60_000) + "message end").apply {
            stackTrace = Array(100) { index ->
                StackTraceElement("Frame${"界".repeat(140)}", "open", "Editor.kt", index)
            }
        }

        capture(report, failure)

        val bytes = reportFile.length()
        assertTrue("$bytes bytes", bytes in 1..16_384)
        val text = checkNotNull(LastCrashReport(folder.root, environment).importStartup()).details
        assertFalse(text.contains("message end"))
        assertTrue(text.endsWith("... crash report truncated\n"))
        assertTrue(text.contains("Editor.kt:0"))
        assertFalse(text.contains('\uFFFD'))
    }

    @Test
    fun `two restarts each show the latest crash once`() {
        val report = LastCrashReport(folder.root, environment)
        capture(report, IllegalStateException("first crash"))
        capture(report, IllegalArgumentException("latest crash"))

        assertNull(report.importStartup())
        repeat(2) {
            val startup = LastCrashReport(folder.root, environment)
            val log = startupLog(startup)
            importCrash(startup, log)
            importCrash(startup, log)
            assertNull(startup.importStartup())
            assertEquals(1, log.entries.value.size)
            val entry = log.entries.value.single()
            assertEquals(DiagnosticsLog.CRASH_TAG, entry.tag)
            assertTrue(entry.message.contains("latest crash"))
            assertFalse(entry.message.contains("first crash"))
            log.record(DiagnosticsLog.Level.INFO, "HfrCall", "volatile event")
        }
    }

    @Test
    fun `clear deletes the retained report and temporary file before the next restart`() {
        capture(LastCrashReport(folder.root, environment), IllegalStateException("editor failed"))
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)
        importCrash(startup, log)
        folder.newFile("last-crash.tmp")

        log.clear()

        assertTrue(log.entries.value.isEmpty())
        assertTrue(folder.root.list().orEmpty().isEmpty())
        assertNull(LastCrashReport(folder.root, environment).importStartup())
    }

    @Test
    fun `clear before startup import starts leaves nothing for either process to import`() {
        capture(LastCrashReport(folder.root, environment), IllegalStateException("editor failed"))
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)

        log.clear()
        importCrash(startup, log)

        assertTrue(log.entries.value.isEmpty())
        assertNull(LastCrashReport(folder.root, environment).importStartup())
    }

    @Test
    fun `clear during startup import prevents publication and clears the file for the next restart`() {
        capture(LastCrashReport(folder.root, environment), IllegalStateException("editor failed"))
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)
        // Pause the import after its disk phase but before its publication to the journal.
        val pending = checkNotNull(startup.importStartup())

        log.clear()
        log.recordCrash(pending.timestampMillis, pending.details)

        assertTrue(log.entries.value.isEmpty())
        assertNull(LastCrashReport(folder.root, environment).importStartup())
    }

    @Test
    fun `startup redacts the retained report before publishing it`() {
        val fields = mapOf(
            "Set-Cookie: session=SecretSession; other=SecretOther" to "SecretSession",
            "md_user=SecretUser" to "SecretUser",
            "md_id=SecretId" to "SecretId",
            "md_passs=SecretPass" to "SecretPass",
            "hash_check=SecretHash" to "SecretHash",
            "pseudo=SecretPseudo" to "SecretPseudo",
            "password=SecretPassword" to "SecretPassword",
            "/forum2.php?post=42&hash_check=SecretRelative" to "SecretRelative",
            "forum2.php?post=SecretQuery" to "SecretQuery",
            "https://example.com/SecretAbsolute" to "SecretAbsolute",
            "/data/user/0/SecretPath" to "SecretPath",
            """{"name":"SecretName"}""" to "SecretName",
            "identifier 7654321" to "7654321",
        )
        val failure = IllegalStateException("Cookie: md_user=Alice\n other=SecretFolded").apply {
            stackTrace = arrayOf(
                StackTraceElement("Editor", "open", "Editor.kt", 123456),
                StackTraceElement("Parser", "parse", "File7654321.kt", 987654),
            ) + fields.keys.map { StackTraceElement("Parser", "parse", it, -1) }
        }
        capture(LastCrashReport(folder.root, environment), failure)
        assertTrue(reportFile.readText().contains("Alice"))

        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)
        importCrash(startup, log)
        val retained = reportFile.readBytes().decodeToString(startIndex = Long.SIZE_BYTES)
        val message = log.entries.value.single().message

        (fields.values + listOf("Alice", "SecretFolded", "SecretOther", "?post=42", "Cookie:")).forEach {
            assertFalse("Retained: $it", retained.contains(it))
            assertFalse("Published: $it", message.contains(it))
        }
        assertTrue(message.endsWith(retained.trimEnd()))
        assertEquals(retained, checkNotNull(LastCrashReport(folder.root, environment).importStartup()).details)
        assertTrue(retained.contains("java.lang.IllegalStateException: <redacted>"))
        assertTrue(retained.contains("at Editor.open(Editor.kt:123456)"))
        assertTrue(retained.contains("at Parser.parse(File<n>.kt:987654)"))
    }

    @Test
    fun `startup only publishes after it can replace the raw file`() {
        capture(LastCrashReport(folder.root, environment), IllegalStateException("Cookie: session=Alice"))
        val blocked = folder.newFolder("last-crash.tmp")
        val child = File(blocked, "block-replacement").apply { createNewFile() }
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)

        importCrash(startup, log)

        assertTrue(log.entries.value.isEmpty())
        assertTrue(reportFile.readText().contains("Alice"))
        assertTrue(child.delete())
        assertTrue(blocked.delete())
        val next = LastCrashReport(folder.root, environment)
        assertTrue(checkNotNull(next.importStartup()).details.contains("IllegalStateException: <redacted>"))
        assertFalse(reportFile.readText().contains("Alice"))
    }

    @Test
    fun `a competing crash delegates while the first writer still holds the file lock`() {
        val report = LastCrashReport(folder.root, environment)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val competing = IllegalArgumentException("competing crash")
        val delegated = CountDownLatch(1)
        val failure = object : RuntimeException() {
            override val message: String
                get() {
                    writing.countDown()
                    release.await()
                    return "first writer"
                }
        }
        val handler = report.handler { _, error -> if (error === competing) delegated.countDown() }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { handler.uncaughtException(Thread.currentThread(), failure) }
            assertTrue(writing.await(5, SECONDS))
            val second = threads.submit { handler.uncaughtException(Thread.currentThread(), competing) }
            assertTrue(delegated.await(5, SECONDS))
            second.get(5, SECONDS)
            assertFalse(reportFile.exists())
            release.countDown()
            first.get(5, SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }

        val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup())
        assertTrue(retained.details.contains("first writer"))
    }

    @Test
    fun `a worker crash terminates the process when there is no previous handler`() {
        val java = File(System.getProperty("java.home"), "bin/java")
        val main = CrashWithoutPreviousHandler::class.java
        val classpath = listOf(main, LastCrashReport::class.java, CrashReportRedactor::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct()
            .joinToString(File.pathSeparator)
        val child = ProcessBuilder(java.path, "-cp", classpath, main.name, folder.root.path).start()
        try {
            assertTrue(child.waitFor(10, SECONDS))
            assertEquals(child.errorStream.bufferedReader().readText(), 10, child.exitValue())
            val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup())
            assertTrue(retained.details.contains("worker"))
        } finally {
            child.destroyForcibly()
        }
    }

    private fun capture(report: LastCrashReport, error: Throwable) {
        report.handler { _, _ -> }.uncaughtException(Thread.currentThread(), error)
    }

    private fun startupLog(report: LastCrashReport) = DiagnosticsLog().apply {
        registerCrashReportClear(report::clear)
    }

    private fun importCrash(report: LastCrashReport, log: DiagnosticsLog) {
        report.importStartup()?.let { log.recordCrash(it.timestampMillis, it.details) }
    }

    object CrashWithoutPreviousHandler {
        @JvmStatic
        fun main(args: Array<String>) {
            Thread.setDefaultUncaughtExceptionHandler(LastCrashReport(File(args.single()), "test").handler(null))
            val worker = Thread({ error("worker failed") }, "test-worker")
            worker.start()
            worker.join()
        }
    }
}
