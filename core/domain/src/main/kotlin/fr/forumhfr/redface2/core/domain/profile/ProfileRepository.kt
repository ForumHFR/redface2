package fr.forumhfr.redface2.core.domain.profile

import fr.forumhfr.redface2.core.model.UserProfile

/**
 * Phase 2 finish (#208) — domain interface for loading user profiles from
 * `/hfr/profil-{userId}.htm`.
 *
 * Navigation is always [userId]-first: [pseudo] and [avatarUrl] are display
 * hints that can be pre-populated from the topic page before the full profile
 * is loaded, but the canonical key is always the numeric [userId].
 *
 * No caching is specified for Phase 2 finish — the response is fetched
 * anonymously each time (profiles are public, sessions are irrelevant,
 * and the TTL of a profile is much longer than a topic page). Room persistence
 * can be added in a follow-up PR if needed.
 */
interface ProfileRepository {
    /**
     * Fetches and parses the profile for user [userId].
     *
     * Returns a [Result] wrapping [UserProfile] on success, or an exception
     * (typically [java.io.IOException] for network errors or
     * [fr.forumhfr.redface2.core.domain.auth.SessionExpiredException] if the
     * client detects a login redirect — unlikely on a public endpoint, but
     * propagated defensively).
     */
    suspend fun getProfile(userId: Int): Result<UserProfile>
}
