package fr.forumhfr.redface2.core.domain.upload

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticRedactor

/** #988 — builds the bounded diagnostic shared by every image-upload ViewModel. */
object UploadFailureDiagnostics {

    fun describe(error: Throwable, mappedError: String): String {
        val exceptionClass = error::class.qualifiedName ?: error.javaClass.name
        val message = DiagnosticRedactor.redact(error.message ?: "none", MAX_MESSAGE_LENGTH)
        val causeDescription = error.cause?.let(::describeCause) ?: "none"
        val unmapped = when (error) {
            is UploadException.TooLarge,
            is UploadException.UnsupportedType,
            is UploadException.Server,
            is UploadException.Malformed,
            is UploadException.Configuration,
            is UploadException.Network,
            -> ""
            else -> " unmapped=true"
        }
        return "upload KO: $mappedError ex=$exceptionClass: $message cause=$causeDescription$unmapped"
    }

    private fun describeCause(cause: Throwable): String {
        val causeClass = cause::class.qualifiedName ?: cause.javaClass.name
        return DiagnosticRedactor.redact("$causeClass: ${cause.message ?: "none"}", MAX_MESSAGE_LENGTH)
    }

    private const val MAX_MESSAGE_LENGTH = 160
}
