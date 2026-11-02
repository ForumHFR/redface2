package fr.forumhfr.redface2.core.domain.editor

/** ED-162/170: failure of a quote-form GET before entry into the write repository. */
class EditorPreparationException(val reason: Reason) : Exception() {
    enum class Reason { NETWORK, SESSION_EXPIRED }
}
