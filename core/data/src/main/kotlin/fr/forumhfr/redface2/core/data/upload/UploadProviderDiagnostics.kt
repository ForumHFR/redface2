package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticRedactor

/** #988 — shared provider diagnostics; never includes a complete filename or URL. */
internal object UploadProviderDiagnostics {

    fun request(filename: String, contentType: String?, byteCount: Int): String =
        "request filename_ext=${extensionOf(filename)} filename_length=${filename.length} " +
            "content_type=${contentType ?: "none"} bytes=$byteCount"

    fun responseTrace(
        code: Int,
        contentType: String?,
        startedAtNanos: Long,
        result: String,
    ): ProviderResponseTrace = ProviderResponseTrace(
        code = code,
        contentType = contentType,
        durationMs = elapsedMillis(startedAtNanos),
        result = result,
    )

    fun response(
        trace: ProviderResponseTrace,
        detail: String? = null,
        failureBody: String? = null,
    ): String = buildString {
        append("response code=")
        append(trace.code)
        append(" content_type=")
        append(trace.contentType ?: "none")
        append(" duration_ms=")
        append(trace.durationMs)
        append(" result=")
        append(trace.result)
        detail?.let {
            append(' ')
            append(it)
        }
        failureBody?.let {
            append(" body=")
            append(DiagnosticRedactor.redact(it, MAX_LOGGED_BODY))
        }
    }

    private fun extensionOf(filename: String): String = filename
        .substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { extension ->
            extension.length in 1..MAX_EXTENSION_LENGTH && extension.all(Char::isLetterOrDigit)
        }
        ?.lowercase()
        ?: "none"

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private const val MAX_EXTENSION_LENGTH = 16
    private const val MAX_LOGGED_BODY = 300
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

internal data class ProviderResponseTrace(
    val code: Int,
    val contentType: String?,
    val durationMs: Long,
    val result: String,
)
