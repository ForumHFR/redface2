package fr.forumhfr.redface2.core.model.messages

import fr.forumhfr.redface2.core.model.Post

/**
 * One page of a private-message conversation (`forum2.php?cat=prive&post={threadId}`).
 *
 * An MP thread shares the exact HTML structure of a topic page, so its [messages] are the same
 * [Post] model parsed by the shared post extractor — only the surrounding metadata differs
 * (no category id, the page carries a [subject] instead of a topic title and a single
 * [correspondent]). The HFR `cat` of an MP page is the string `"prive"`, never an `Int`, which
 * is why MP threads use this dedicated model instead of
 * [fr.forumhfr.redface2.core.model.Topic].
 *
 * @property threadId HFR `post` id of the conversation.
 * @property subject conversation subject (the `h3` page title).
 * @property correspondent pseudo of the other participant. Best-effort from the thread page
 *   (the first message whose author is not the current user); the caller may override it with
 *   the authoritative value carried from the inbox row.
 * @property messages messages of this page, oldest first, as [Post].
 * @property page 1-based index of this page.
 * @property totalPages total number of pages in the conversation (>= 1).
 * @property canReply whether HFR rendered the `bddpost` reply form for the current session.
 */
data class PrivateMessageThread(
    val threadId: Int,
    val subject: String,
    val correspondent: String,
    val messages: List<Post>,
    val page: Int,
    val totalPages: Int,
    val canReply: Boolean = false,
)
