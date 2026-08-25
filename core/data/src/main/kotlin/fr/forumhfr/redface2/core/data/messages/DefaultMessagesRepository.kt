package fr.forumhfr.redface2.core.data.messages

import android.util.Log
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageThreadPage
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import java.time.Instant
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
@Suppress("LongParameterList") // Auth/network/parser plus the two deliberately separate cache tiers.
class DefaultMessagesRepository @Inject internal constructor(
    private val authRepository: AuthRepository,
    private val hfrClient: HfrClient,
    private val parser: PrivateMessageListParser,
    private val threadParser: PrivateMessageThreadParser,
    private val threadSessionCache: PrivateMessageThreadSessionCache,
    private val privateMessageContentAccess: PrivateMessageContentAccess,
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
        // decrement it (otherwise the cold-start count would be a non-resettable baseline). It is
        // tagged `isInitial` so that, if a page-1 piggyback or a refresh tick wins the race to be the
        // first count, the late-landing cold-start is dropped rather than resetting the local reads
        // accumulated meanwhile (Codex review, #453).
        return merge(
            flow { emit(NetworkCount(fetchUnreadCount(), isInitial = true)) },
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
    // than being hidden behind an empty screen. withContext/flowOn(ioDispatcher) wrap HfrClient
    // calls per the repository contract (cf. NetworkOnMainThreadException regression, PR #162).
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

    override fun getPrivateMessageThread(
        threadId: Int,
        page: Int,
        fallbackCorrespondent: String?,
    ): Flow<PrivateMessageThreadPage> = flow {
        val owner = currentPseudo()
            ?: throw SessionExpiredException(REDACTED_PRIVATE_MESSAGE_URL)
        val stamp = threadSessionCache.capture(owner)
        var sessionCacheHit = false
        if (isCurrentSession(stamp)) {
            threadSessionCache.read(stamp, threadId, page)?.let { cached ->
                if (isCurrentSession(stamp)) {
                    sessionCacheHit = true
                    emit(PrivateMessageThreadPage(cached, PrivateMessageThreadPage.Source.SESSION_CACHE))
                }
            }
        }
        if (!sessionCacheHit) {
            readDiskPageOrNull(owner, threadId, page)?.let { cached ->
                if (isCurrentSession(stamp)) {
                    emit(PrivateMessageThreadPage(cached, PrivateMessageThreadPage.Source.DISK))
                }
            }
        }

        fetchAndCacheThreadPage(
            threadId = threadId,
            page = page,
            fallbackCorrespondent = fallbackCorrespondent,
            stamp = stamp,
            diskUserId = owner,
            persistToDisk = true,
        )?.let { parsed ->
            emit(PrivateMessageThreadPage(parsed, PrivateMessageThreadPage.Source.NETWORK))
        }
        // A stale stamp deliberately produces no terminal emission: turning the refusal into data
        // here would let a response owned by a previous generation cross the repository boundary.
        // The conversation ViewModel owns the empty-collection UI fallback and scopes it to the
        // account that started the load, so a real account switch stays a refusal.
    }.flowOn(ioDispatcher)

    override fun isPrivateMessageThreadPageWarm(account: String, threadId: Int, page: Int): Boolean {
        val stamp = threadSessionCache.capture(account)
        return threadSessionCache.contains(stamp, threadId, page)
    }

    override suspend fun prefetchPrivateMessageThread(threadId: Int, page: Int) =
        withContext(ioDispatcher) {
            try {
                val owner = currentPseudo()
                if (owner != null) {
                    val stamp = threadSessionCache.capture(owner)
                    if (isCurrentSession(stamp) && threadSessionCache.read(stamp, threadId, page) == null) {
                        fetchAndCacheThreadPage(
                            threadId = threadId,
                            page = page,
                            fallbackCorrespondent = null,
                            stamp = stamp,
                            diskUserId = owner,
                            persistToDisk = false,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                // Best-effort and deliberately silent (#316): HfrServerException carries the
                // private forum2.php URL. Never hand the raw throwable to Log or DiagnosticsLog.
            }
        }

    /**
     * Shared terminal-network path for visible reads and prefetch. The parsed target is validated
     * once, immediately before the cache write; the account/generation stamp is then rechecked
     * again before the caller may use the response.
     */
    private suspend fun fetchAndCacheThreadPage(
        threadId: Int,
        page: Int,
        fallbackCorrespondent: String?,
        stamp: PrivateMessageThreadSessionCache.Stamp,
        diskUserId: String,
        persistToDisk: Boolean,
    ): PrivateMessageThread? {
        val parsed = threadParser.parse(
            html = hfrClient.getPrivateMessageThreadPage(threadId = threadId, page = page),
            fallbackCorrespondent = fallbackCorrespondent,
        )
        if (parsed.matchesTarget(threadId, page) && isCurrentSession(stamp)) {
            threadSessionCache.write(stamp, threadId, page, parsed)
            if (persistToDisk) persistTerminalPage(diskUserId, parsed, stamp)
        }
        return parsed.takeIf { isCurrentSession(stamp) }
    }

    private suspend fun readDiskPageOrNull(
        userId: String,
        threadId: Int,
        page: Int,
    ): PrivateMessageThread? = try {
        privateMessageContentAccess.readIfEnabled(userId, threadId, page)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        // Optional private storage is best-effort and deliberately silent (#316).
        null
    }

    /** Preference and session seal are both re-read immediately before the Room transaction. */
    private suspend fun persistTerminalPage(
        userId: String,
        thread: PrivateMessageThread,
        stamp: PrivateMessageThreadSessionCache.Stamp,
    ) {
        try {
            privateMessageContentAccess.replaceIfEnabled(
                userId = userId,
                thread = thread,
                fetchedAt = Instant.now(),
                isSessionCurrent = { isCurrentSession(stamp) },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Optional private storage is best-effort and deliberately silent (#316).
        }
    }

    private suspend fun isCurrentSession(stamp: PrivateMessageThreadSessionCache.Stamp): Boolean =
        threadSessionCache.isCurrent(stamp) &&
            currentPseudo()?.let(::canonicalizePseudo) == stamp.account

    private fun PrivateMessageThread.matchesTarget(threadId: Int, page: Int): Boolean =
        this.threadId == threadId && this.page == page

    /** #453 — events folded by the unread-count [scan]. */
    private sealed interface UnreadEvent

    /**
     * A count from the network. [isInitial] marks the one-shot cold-start fetch (vs a refresh tick /
     * page-1 piggyback) so a late-landing cold-start cannot reset reads another count already baselined.
     */
    private data class NetworkCount(val count: Int?, val isInitial: Boolean = false) : UnreadEvent

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
            is NetworkCount -> when {
                // The FIRST count to arrive (whichever source wins the cold-start race) adopts its
                // value but KEEPS the local reads accumulated meanwhile : a read that raced the very
                // first fetch must not be discarded — HFR has no server-side read flag (#361), so that
                // count can't reflect it anyway (#453, Codex review).
                !hasNetworkCount -> copy(baseCount = event.count, hasNetworkCount = true)
                // A late-landing cold-start fetch (a piggyback / refresh tick already baselined first)
                // is stale and must NOT reset the reads marked since : drop it (Codex review, #453).
                event.isInitial -> this
                // A genuine later refresh (foreground tick / page-1 piggyback) is authoritative : it
                // resets the local decrements so the displayed count reconciles with the server truth.
                else -> LocalUnread(baseCount = event.count, readThreadIds = emptySet(), hasNetworkCount = true)
            }
            is ReadThread -> copy(readThreadIds = readThreadIds + event.threadId)
        }
    }

    /** Wraps a resolved (possibly `null`) displayed count so [mapNotNull] only drops the scan seed. */
    private data class Displayed(val value: Int?)

    private companion object {
        const val LOG_TAG = "MessagesRepository"
        const val FIRST_PAGE = 1
        const val REDACTED_PRIVATE_MESSAGE_URL = "private-message-session"
    }
}
