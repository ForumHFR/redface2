package fr.forumhfr.redface2.feature.messages

/**
 * Route arguments for [PrivateMessageThreadViewModel]. Plain data class so route values pass
 * through Hilt assisted injection, mirroring `TopicRequest` / `CategoryRequest`.
 *
 * [correspondent] and [subject] are carried from the inbox row so the thread screen can show a
 * meaningful title immediately (and so the thread parser has an authoritative correspondent
 * fallback when the page alone cannot reveal it — the user is the only sender so far).
 */
data class PrivateMessageThreadRequest(
    val threadId: Int,
    val correspondent: String,
    val subject: String,
    val page: Int = 1,
)
