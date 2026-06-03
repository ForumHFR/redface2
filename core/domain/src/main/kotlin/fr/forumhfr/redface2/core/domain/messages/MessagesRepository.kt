package fr.forumhfr.redface2.core.domain.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the user's private messages. The unread counter (Phase 1B.1) feeds the
 * home screen "I'm really logged in to HFR" signal; the inbox list + thread reading (#298)
 * back the dedicated Messages tab. All reads require an authenticated session.
 */
interface MessagesRepository {

    /**
     * Number of unread MPs for the currently authenticated user. Emits:
     * - `null` while the user is anonymous or while the first fetch is still in flight
     *   (consumers should render nothing in this state, same convention as `AuthState?`)
     * - a non-negative `Int` once a successful fetch has resolved
     *
     * The flow does not retry automatically on network errors; the caller is expected to
     * trigger a manual refresh if needed (Phase 1B.1 ships without refresh affordance).
     */
    fun observeUnreadMpCount(): Flow<Int?>

    /**
     * Fetches one page of the private-message inbox (`forum1.php?cat=prive`). Throws on
     * network / session errors (e.g. [fr.forumhfr.redface2.core.domain.auth.SessionExpiredException]);
     * the caller maps the failure to its UI state.
     */
    suspend fun getPrivateMessageList(page: Int = 1): PrivateMessageListPage

    /**
     * Fetches one page of a private-message conversation (`forum2.php?cat=prive&post={threadId}`).
     *
     * @param fallbackCorrespondent optional caller-provided correspondent label, used only when
     *   the page alone cannot reveal it (the user is the only sender so far). UI Navigation routes
     *   must not carry it because it is private metadata that can outlive the session.
     *   Throws on network / session errors, like [getPrivateMessageList].
     */
    suspend fun getPrivateMessageThread(
        threadId: Int,
        page: Int = 1,
        fallbackCorrespondent: String? = null,
    ): PrivateMessageThread
}
