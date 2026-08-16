package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote

/**
 * Route arguments for [PrivateMessageReplyViewModel] (#301). Plain data class so route values pass
 * through Hilt assisted injection, mirroring [PrivateMessageThreadRequest].
 *
 * Only routing identifiers are carried (thread/page and, for a citation, the server-provided target
 * plus page rank). The form's `hash_check`, hidden fields, subject, quoted BBCode and user pseudo are
 * always read from a fresh HFR form, so no private message content is serialised into the back stack.
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
    /** Null for « Répondre »; non-null for the fail-closed citation path. */
    val quote: PrivateMessageQuote? = null,
) {
    init {
        require(!openRecipientManager || quote == null) {
            "A private-message quote cannot open the recipient manager"
        }
    }
}
