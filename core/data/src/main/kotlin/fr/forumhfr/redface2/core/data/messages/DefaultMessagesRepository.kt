package fr.forumhfr.redface2.core.data.messages

import android.util.Log
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Phase 1B.1 bonus: surfaces the authenticated user's unread MP count on FlagsScreen as
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
        runCatching {
            val html = hfrClient.getPrivateMessageListPage(page = 1)
            parser.countUnread(html)
        }.onFailure { throwable ->
            // Surface fetch failures in logcat so a missing "MPs non lus" line on FlagsScreen
            // can be debugged. Silent swallow would make a regression invisible (e.g. HFR DOM
            // change → parser returns 0 vs network failure → null). Logging on failure only
            // keeps the happy path quiet.
            Log.w(LOG_TAG, "Unread MP count fetch failed", throwable)
        }.getOrNull()
    }

    private companion object {
        const val LOG_TAG = "MessagesRepository"
    }
}
