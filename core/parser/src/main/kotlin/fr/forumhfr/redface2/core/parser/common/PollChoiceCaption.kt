package fr.forumhfr.redface2.core.parser.common

/**
 * #779 (PR 1) — reads HFR's « Sondage à N choix possibles » caption, the ONLY place the
 * per-poll vote limit is exposed. HFR renders it for multiple-choice polls on both surfaces:
 * the results card and the vote FORM's `div.sondage`. A single-choice poll carries no caption
 * (a radio group is implicitly « 1 choix »), so the absence of a match is meaningful, not an error.
 *
 * Shared between [fr.forumhfr.redface2.core.parser.TopicPageParser] (which stores the limit on
 * `Poll.maxSelections`) and the vote-form parser, so the regex lives in exactly one place.
 *
 * `max_votes` is deliberately NOT read from the vote form: that input only exists on the poll
 * CREATION form (`message.php`), never on the vote form — proven on the live captures
 * `topic_poll_form_meteo` (mono) / `topic_poll_form_multi_bourse` (multi, caption « à 2 choix »).
 */
internal object PollChoiceCaption {
    private val CAPTION = Regex("""Sondage à\s+(\d+)\s+choix""", RegexOption.IGNORE_CASE)

    /**
     * @return N from « Sondage à N choix possibles » found anywhere in [text], or `null` when the
     *   caption is absent (single-choice poll, or a shape that does not carry it). Callers decide
     *   the honest fallback: `1` for a radio group, `null` when the limit is truly unknown.
     */
    fun maxSelections(text: String): Int? =
        CAPTION.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
