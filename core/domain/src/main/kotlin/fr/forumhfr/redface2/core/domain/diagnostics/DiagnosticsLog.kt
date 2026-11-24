package fr.forumhfr.redface2.core.domain.diagnostics

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory ring buffer of diagnostic events, exposed as a [StateFlow] so the in-app
 * viewer can render them live. Alpha-only crutch: contributors who don't have `adb`
 * on hand can read the same trail that lands in logcat without leaving the app.
 *
 * Thread-safety: backed by a single [MutableStateFlow]; atomic [update] is enough since
 * the buffer state is never read-then-write across coroutines.
 *
 * Volume: capped at [CAPACITY]. Older entries are dropped when full, except for the
 * previous process's crash report, which stays available until cleared or this process ends.
 * This buffer is never persisted.
 *
 * Identity: each entry carries a monotonic [Entry.id] so Compose lists can use stable
 * keys even when two identical messages land in the same millisecond.
 */
@Singleton
class DiagnosticsLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val sequence = AtomicLong()
    private val crashLock = Any()
    private var acceptsStartupCrash = true
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile private var submissionTraceSink: ((String) -> Unit)? = null
    @Volatile private var clearCrashReport: (() -> Boolean)? = null

    /** Installed only by debug/dev app startup; prod/beta release keeps the volatile buffer. */
    fun exportSubmissionTraces(sink: (String) -> Unit) { submissionTraceSink = sink }

    /** Registers the app's durable crash deletion before launching its startup import. */
    fun registerCrashReportClear(clear: () -> Boolean) { clearCrashReport = clear }

    fun record(level: Level, tag: String, message: String) {
        val entry = Entry(
            id = sequence.incrementAndGet(),
            level = level,
            tag = tag,
            message = message,
            timestampMillis = System.currentTimeMillis(),
        )
        if (tag == EditorSubmissionTrace.TAG) submissionTraceSink?.invoke(message)
        _entries.update { current ->
            if (current.size == CAPACITY && current.first().tag == CRASH_TAG) {
                buildList(CAPACITY) {
                    add(current.first())
                    addAll(current.subList(2, current.size))
                    add(entry)
                }
            } else {
                (current + entry).takeLast(CAPACITY)
            }
        }
    }

    fun recordCrash(timestampMillis: Long, details: String) {
        val redacted = CrashReportRedactor.redact(details)
        synchronized(crashLock) {
            if (acceptsStartupCrash) {
                acceptsStartupCrash = false
                val entry = Entry(
                    id = sequence.incrementAndGet(),
                    level = Level.ERROR,
                    tag = CRASH_TAG,
                    message = "crashedAt=${Instant.ofEpochMilli(timestampMillis)}\n$redacted".trimEnd(),
                    timestampMillis = timestampMillis,
                )
                _entries.update { listOf(entry) + it.takeLast(CAPACITY - 1) }
            }
        }
    }

    fun clear() = synchronized(crashLock) {
        // Startup releases the file lock before publishing, so pending imports cannot race clear.
        if (clearCrashReport?.invoke() != false) {
            acceptsStartupCrash = false
            _entries.value = emptyList()
        }
    }

    data class Entry(
        val id: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val timestampMillis: Long,
    )

    enum class Level { INFO, DEBUG, WARN, ERROR }

    companion object {
        const val CAPACITY = 200
        const val CRASH_TAG = "Crash"
    }
}
