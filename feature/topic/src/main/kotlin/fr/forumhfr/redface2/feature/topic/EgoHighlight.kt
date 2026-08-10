package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.model.Post

/**
 * Returns the canonical pseudo that Ego highlights may trust for the current session.
 *
 * The feature gate is checked before the session data so disabled highlights, anonymous sessions,
 * missing pseudos and pseudos that canonicalize to an empty string all share the same safe `null`.
 */
internal fun deriveEgoCanonicalPseudo(
    enabled: Boolean,
    isAuthenticated: Boolean,
    connectedPseudo: String?,
): String? {
    if (!enabled || !isAuthenticated) return null
    return connectedPseudo
        ?.let(::canonicalizePseudo)
        ?.takeIf(String::isNotEmpty)
}

/**
 * Returns whether [post] belongs to the currently authenticated session for EgoPost rendering.
 *
 * [Post.isOwnPost] is deliberately ignored: it is persisted in a topic cache that is not scoped
 * to an account, so the bit can outlive an A → B account switch. The author comparison is the
 * session-bound source of truth and also covers profiles whose HFR toolbar is disabled.
 * [egoCanonicalPseudo] is derived once for the loaded page by [deriveEgoCanonicalPseudo].
 */
internal fun isEgoPost(
    post: Post,
    egoCanonicalPseudo: String?,
): Boolean = egoCanonicalPseudo != null && canonicalizePseudo(post.author) == egoCanonicalPseudo
