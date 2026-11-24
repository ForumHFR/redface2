package fr.forumhfr.redface2

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One private handoff file between the process that crashes and its next startup. */
internal class LastCrashReport(directory: File, private val environment: String) {
    private val report = File(directory, "last-crash")
    private val temporary = File(directory, "last-crash.tmp")
    private var crashStarted = false

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun handler(previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                write(thread, error)
            } catch (_: Throwable) {
                // Crash capture is best effort, including when the VM is low on memory.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }

    @Synchronized
    private fun write(thread: Thread, error: Throwable) {
        crashStarted = true
        try {
            DataOutputStream(temporary.outputStream()).use { output ->
                output.writeLong(System.currentTimeMillis())
                val writer = ReportWriter(output)
                writer.line(environment)
                writer.line("thread=${thread.name.reportField()}")
                writer.stack(error)
            }
            // Both files are siblings. On Android the rename atomically replaces the old report.
            temporary.renameTo(report)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun consume(): Capture? {
        // A background crash can race the startup import in this process.
        if (crashStarted) return null
        temporary.delete()
        return if (report.exists()) read().takeIf { report.delete() } else null
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
        private const val MAX_REPORT_BYTES = 16 * 1024
        private const val MAX_FIELD_CHARS = 512
        private const val MAX_THROWABLES = 8
        private const val MAX_FRAMES = 40
        private val TRUNCATED = "... crash report truncated\n".toByteArray(Charsets.UTF_8)

        private fun String.reportField(): String = take(MAX_FIELD_CHARS).replace('\n', ' ').replace('\r', ' ') +
            if (length > MAX_FIELD_CHARS) "..." else ""
    }
}
