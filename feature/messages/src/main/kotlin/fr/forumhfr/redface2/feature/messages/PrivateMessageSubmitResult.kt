package fr.forumhfr.redface2.feature.messages

/**
 * #1040 lot 6 — opaque, in-memory handoff from a successful MP reply editor to the retained
 * conversation ViewModel. [eventId] makes consumption idempotent; [page] is the reply context page
 * that must be force-refetched after the editor pops.
 */
data class PrivateMessageSubmitResult(
    val eventId: Long,
    val page: Int,
) {
    init {
        require(eventId > 0) { "Private-message submit event id must be positive" }
        require(page > 0) { "Private-message submit page must be positive" }
    }
}
