package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.write.PostSubmitOperation

/**
 * Opaque, in-memory handoff from a successful MP reply or edit to the retained conversation.
 * [eventId] makes consumption idempotent; [page] is the originating page to force-refetch.
 * [operation] distinguishes an edit acknowledgement from a reply, without any feature dependency.
 */
data class PrivateMessageSubmitResult(
    val eventId: Long,
    val page: Int,
    val operation: PostSubmitOperation = PostSubmitOperation.Publish,
) {
    init {
        require(eventId > 0) { "Private-message submit event id must be positive" }
        require(page > 0) { "Private-message submit page must be positive" }
    }
}
