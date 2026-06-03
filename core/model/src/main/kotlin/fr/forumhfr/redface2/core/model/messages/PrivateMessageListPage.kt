package fr.forumhfr.redface2.core.model.messages

/**
 * One page of the private-message inbox. HFR pages MPs at 50 per page, newest activity first.
 *
 * @property page 1-based index of this page.
 * @property totalPages total number of inbox pages (>= 1).
 * @property items conversations on this page, in HFR display order.
 */
data class PrivateMessageListPage(
    val page: Int,
    val totalPages: Int,
    val items: List<PrivateMessageSummary>,
)
