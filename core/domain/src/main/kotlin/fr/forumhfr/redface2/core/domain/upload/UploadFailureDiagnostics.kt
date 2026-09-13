package fr.forumhfr.redface2.core.domain.upload

/** #988 — builds the bounded diagnostic shared by every image-upload ViewModel. */
object UploadFailureDiagnostics {

    fun describe(error: Throwable, mappedError: String): String {
        val exceptionClass = error::class.qualifiedName ?: error.javaClass.name
        val message = error.message?.take(MAX_MESSAGE_LENGTH) ?: "none"
        val causeClass = error.cause?.let { cause ->
            cause::class.qualifiedName ?: cause.javaClass.name
        } ?: "none"
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
        return "upload KO: $mappedError ex=$exceptionClass: $message cause=$causeClass$unmapped"
    }

    private const val MAX_MESSAGE_LENGTH = 160
}
