package fr.forumhfr.redface2.core.domain.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread

/** One conversation page together with the source that produced this repository emission. */
data class PrivateMessageThreadPage(
    val thread: PrivateMessageThread,
    val source: Source,
) {
    enum class Source {
        SESSION_CACHE,
        NETWORK,
    }
}
