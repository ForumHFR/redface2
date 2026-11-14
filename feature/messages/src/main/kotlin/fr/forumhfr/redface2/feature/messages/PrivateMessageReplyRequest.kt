package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.QuoteSelection

/**
 * Route arguments for [PrivateMessageReplyViewModel] (#301). Plain data class so route values pass
 * through Hilt assisted injection, mirroring [PrivateMessageThreadRequest].
 *
 * Openings carry only thread/page. Q is enqueued in the owner-bound draft store before navigation,
 * for simple and multiple citations alike (ED-132 v0.7.8). [quote] and [initialQuotes] remain legacy
 * input fields for source compatibility; the reply never imports them into Q or serves them. The
 * form's `hash_check`, hidden fields, subject, quoted BBCode and user pseudo are always read from
 * fresh HFR forms, so no private message content is serialised into the back stack.
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
    /** Legacy launch coordinate, ignored: the owning draft row supplies Q. */
    val quote: PrivateMessageQuote? = null,
    /** Legacy « Citer N » selections, ignored like [quote]: the owning draft row supplies the ordered Q. */
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
