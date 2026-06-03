package fr.forumhfr.redface2.feature.messages

/**
 * Route arguments for [PrivateMessageThreadViewModel]. Plain data class so route values pass
 * through Hilt assisted injection, mirroring `TopicRequest` / `CategoryRequest`.
 *
 * Only opaque route data is carried here. The subject/correspondent are read from the fetched
 * `forum2.php?cat=prive` HTML so a stale Navigation entry cannot expose private metadata after
 * logout or process restore.
 */
data class PrivateMessageThreadRequest(
    val threadId: Int,
    val page: Int = 1,
)
