package fr.forumhfr.redface2.core.domain.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
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
     * #313 — asks for a re-fetch of the unread count on the NEXT occasion (fire-and-forget,
     * non-suspending : safe from a lifecycle callback). No-op while the user is anonymous or
     * nobody collects [observeUnreadMpCount]. Caller : app-foreground (`ON_START`) so the
     * badge catches MPs received while the app was backgrounded.
     *
     * The count also refreshes for free whenever page 1 of the inbox is fetched through
     * [getPrivateMessageList] (the badge piggybacks on the Messages tab's own loads — no
     * second network call).
     */
    fun requestUnreadRefresh()

    /**
     * #453 — signals that conversation [threadId] was read in-app. Optimistically decrements the
     * observed unread count by one (clamped at zero), so reading the LAST unread conversation
     * clears the badge immediately instead of waiting for the next page-1 fetch. Fire-and-forget,
     * non-suspending (safe from a navigation callback) ; no-op while anonymous or while nobody
     * collects [observeUnreadMpCount].
     *
     * Local-only by design (zero network) : HFR has no server-side read flag (#361), so a read does
     * not change the server count — the decrement mirrors the inbox row that already marks the
     * conversation read locally, and the next real page-1 fetch reconciles. Marking the same thread
     * twice between two network refreshes decrements only once.
     */
    fun markThreadRead(threadId: Int)

    /**
     * Fetches one page of the private-message inbox (`forum1.php?cat=prive`). Throws on
     * network / session errors (e.g. [fr.forumhfr.redface2.core.domain.auth.SessionExpiredException]);
     * the caller maps the failure to its UI state.
     */
    suspend fun getPrivateMessageList(page: Int = 1): PrivateMessageListPage

    /**
     * Reads one page of a private-message conversation (`forum2.php?cat=prive&post={threadId}`).
     * A session-cache hit is emitted first with [PrivateMessageThreadPage.Source.SESSION_CACHE],
     * then the page is always revalidated and emitted from
     * [PrivateMessageThreadPage.Source.NETWORK]. No TTL skips that network request.
     *
     * @param fallbackCorrespondent optional caller-provided correspondent label, used only when
     *   the page alone cannot reveal it (the user is the only sender so far). UI Navigation routes
     *   must not carry it because it is private metadata that can outlive the session.
     *   Throws on network / session errors, like [getPrivateMessageList].
     */
    fun getPrivateMessageThread(
        threadId: Int,
        page: Int = 1,
        fallbackCorrespondent: String? = null,
    ): Flow<PrivateMessageThreadPage>

    /**
     * Authenticated, bounded prefetch of one private-conversation page (ADR-013 decision 3).
     * This is the sole exception to the project's anonymous-prefetch rule: callers may request
     * only an adjacent page of the conversation currently open in the foreground. Inbox/list
     * callers are forbidden because this GET clears the conversation's unread/read-receipt state.
     *
     * A page already present in the process-memory session cache is a no-op. A successful response
     * enters that same account/thread/page cache only after its parsed target and session stamp are
     * validated. Failures are best-effort and silent; caller cancellation always propagates.
     */
    suspend fun prefetchPrivateMessageThread(threadId: Int, page: Int)
}
