package fr.forumhfr.redface2.core.model.messages

import java.time.Instant

/**
 * One entry of the user's private-message inbox (`forum1.php?cat=prive`). Each row is a
 * conversation ("thread") with a single [correspondent] — HFR models an MP exactly like a
 * topic, so [threadId] is the HFR `post` id used to open the thread on `forum2.php`.
 *
 * @property threadId HFR `post` id of the conversation (unique per `cat=prive`).
 * @property correspondent pseudo of the other participant, read from the listing row.
 * @property subject conversation subject as shown in the inbox.
 * @property date timestamp of the last activity in the conversation.
 * @property hasUnread `true` when the conversation holds a message the current user has not
 *   read yet (the `closedbp.gif` marker, vs `closedp.gif` for fully read). See
 *   [fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser].
 */
data class PrivateMessageSummary(
    val threadId: Int,
    val correspondent: String,
    val subject: String,
    val date: Instant,
    val hasUnread: Boolean,
)
