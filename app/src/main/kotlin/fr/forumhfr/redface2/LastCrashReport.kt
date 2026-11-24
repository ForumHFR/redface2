package fr.forumhfr.redface2

import fr.forumhfr.redface2.core.domain.diagnostics.CrashReportRedactor
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The latest crash, kept privately until cleared or replaced by another crash. */
internal class LastCrashReport(directory: File, private val environment: String) {
    private val report = File(directory, "last-crash")
    private val temporary = File(directory, "last-crash.tmp")
    private val fileLock = ReentrantLock()
    private var skipStartupImport = false

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun handler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                write(thread, error)
            } catch (_: Throwable) {
                // Crash capture is best effort, including when the VM is low on memory.
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    // End the whole process, including for a worker crash, without shutdown hooks.
                    Runtime.getRuntime().halt(CRASH_EXIT_CODE)
                }
            }
        }

    private fun write(thread: Thread, error: Throwable) {
        if (fileLock.isHeldByCurrentThread || !fileLock.tryLock()) return
        try {
            skipStartupImport = true
            replaceReport(System.currentTimeMillis()) { writer ->
                writer.line(environment)
                writer.line("thread=${thread.name.reportField()}")
                writer.stack(error)
            }
        } finally {
            fileLock.unlock()
        }
    }

    private fun replaceReport(timestampMillis: Long, write: (ReportWriter) -> Unit) {
        try {
            DataOutputStream(temporary.outputStream()).use { output ->
                output.writeLong(timestampMillis)
                write(ReportWriter(output))
            }
            // Both files are siblings. On Android the rename atomically replaces the old report.
            if (!temporary.renameTo(report)) throw IOException("Could not replace the crash report")
        } finally {
            temporary.delete()
        }
    }

    fun importStartup(): Capture? = fileLock.withLock {
        // Import only once, and leave any crash from this process for the next one.
        if (skipStartupImport) return null
        skipStartupImport = true
        temporary.delete()
        val capture = read() ?: return null
        val redacted = CrashReportRedactor.redact(capture.details)
        if (redacted == capture.details) return capture
        try {
            replaceReport(capture.timestampMillis) { writer ->
                redacted.removeSuffix("\n").lineSequence().forEach(writer::line)
            }
            // Read back the committed text so the in-memory entry sees the same size limit.
            read()
        } catch (_: IOException) {
            null
        }
    }

    fun clear(): Boolean = fileLock.withLock {
        val temporaryDeleted = delete(temporary)
        val reportDeleted = delete(report)
        temporaryDeleted && reportDeleted
    }

    private fun delete(file: File): Boolean = try {
        Files.deleteIfExists(file.toPath())
        true
    } catch (_: IOException) {
        false
    }

    private fun read(): Capture? = try {
        RandomAccessFile(report, "r").use { input ->
            val length = input.length()
            if (length in (Long.SIZE_BYTES + 1L)..MAX_REPORT_BYTES.toLong()) {
                val timestamp = input.readLong()
                val details = ByteArray(length.toInt() - Long.SIZE_BYTES)
                input.readFully(details)
                Capture(timestamp, details.toString(Charsets.UTF_8))
            } else {
                null
            }
        }
    } catch (_: IOException) {
        null
    }

    data class Capture(val timestampMillis: Long, val details: String)

    private class ReportWriter(private val output: DataOutputStream) {
        private var remaining = MAX_REPORT_BYTES - Long.SIZE_BYTES - TRUNCATED.size

        fun line(text: String) {
            if (remaining <= 0) return
            val bytes = "$text\n".toByteArray(Charsets.UTF_8)
            if (bytes.size < remaining) {
                output.write(bytes)
                remaining -= bytes.size
            } else {
                output.write(TRUNCATED)
                remaining = 0
            }
        }

        fun stack(error: Throwable) {
            var current: Throwable? = error
            var depth = 0
            while (current != null && remaining > 0 && depth < MAX_THROWABLES) {
                val prefix = if (depth == 0) "" else "Caused by: "
                val message = current.message?.reportField()?.let { ": $it" }.orEmpty()
                line("$prefix${current.javaClass.name}$message")
                val frames = current.stackTrace
                for (index in 0 until minOf(frames.size, MAX_FRAMES)) {
                    if (remaining <= 0) return
                    line("\tat ${frames[index].toString().reportField()}")
                }
                if (frames.size > MAX_FRAMES) line("\t... ${frames.size - MAX_FRAMES} frames omitted")
                current = current.cause
                depth++
            }
            if (current != null) line("... further causes omitted")
        }
    }

    companion object {
        private const val CRASH_EXIT_CODE = 10
        private const val MAX_REPORT_BYTES = 16 * 1024
        private const val MAX_FIELD_CHARS = 512
        private const val MAX_THROWABLES = 8
        private const val MAX_FRAMES = 40
        private val TRUNCATED = "... crash report truncated\n".toByteArray(Charsets.UTF_8)

        private fun String.reportField(): String = take(MAX_FIELD_CHARS).replace('\n', ' ').replace('\r', ' ') +
            if (length > MAX_FIELD_CHARS) "..." else ""
    }
}
