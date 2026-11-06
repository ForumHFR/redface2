package fr.forumhfr.redface2.core.domain.editor

/** ED-170/171: adapters retain their error vocabulary; infrastructure supplies no UI strings. */
data class EditorSubmissionErrors<F>(
    val uncertain: F,
    val draftWriteFailed: F,
    val sessionExpired: F,
    val unknown: F,
    val network: F = unknown,
    val preparationFailed: F = unknown,
    val quoteFetchFailed: F = network,
)
