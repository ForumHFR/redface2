package fr.forumhfr.redface2.core.domain.auth

import java.io.IOException

/**
 * Authenticated HFR endpoint returned the login page instead of the requested resource.
 * Callers must treat this as a stale session, not as an empty flags/MP/topic payload.
 */
class SessionExpiredException(
    val finalUrl: String,
) : IOException("HFR session expired; final URL was $finalUrl")
