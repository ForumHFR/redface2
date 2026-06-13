package fr.forumhfr.redface2.core.model.messages

import java.time.Instant

/**
 * One entry of the user's private-message inbox (`forum1.php?cat=prive`). Each row is a
 * conversation ("thread"); [threadId] is the HFR `post` id used to open it on `forum2.php`.
 *
 * @property threadId HFR `post` id of the conversation (unique per `cat=prive`).
 * @property correspondent pseudo of the other participant for a one-to-one conversation. Empty
 *   when [isMultiRecipient] is `true` (HFR shows "Interlocuteurs multiples" instead of a single
 *   pseudo, and the participant list it exposes is truncated); the UI renders a localized label
 *   in that case.
 * @property subject conversation subject as shown in the inbox.
 * @property date timestamp of the last activity in the conversation.
 * @property hasUnread `true` when the conversation holds a message the current user has not
 *   read yet (the `closedbp.gif` marker, vs `closedp.gif` for fully read). See
 *   [fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser].
 * @property isMultiRecipient `true` for a multi-recipient conversation (MultiMP / "DT"): the
 *   `Interlocuteur` cell is a "Interlocuteurs multiples" label rather than a profile link.
 * @property lastPage number of the conversation's last page, read from the inbox "Pages" cell
 *   (`td.sujetCase4`). HFR only renders that link for conversations spanning several pages, so a
 *   single-page conversation stays at the default `1`. Web parity (#430): the web inbox subject
 *   link goes to page 1 and the trailing page number goes to the last page — the app uses
 *   [lastPage] as the opening page so a long conversation lands on its most recent messages.
 */
data class PrivateMessageSummary(
    val threadId: Int,
    val correspondent: String,
    val subject: String,
    val date: Instant,
    val hasUnread: Boolean,
    val isMultiRecipient: Boolean = false,
    val lastPage: Int = 1,
)
