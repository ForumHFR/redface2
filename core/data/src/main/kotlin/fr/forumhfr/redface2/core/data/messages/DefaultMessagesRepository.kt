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
import kotlinx.coroutines.flow.flowOn
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeUnreadMpCount(): Flow<Int?> = authRepository.observeAuthState()
        .transformLatest { state ->
            when (state) {
                AuthState.Anonymous -> emit(null)
                is AuthState.Authenticated -> emit(fetchUnreadCount())
            }
        }
        .flowOn(ioDispatcher)

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
            parser.parseList(hfrClient.getPrivateMessageListPage(page = page))
        }

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

    private companion object {
        const val LOG_TAG = "MessagesRepository"
    }
}
