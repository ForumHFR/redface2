package fr.forumhfr.redface2.core.domain.diagnostics

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
 * Volume: capped at [CAPACITY]. Older entries are dropped when full. Process death
 * resets the buffer — there is no persistence, by design (we don't want a tester's
 * device shipping a long-lived auth trace to disk).
 */
@Singleton
class DiagnosticsLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(level: Level, tag: String, message: String) {
        val entry = Entry(
            level = level,
            tag = tag,
            message = message,
            timestampMillis = System.currentTimeMillis(),
        )
        _entries.update { current ->
            (current + entry).takeLast(CAPACITY)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestampMillis: Long,
    )

    enum class Level { INFO, DEBUG, WARN, ERROR }

    companion object {
        const val CAPACITY = 200
    }
}
