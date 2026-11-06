package fr.forumhfr.redface2.core.domain.editor

/** ED-162/170: failure of a preparatory form GET (including quote materialization), before any POST. */
class EditorPreparationException(val reason: Reason) : Exception() {
    enum class Reason { NETWORK, SESSION_EXPIRED, INVALID_FORM, QUOTE_FETCH }
}
