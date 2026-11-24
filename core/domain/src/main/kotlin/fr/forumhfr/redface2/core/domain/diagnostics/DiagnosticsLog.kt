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
 * Ordinary recording is thread-safe through atomic [update]. Startup crash publication and
 * clearing run on the main thread, so a completed disk import cannot repopulate a cleared log.
 * The durable deletion of the crash report runs elsewhere and reports back through
 * [crashReportClear], which is the only confirmation the viewer may show.
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
    private var acceptsStartupCrash = true
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile private var submissionTraceSink: ((String) -> Unit)? = null
    private var scheduleCrashReportClear: ((onResult: (Boolean) -> Unit) -> Unit)? = null
    private val crashReportClearRequests = AtomicLong()
    private val _crashReportClear = MutableStateFlow(CrashReportClear.IDLE)

    /** Outcome of the latest durable deletion requested by [clear] or [retryCrashReportClear]. */
    val crashReportClear: StateFlow<CrashReportClear> = _crashReportClear.asStateFlow()

    /** Installed only by debug/dev app startup; prod/beta release keeps the volatile buffer. */
    fun exportSubmissionTraces(sink: (String) -> Unit) { submissionTraceSink = sink }

    /**
     * Registers the app's asynchronous disk deletion on the main thread before startup import.
     * [scheduleClear] must return at once and call its argument from any thread with the outcome.
     */
    fun registerCrashReportClear(scheduleClear: (onResult: (Boolean) -> Unit) -> Unit) {
        scheduleCrashReportClear = scheduleClear
    }

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

    /** Publishes a structured crash report on the main thread after its disk import. */
    fun recordCrash(timestampMillis: Long, details: String) {
        if (!acceptsStartupCrash) return
        acceptsStartupCrash = false
        val entry = Entry(
            id = sequence.incrementAndGet(),
            level = Level.ERROR,
            tag = CRASH_TAG,
            message = "crashedAt=${Instant.ofEpochMilli(timestampMillis)}\n$details".trimEnd(),
            timestampMillis = timestampMillis,
        )
        _entries.update { listOf(entry) + it.takeLast(CAPACITY - 1) }
    }

    /**
     * Clears the visible log on the main thread, then schedules the durable deletion. The crash
     * report is only gone once [crashReportClear] reaches [CrashReportClear.CLEARED]: if the process
     * dies during the few milliseconds of the deletion, the next start imports the report again.
     */
    fun clear() {
        acceptsStartupCrash = false
        _entries.value = emptyList()
        requestCrashReportClear()
    }

    /** Runs the durable deletion again after a failure, keeping the entries recorded since. */
    fun retryCrashReportClear() {
        if (_crashReportClear.value == CrashReportClear.FAILED) requestCrashReportClear()
    }

    private fun requestCrashReportClear() {
        val request = crashReportClearRequests.incrementAndGet()
        val schedule = scheduleCrashReportClear
        if (schedule == null) {
            // No report is kept by this process (tests, secondary process): nothing to wait for.
            _crashReportClear.value = CrashReportClear.CLEARED
            return
        }
        _crashReportClear.value = CrashReportClear.CLEARING
        schedule { deleted ->
            val outcome = if (deleted) CrashReportClear.CLEARED else CrashReportClear.FAILED
            // Only the latest request may settle the state; an older outcome is stale.
            _crashReportClear.update { if (crashReportClearRequests.get() == request) outcome else it }
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

    /** Durable deletion of the retained crash report, as shown next to the cleared log. */
    enum class CrashReportClear { IDLE, CLEARING, CLEARED, FAILED }

    companion object {
        const val CAPACITY = 200
        const val CRASH_TAG = "Crash"
    }
}
