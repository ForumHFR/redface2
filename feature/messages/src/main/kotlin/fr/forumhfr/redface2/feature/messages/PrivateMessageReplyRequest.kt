package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.QuoteSelection

/**
 * Route arguments for [PrivateMessageReplyViewModel] (#301). Plain data class so route values pass
 * through Hilt assisted injection, mirroring [PrivateMessageThreadRequest].
 *
 * Route-derived openings carry only thread/page and, for a simple citation, the server-provided
 * target plus page rank. A multi-quote opening may also receive transient [initialQuotes] through
 * the in-memory app handoff; those snapshots are never route arguments. The form's `hash_check`,
 * hidden fields, subject, quoted BBCode and user pseudo are always read from fresh HFR forms, so no
 * private message content is serialised into the back stack.
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
    /**
     * #1074 — transient selections handed to a « Citer N » editor opening. Their complete locators
     * stay in memory and are materialised sequentially; they are never serialised in the route.
     */
    val initialQuotes: List<QuoteSelection> = emptyList(),
) {
    init {
        require(!openRecipientManager || (quote == null && initialQuotes.isEmpty())) {
            "A private-message quote session cannot open the recipient manager"
        }
        require(quote == null || initialQuotes.isEmpty()) {
            "A simple private-message quote cannot also carry multi-quote selections"
        }
    }
}
