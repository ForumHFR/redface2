package fr.forumhfr.redface2

import fr.forumhfr.redface2.core.domain.diagnostics.CrashReportStack
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
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
        val thread = Thread("editor-main")
        val report = LastCrashReport(folder.root, environment, thread)
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
            "$environment\nthread=main\njava.lang.IllegalStateException: message omitted (14 chars)\n" +
                "\tat Editor.open(Editor.kt:19)\nCaused by: java.io.IOException: message omitted (13 chars)\n" +
                "\tat Parser.parse(Parser.kt:7)\n",
            capture.details,
        )
    }

    @Test
    fun `write failure still delegates and preserves the previous capture`() {
        val report = LastCrashReport(folder.root, environment)
        val thread = Thread.currentThread()
        capture(report, IllegalArgumentException("saved"))
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
        val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup())
        assertTrue(retained.details.contains("java.lang.IllegalArgumentException: message omitted (5 chars)"))
    }

    @Test
    fun `long messages retain only their length and the UTF-8 report is bounded`() {
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
        assertTrue(text.contains("message omitted (60011 chars)"))
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
            assertTrue(entry.message.contains("java.lang.IllegalArgumentException: message omitted (12 chars)"))
            assertFalse(entry.message.contains("java.lang.IllegalStateException"))
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
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleting = CountDownLatch(1)
        val deleted = CompletableFuture<Boolean>()
        val disk = Executors.newFixedThreadPool(2)
        val ui = Executors.newSingleThreadExecutor()
        val log = DiagnosticsLog().apply {
            record(DiagnosticsLog.Level.INFO, "HfrCall", "event before clear")
            registerCrashReportClear {
                disk.execute {
                    deleting.countDown()
                    deleted.complete(startup.clear())
                }
            }
        }
        try {
            val importing = disk.submit<LastCrashReport.Capture?> {
                startup.importStartup { file ->
                    // The report is open and the import still holds the file lock.
                    RandomAccessFile(file, "r").also {
                        reading.countDown()
                        release.await()
                    }
                }
            }
            assertTrue(reading.await(5, SECONDS))
            ui.submit {
                log.clear()
                assertTrue(log.entries.value.isEmpty())
            }.get(5, SECONDS)
            assertTrue(deleting.await(5, SECONDS))
            assertTrue(reportFile.exists())
            assertFalse(deleted.isDone)

            release.countDown()
            val pending = checkNotNull(importing.get(5, SECONDS))
            ui.submit {
                log.recordCrash(pending.timestampMillis, pending.details)
                assertTrue(log.entries.value.isEmpty())
            }.get(5, SECONDS)
            assertTrue(deleted.get(5, SECONDS))
        } finally {
            release.countDown()
            disk.shutdownNow()
            ui.shutdownNow()
        }
        assertNull(LastCrashReport(folder.root, environment).importStartup())
    }

    @Test
    fun `capture retains only allowlisted fields before startup and after import`() {
        val privateFields = listOf(
            "md_pass=deadbeef",
            "Proxy-Authorization: Basic ProbeProxyToken",
            "/mp/42/Alice",
            "https://forum.hardware.fr/mp/d'Alice/42",
            "Cookie: md_user=Alice",
            "/forum2.php?post=42&hash_check=Secret",
        )
        val privateText = privateFields.joinToString("\n")
        val approved = IllegalArgumentException("Failed requirement.").apply { stackTrace = emptyArray() }
        val cause = IOException(privateText, approved).apply { stackTrace = emptyArray() }
        val failure = IllegalStateException("Failed requirement. $privateText", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement("fr.forumhfr.Editor", "open", "Cookie.kt", 123456),
                StackTraceElement("Class/SecretClass", "open", "Editor.kt", 20),
                StackTraceElement("Editor", "method=SecretMethod", "Editor.kt", 21),
                StackTraceElement("loader", "module/SecretModule", "1", "Editor", "open", "Editor.kt", 22),
            ) + privateFields.map { StackTraceElement("Parser", "parse", it, 23) }
        }
        capture(LastCrashReport(folder.root, environment), failure, Thread(privateText))

        val original = reportFile.readBytes()
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)
        importCrash(startup, log)
        val message = log.entries.value.single().message
        val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup()).details

        val originalText = original.decodeToString()
        val fragments = listOf("deadbeef", "ProbeProxyToken", "Alice", "md_user", "hash_check", "Secret")
        (privateFields + fragments).forEach {
            assertFalse("Retained: $it", originalText.contains(it))
            assertFalse("Published: $it", message.contains(it))
        }
        assertTrue(original.contentEquals(reportFile.readBytes()))
        assertTrue(message.endsWith(retained.trimEnd()))
        assertEquals(
            "$environment\nthread=other\njava.lang.IllegalStateException: " +
                "message omitted (${failure.message!!.length} chars)\n" +
                "\tat fr.forumhfr.Editor.open(Cookie.kt:123456)\n\tat Editor.open(Editor.kt:22)\n" +
                "Caused by: java.io.IOException: message omitted (${privateText.length} chars)\n" +
                "Caused by: java.lang.IllegalArgumentException: Failed requirement.\n",
            retained,
        )
    }

    @Test
    fun `only exact standard library messages survive`() {
        val report = LastCrashReport(folder.root, environment)
        val missing = IllegalStateException()
        val suffixed = IllegalStateException("Check failed. ", missing)
        val required = IllegalArgumentException("Required value was null.", suffixed)
        val checked = IllegalStateException("Check failed.", required)
        listOf(checked, required, suffixed, missing).forEach { it.stackTrace = emptyArray() }

        capture(report, checked)

        val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup()).details
        assertEquals(
            "$environment\nthread=main\njava.lang.IllegalStateException: Check failed.\n" +
                "Caused by: java.lang.IllegalArgumentException: Required value was null.\n" +
                "Caused by: java.lang.IllegalStateException: message omitted (14 chars)\n" +
                "Caused by: java.lang.IllegalStateException: message omitted (0 chars)\n",
            retained,
        )
    }

    @Test
    fun `startup never publishes a legacy report with free text`() {
        DataOutputStream(reportFile.outputStream()).use {
            it.writeLong(0)
            it.writeBytes("java.lang.IllegalStateException: md_pass=deadbeef\n")
        }
        val startup = LastCrashReport(folder.root, environment)
        val log = startupLog(startup)

        importCrash(startup, log)

        assertTrue(log.entries.value.isEmpty())
        assertTrue(reportFile.isFile)
        log.clear()
        assertFalse(reportFile.exists())
    }

    @Test
    fun `a competing crash delegates while the first writer still holds the file lock`() {
        val report = LastCrashReport(folder.root, environment)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val competing = IllegalArgumentException("competing crash")
        val delegated = CountDownLatch(1)
        val failure = BlockingCrash(writing, release)
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
        assertTrue(retained.details.contains("message omitted (12 chars)"))
        assertFalse(retained.details.contains("java.lang.IllegalArgumentException"))
    }

    @Test
    fun `a worker crash terminates the process when there is no previous handler`() {
        val java = File(System.getProperty("java.home"), "bin/java")
        val main = CrashWithoutPreviousHandler::class.java
        val classpath = listOf(main, LastCrashReport::class.java, CrashReportStack::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct()
            .joinToString(File.pathSeparator)
        val child = ProcessBuilder(java.path, "-cp", classpath, main.name, folder.root.path).start()
        try {
            assertTrue(child.waitFor(10, SECONDS))
            assertEquals(child.errorStream.bufferedReader().readText(), 10, child.exitValue())
            val retained = checkNotNull(LastCrashReport(folder.root, environment).importStartup())
            assertTrue(retained.details.contains("thread=other"))
            assertTrue(retained.details.contains("java.lang.IllegalStateException: message omitted (13 chars)"))
        } finally {
            child.destroyForcibly()
        }
    }

    private fun capture(report: LastCrashReport, error: Throwable, thread: Thread = Thread.currentThread()) {
        report.handler { _, _ -> }.uncaughtException(thread, error)
    }

    private fun startupLog(report: LastCrashReport) = DiagnosticsLog().apply {
        registerCrashReportClear { assertTrue(report.clear()) }
    }

    private fun importCrash(report: LastCrashReport, log: DiagnosticsLog) {
        report.importStartup()?.let { log.recordCrash(it.timestampMillis, it.details) }
    }

    private class BlockingCrash(
        private val writing: CountDownLatch,
        private val release: CountDownLatch,
    ) : RuntimeException() {
        override val message: String
            get() {
                writing.countDown()
                release.await()
                return "first writer"
            }
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
