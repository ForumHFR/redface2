package fr.forumhfr.redface2

import fr.forumhfr.redface2.core.domain.diagnostics.CrashReportStack
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The latest crash, kept privately until cleared or replaced by another crash. */
internal class LastCrashReport(
    directory: File,
    private val environment: String,
    private val mainThread: Thread = Thread.currentThread(),
) {
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
                if (writer.line(environment)) CrashReportStack.write(thread, mainThread, error, writer::line)
            }
        } finally {
            fileLock.unlock()
        }
    }

    private fun replaceReport(timestampMillis: Long, write: (ReportWriter) -> Unit) {
        try {
            DataOutputStream(temporary.outputStream()).use { output ->
                output.writeInt(STRUCTURED_REPORT_MAGIC)
                output.writeLong(timestampMillis)
                write(ReportWriter(output))
            }
            // Both files are siblings. On Android the rename atomically replaces the old report.
            if (!temporary.renameTo(report)) throw IOException("Could not replace the crash report")
        } finally {
            temporary.delete()
        }
    }

    fun importStartup(
        openReport: (File) -> RandomAccessFile = { RandomAccessFile(it, "r") },
    ): Capture? = fileLock.withLock {
        // Import only once, and leave any crash from this process for the next one.
        if (skipStartupImport) return null
        skipStartupImport = true
        delete(temporary)
        read(openReport)
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
    } catch (_: SecurityException) {
        false
    }

    private fun read(openReport: (File) -> RandomAccessFile): Capture? = try {
        openReport(report).use { input ->
            val length = input.length()
            // Earlier formats allowed free text and cannot be imported.
            if (length in (HEADER_BYTES + 1L)..MAX_REPORT_BYTES.toLong() &&
                input.readInt() == STRUCTURED_REPORT_MAGIC
            ) {
                val timestamp = input.readLong()
                val details = ByteArray(length.toInt() - HEADER_BYTES)
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
        private var remaining = MAX_REPORT_BYTES - HEADER_BYTES - TRUNCATED.size

        fun line(text: String): Boolean {
            val bytes = "$text\n".toByteArray(Charsets.UTF_8)
            if (bytes.size < remaining) {
                output.write(bytes)
                remaining -= bytes.size
            } else {
                output.write(TRUNCATED)
                remaining = 0
            }
            return remaining > 0
        }
    }

    companion object {
        private const val CRASH_EXIT_CODE = 10
        private const val MAX_REPORT_BYTES = 16 * 1024
        private const val STRUCTURED_REPORT_MAGIC = 0x52464331 // RFC1
        private const val HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES
        private val TRUNCATED = "... crash report truncated\n".toByteArray(Charsets.UTF_8)
    }
}
