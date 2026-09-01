package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.QuoteSelection

/**
 * #974 — which posts a reply CITES, independently of how the citations were authored : inline
 * `[quotemsg]` BBCode in the text field (the production default, `quoteCardsEnabled = false`) or
 * quote cards materialised at submit. The topic engine lands on the highest one after the POST
 * ([fr.forumhfr.redface2.core.model.write.ReplySubmitResult.Success.numreponse] only carries the
 * FIRST cited post — HFR anchors the success URL on the `numrep` of the quote form).
 *
 * Pure and total : malformed tags are skipped, never thrown on (user input).
 */
object QuotedNumreponses {

    /**
     * HFR's citation tag, `[quotemsg=<numreponse>,<position>,<userId>]` (`protocol-hfr.md` § Quote) :
     * the FIRST parameter is the cited `numreponse` ; the others are optional here (HFR prefills
     * carry three, some materialised forms only one). Case-insensitive like HFR's BBCode. A
     * missing `[/quotemsg]` is not the extractor's concern — the post was still cited.
     */
    private val QUOTEMSG_OPEN_TAG: Regex =
        Regex("""\[quotemsg=\s*(\d+)\s*(?:,[^\]]*)?]""", RegexOption.IGNORE_CASE)

    /** Cited `numreponse`s found in [bbcode], first-appearance order, deduplicated. */
    fun fromBbcode(bbcode: String): List<Int> =
        QUOTEMSG_OPEN_TAG.findAll(bbcode)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()

    /**
     * [fromBbcode] on the field content, unioned with the armed [cards] (cards mode : the
     * `[quotemsg]` blocks are only materialised at submit, so they are absent from the field).
     * Appearance order, cards after the inline tags, deduplicated.
     */
    fun of(bbcode: String, cards: List<QuoteSelection>): List<Int> =
        (fromBbcode(bbcode) + cards.map { it.numreponse }).distinct()
}
