package fr.forumhfr.redface2.core.ui.editor

import fr.forumhfr.redface2.core.domain.upload.UploadException
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason

/**
 * Error routing and successful-read resolution are surface-owned.
 * [resolvesReadError] receives only errors of the completed read kind and whether quotes were resolved.
 * Submission errors and ambiguous results are never retired by this policy.
 */
data class EditorErrorMappers(
    val isReadError: (EditorError) -> Boolean,
    val resolvesReadError: (EditorError, Boolean) -> Boolean,
    val uploadError: (EditorError) -> EditorError? = { it },
    val isUploadSuccessful: (EditorError?) -> Boolean = { it == null },
)

/** POST results remain domain values; an unrecognised result is always ambiguous. */
fun ReplyFailureReason.toEditorError(
    surface: EditorErrorSurface = EditorErrorSurface.Topic,
): EditorError = when (this) {
    ReplyFailureReason.EmptyMessage -> EditorErrors.empty
    ReplyFailureReason.InvalidHashCheck -> EditorErrors.invalidHashCheck
    ReplyFailureReason.AntiFlood -> EditorErrors.antiFlood
    ReplyFailureReason.LoginRequired -> EditorErrors.loginRequired
    ReplyFailureReason.UnknownRecipient -> if (surface == EditorErrorSurface.MessageReply) {
        EditorErrors.refused
    } else {
        EditorErrors.unknownRecipient
    }
    ReplyFailureReason.TopicLocked -> if (surface == EditorErrorSurface.Topic) {
        EditorErrors.topicLocked
    } else {
        EditorErrors.uncertain(surface)
    }
    ReplyFailureReason.Unknown -> EditorErrors.uncertain(surface)
}

/** Callers propagate CancellationException before mapping. No exception is stored in UI state. */
fun Throwable.toEditorUploadError(): EditorError = when (this) {
    is UploadException.TooLarge -> EditorErrors.tooLarge
    is UploadException.UnsupportedType -> EditorErrors.unsupportedType
    is UploadException.Server -> EditorErrors.server(code, providerId)
    is UploadException.Malformed -> EditorErrors.malformed(providerId)
    is UploadException.Configuration -> EditorErrors.configuration
    else -> EditorErrors.uploadNetwork
}
