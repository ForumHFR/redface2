package fr.forumhfr.redface2.core.model.write

/**
 * Coordinates of an owned message under HFR's `cat=prive` (#1625).
 * [numreponse] is unique within the private category, not globally across HFR.
 * Contains no body or session token; the public [EditPostContext] stays unchanged.
 */
data class PrivateMessageEditContext(
    val threadId: Int,
    val page: Int,
    val numreponse: Int,
) {
    init {
        require(threadId > 0) { "threadId must be > 0" }
        require(page >= 1) { "page must be >= 1" }
        require(numreponse > 0) { "numreponse must be > 0" }
    }

    // Private coordinates must not escape through logs, exceptions or assertion messages.
    override fun toString(): String = "PrivateMessageEditContext([redacted])"
}
