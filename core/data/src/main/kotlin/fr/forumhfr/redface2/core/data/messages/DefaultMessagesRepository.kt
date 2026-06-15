package fr.forumhfr.redface2.core.data.messages

import android.util.Log
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Phase 1B.1 bonus: surfaces the authenticated user's unread MP count on FlagsRoute as
 * a "really logged in" signal. Flow semantics:
 *
 * - When `AuthState` is `Anonymous` → emit `null` (no count to show).
 * - When `AuthState` flips to `Authenticated`, fetch `forum1.php?cat=prive` once and emit
 *   the parsed unread count. A failure emits `null` (the home screen renders nothing
 *   rather than display a stale or speculative count).
 * - On logout → emit `null` again on the next auth state change.
 *
 * Only the first page is fetched. An inbox with >50 MPs would need pagination, but the
 * legacy fixture confirms HFR pages MPs at 50/page and unread MPs are surfaced on page 1
 * (newest-first ordering) — sufficient for "is there anything new?" UX.
 */
@Singleton
class DefaultMessagesRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val hfrClient: HfrClient,
    private val parser: PrivateMessageListParser,
    private val threadParser: PrivateMessageThreadParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MessagesRepository {

    // #313 — two refresh channels beyond the auth flip. `unreadRefreshTicks` is the explicit
    // request (app-foreground) ; `inboxDerivedUnread` is the free piggyback : every page-1 inbox
    // fetch already carries the per-conversation dots, so the badge updates the moment the user
    // opens the Messages tab or comes back from a conversation, without a second network call.
    // tryEmit + extraBufferCapacity=1 : ticks may fire with no collector (badge disabled) — they
    // must never suspend or throw, and one pending tick is enough (they coalesce).
    // Piggyback emissions are SEALED to the session that started the fetch (pseudo snapshotted
    // at call-time) : the repository is a singleton, so a page-1 fetch started under account A
    // that lands after a logout/login to B must not feed B's badge with A's private metadata
    // (Codex review, PR #439). The collector filters on the pseudo of ITS auth session.
    private val unreadRefreshTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val inboxDerivedUnread = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)

    // #453 — optimistic local decrement. Reading the LAST unread conversation left the badge
    // stale until the next page-1 fetch (foreground / tab visit), because the count only ever
    // came from the network. A thread-read signal decrements the displayed count locally — zero
    // network, and consistent with the inbox row that already forces `hasUnread=false` for a read
    // thread (MessagesScreen). HFR has no server-side read flag (#361), so the server count would
    // not change just from reading anyway ; the next real page-1 fetch reconciles. Threads read
    // since the last network count are tracked so re-opening one never double-decrements, and a
    // fresh fetch clears the set (its count is authoritative). tryEmit + buffer: the signal may
    // fire with no collector (badge disabled, #452) and must never suspend.
    private val markThreadReadEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeUnreadMpCount(): Flow<Int?> = authRepository.observeAuthState()
        .transformLatest { state ->
            when (state) {
                AuthState.Anonymous -> emit(null)
                is AuthState.Authenticated -> emitAll(authenticatedUnreadCount(state))
            }
        }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    /**
     * Stream of unread counts for an authenticated [session] : the first fetch, then every network
     * refresh (foreground tick / page-1 piggyback), with #453 local read-decrements applied on top.
     * Each fresh network count is authoritative and resets the local decrements
     * ([LocalUnread.applyEvent] on a [NetworkCount]).
     */
    private fun authenticatedUnreadCount(session: AuthState.Authenticated): Flow<Int?> {
        val networkCounts = merge(
            unreadRefreshTicks.map { NetworkCount(fetchUnreadCount()) },
            inboxDerivedUnread.mapNotNull { (pseudo, count) ->
                // Drop emissions sealed for another session (account switch while the page-1
                // fetch was in flight).
                if (pseudo == session.pseudo) NetworkCount(count) else null
            },
        )
        // The initial fetch is its own first emission so reads marked before any refresh still
        // decrement it (otherwise the cold-start count would be a non-resettable baseline).
        return merge(
            flow { emit(NetworkCount(fetchUnreadCount())) },
            networkCounts,
            markThreadReadEvents.map { ReadThread(it) },
        )
            .scan(LocalUnread()) { acc, event -> acc.applyEvent(event) }
            // Drop the seed (no network count seen yet) ; emit every resolved state afterwards,
            // including a `null` from a failed fetch (the contract surfaces failures as null).
            .mapNotNull { folded ->
                if (folded.hasNetworkCount) Displayed(folded.displayedCount) else null
            }
            .map { it.value }
    }

    override fun requestUnreadRefresh() {
        unreadRefreshTicks.tryEmit(Unit)
    }

    override fun markThreadRead(threadId: Int) {
        markThreadReadEvents.tryEmit(threadId)
    }

    private suspend fun fetchUnreadCount(): Int? = withContext(ioDispatcher) {
        try {
            val html = hfrClient.getPrivateMessageListPage(page = 1)
            parser.countUnread(html)
        } catch (cancellation: CancellationException) {
            // Rethrow so transformLatest / collector cancellation propagates instead of being
            // logged as a "fetch failed" and swallowed into null (structured concurrency).
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // Surface fetch failures in logcat so a missing "MPs non lus" line in FlagsRoute's
            // footer can be debugged. Silent swallow would make a regression invisible (e.g.
            // HFR DOM change → parser returns 0 vs network failure → null). Logging on failure
            // only keeps the happy path quiet.
            Log.w(LOG_TAG, "Unread MP count fetch failed", error)
            null
        }
    }

    // Unlike observeUnreadMpCount (a best-effort footer signal that swallows failures into
    // null), the inbox list / thread reads PROPAGATE their errors: the Messages tab owns a real
    // error state with a retry, so a network or session failure must reach the ViewModel rather
    // than being hidden behind an empty screen. withContext(ioDispatcher) wraps the HfrClient
    // call per the repository contract (cf. NetworkOnMainThreadException regression, PR #162).
    override suspend fun getPrivateMessageList(page: Int): PrivateMessageListPage =
        withContext(ioDispatcher) {
            // Session snapshot at call-time, BEFORE the network call (same pattern as the flags
            // generation counter of #431) : tagging after the fetch could stamp account B's
            // pseudo on a response served under account A's cookies.
            val sessionPseudo = if (page == FIRST_PAGE) currentPseudo() else null
            val result = parser.parseList(hfrClient.getPrivateMessageListPage(page = page))
            if (page == FIRST_PAGE && sessionPseudo != null) {
                // #313 — page 1 is the same proxy fetchUnreadCount uses (newest-first, unread
                // float to the top), so its dots refresh the badge for free. Deeper pages are
                // NOT representative (their count would clobber a real one with a partial view).
                inboxDerivedUnread.tryEmit(sessionPseudo to result.items.count { it.hasUnread })
            }
            result
        }

    private suspend fun currentPseudo(): String? =
        (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo

    override suspend fun getPrivateMessageThread(
        threadId: Int,
        page: Int,
        fallbackCorrespondent: String?,
    ): PrivateMessageThread = withContext(ioDispatcher) {
        threadParser.parse(
            html = hfrClient.getPrivateMessageThreadPage(threadId = threadId, page = page),
            fallbackCorrespondent = fallbackCorrespondent,
        )
    }

    /** #453 — events folded by the unread-count [scan]. */
    private sealed interface UnreadEvent

    /** A fresh, authoritative count from the network (resets local read-decrements). */
    private data class NetworkCount(val count: Int?) : UnreadEvent

    /** A conversation was read in-app (decrements the displayed count by one, once). */
    private data class ReadThread(val threadId: Int) : UnreadEvent

    /**
     * #453 — fold state over [UnreadEvent]s. [baseCount] is the last network count ; [readThreadIds]
     * are the threads read since that count arrived. [displayedCount] subtracts them (clamped at 0).
     * [hasNetworkCount] tells the seed (no count yet) apart from a resolved `null` (failed fetch).
     */
    private data class LocalUnread(
        val baseCount: Int? = null,
        val readThreadIds: Set<Int> = emptySet(),
        val hasNetworkCount: Boolean = false,
    ) {
        val displayedCount: Int?
            get() = baseCount?.let { (it - readThreadIds.size).coerceAtLeast(0) }

        fun applyEvent(event: UnreadEvent): LocalUnread = when (event) {
            // A fresh network count is authoritative : reset the local read-decrement set.
            is NetworkCount -> LocalUnread(baseCount = event.count, readThreadIds = emptySet(), hasNetworkCount = true)
            is ReadThread -> copy(readThreadIds = readThreadIds + event.threadId)
        }
    }

    /** Wraps a resolved (possibly `null`) displayed count so [mapNotNull] only drops the scan seed. */
    private data class Displayed(val value: Int?)

    private companion object {
        const val LOG_TAG = "MessagesRepository"
        const val FIRST_PAGE = 1
    }
}
