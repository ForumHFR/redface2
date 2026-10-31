package fr.forumhfr.redface2.core.domain.diagnostics

/** Closed values only; UNKNOWN covers M3's shared callback, which does not expose the triggering event. */
enum class SmileyPickerCloseCause { INSERTION, CONTROLLER, SHEET_ACTION, UNKNOWN }

enum class SmileyDetailCloseCause { BACK, REPLACED, SHEET_CLOSED, REOPEN, ACCOUNT_CHANGED }

enum class SmileyKeywordsOutcome { LOADED, EMPTY, FAILED, NOT_APPLICABLE }

enum class SmileySheetPosition { HIDDEN, PARTIALLY_EXPANDED, EXPANDED }

/** One instance per editor controller; no token, query, URL, account or exception text. */
class SmileyPickerDiagnostics(
    private val log: DiagnosticsLog? = null,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var requestedCause: SmileyPickerCloseCause? = null

    fun sheetOpened() {
        requestedCause = null
        record("sheet_open")
    }

    fun dismissRequested(cause: SmileyPickerCloseCause) {
        requestedCause = cause
    }

    fun sheetClosed(cause: SmileyPickerCloseCause) {
        val actualCause = if (cause == SmileyPickerCloseCause.CONTROLLER) requestedCause ?: cause else cause
        record("sheet_close cause=${actualCause.name.lowercase()}")
        requestedCause = null
    }

    fun detailOpened(standard: Boolean) = record("detail_open kind=${if (standard) "standard" else "perso"}")

    fun detailClosed(cause: SmileyDetailCloseCause) = record("detail_close cause=${cause.name.lowercase()}")

    fun attached() = record("ui_attached")

    fun detached() = record("ui_detached")

    fun sheetState(current: SmileySheetPosition, target: SmileySheetPosition) =
        record("sheet_state current=${current.name.lowercase()} target=${target.name.lowercase()}")

    fun sheetHeight(heightDp: Int) = record("sheet_height dp=$heightDp")

    fun keywordsStarted(): Long = nowMillis()

    fun keywordsFinished(outcome: SmileyKeywordsOutcome, started: Long, cached: Boolean = false) {
        val duration = (nowMillis() - started).coerceAtLeast(0)
        record("keywords outcome=${outcome.name.lowercase()} durationMs=$duration cached=$cached")
    }

    private fun record(message: String) {
        log?.record(DiagnosticsLog.Level.INFO, TAG, message)
    }

    companion object {
        const val TAG = "SmileyPicker"
    }
}
