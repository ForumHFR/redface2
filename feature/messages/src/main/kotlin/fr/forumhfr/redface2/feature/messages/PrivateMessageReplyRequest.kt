package fr.forumhfr.redface2.feature.messages

/**
 * Route arguments for [PrivateMessageReplyViewModel] (#301). Plain data class so route values pass
 * through Hilt assisted injection, mirroring [PrivateMessageThreadRequest].
 *
 * Only opaque route data is carried (thread id + the page the user is replying from). The form's
 * `hash_check`, hidden fields, subject and the user's pseudo are read from the freshly-fetched
 * `forum2.php?cat=prive` page so no private metadata is serialised into the back stack.
 */
data class PrivateMessageReplyRequest(
    val threadId: Int,
    val page: Int = 1,
    /**
     * #618 — when true, the screen auto-opens the « Gérer les destinataires » bottom sheet once the
     * form has loaded and the owner-only member editor is available. Carried from
     * `PrivateMessageReplyRoute.openRecipientManager`; `false` on the normal « Répondre » path.
     */
    val openRecipientManager: Boolean = false,
)
