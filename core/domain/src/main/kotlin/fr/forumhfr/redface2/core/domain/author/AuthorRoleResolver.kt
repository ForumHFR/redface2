package fr.forumhfr.redface2.core.domain.author

import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.model.AuthorRole

/**
 * Resolves the decorative staff pill for one post from the canonicalized global directory.
 * Moderation-system posts never inherit the role of the displayed pseudo, and [AuthorRole.MEMBER]
 * is deliberately filtered out because only staff roles have a pill.
 */
fun resolveAuthorRolePill(
    author: String,
    isModerationPost: Boolean,
    staffByPseudo: Map<String, AuthorRole>,
): AuthorRole? =
    staffByPseudo[canonicalizePseudo(author)]
        ?.takeUnless { isModerationPost || it == AuthorRole.MEMBER }
