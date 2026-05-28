package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Phase 2 finish (#208) — default implementation of [ProfileRepository].
 *
 * Fetches the user profile page from HFR anonymously (cf.
 * `docs/specs/protocol-hfr.md` § Profil public) and parses it via
 * [HfrParser.parseUserProfile].
 *
 * The anonymous client in [HfrClient.getProfile] ensures we never mark
 * drapeaux as read — matching the prefetch-non-authentifié rule.
 *
 * Review feedback M1: [withContext](ioDispatcher) here is only needed so the
 * **Jsoup parse** runs off the main thread — `HfrClient.getProfile` already
 * wraps its own network I/O in `withContext(ioDispatcher)`. We keep the wrap
 * to make the contract explicit (any caller can invoke this from
 * `viewModelScope.launch {}` on `Dispatchers.Main.immediate` without risking
 * a `NetworkOnMainThreadException` if the network wrap is removed by a future
 * refactor of [HfrClient.getProfile]).
 *
 * Review feedback I5: `kotlin.runCatching` is **not** coroutine-aware — it
 * catches `CancellationException` and turns it into a `Result.failure`, which
 * silently keeps the load alive after the caller's job has been cancelled.
 * We use a manual try/catch that rethrows `CancellationException` to preserve
 * structured concurrency.
 *
 * No Room cache in Phase 2 finish: profiles are fetched on demand and the
 * response is small (~30 KB). A cache can be added in a follow-up if the
 * profile screen gains a "back" navigation that would need to restore state
 * quickly.
 */
@Singleton
class DefaultProfileRepository @Inject constructor(
    private val client: HfrClient,
    private val parser: HfrParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override suspend fun getProfile(userId: Int): Result<UserProfile> =
        withContext(ioDispatcher) {
            try {
                val html = client.getProfile(userId)
                val profile = parser.parseUserProfile(html, userId)
                Result.success(profile)
            } catch (cancellation: CancellationException) {
                // Cooperative cancellation MUST propagate so the surrounding job
                // can complete cleanly. `runCatching` swallows it ; we do not.
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
                // Catch broad because we want every IO failure, parse failure, Jsoup
                // crash, etc. to surface as Result.failure for the ViewModel. The same
                // suppression is used across :core:data repositories that wrap network
                // + parse pipelines (DefaultReplyRepository, DefaultTopicFormRepository).
                Result.failure(throwable)
            }
        }
}
