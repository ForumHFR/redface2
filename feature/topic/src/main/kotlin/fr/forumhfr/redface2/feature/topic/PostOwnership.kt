package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.model.Post

/**
 * #545 — session-aware post ownership.
 *
 * HFR profiles with « Affichage des outils » disabled (`affichoutils=0`) are served pages WITHOUT
 * the per-post toolbar, so the parser cannot see the edit link and `Post.isEditable` /
 * `Post.isOwnPost` both come back `false` even on the user's own posts. The author cell lives
 * outside the toolbar and is always present, so comparing it against the connected session's
 * pseudo is the reliable fallback. Canonical comparison mirrors the blacklist/profile matching
 * (case- and whitespace-insensitive).
 */
internal fun isOwnPostBySession(post: Post, connectedPseudo: String?): Boolean =
    !connectedPseudo.isNullOrBlank() &&
        canonicalizePseudo(post.author) == canonicalizePseudo(connectedPseudo)

/** Effective ownership : what the parser saw (toolbar) OR the session fallback (#545). */
internal fun isOwnPostEffective(post: Post, connectedPseudo: String?): Boolean =
    post.isOwnPost || isOwnPostBySession(post, connectedPseudo)
