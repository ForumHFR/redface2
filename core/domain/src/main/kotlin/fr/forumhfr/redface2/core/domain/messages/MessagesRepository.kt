package fr.forumhfr.redface2.core.domain.messages

import kotlinx.coroutines.flow.Flow

/**
 * Read-only access to the user's private messages metadata. Phase 1B.1 only exposes the
 * unread MP counter to give the home screen a "I'm really logged in to HFR" signal. The
 * full MP list / thread reading is deferred to Phase 1C.
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
}
