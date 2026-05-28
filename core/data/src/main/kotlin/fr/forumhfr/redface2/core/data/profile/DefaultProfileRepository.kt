package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import javax.inject.Inject
import javax.inject.Singleton
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
 * Wraps the network + parse call in [withContext](ioDispatcher) so callers
 * can safely invoke this from any coroutine context, including
 * `viewModelScope.launch {}` on `Dispatchers.Main.immediate`.
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
            runCatching {
                val html = client.getProfile(userId)
                parser.parseUserProfile(html, userId)
            }
        }
}
