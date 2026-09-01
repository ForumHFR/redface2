package fr.forumhfr.redface2.core.parser.write.poll

import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import fr.forumhfr.redface2.core.parser.common.PollChoiceCaption
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses HFR's poll vote form (`form[method=post][action*=vote.php]`) out of a topic page.
 *
 * Sibling of [fr.forumhfr.redface2.core.parser.write.ReplyFormParser] and
 * [fr.forumhfr.redface2.core.parser.write.TopicFormParser], but with two contract differences that
 * come straight from live authenticated and logged-out captures:
 *
 * - **Empty `hash_check` is tolerated.** The reply/topic parsers `Result.failure` on a blank token
 *   because their flows always run authenticated. A logged-out poll form carries an empty token;
 *   that is expected, surfaced verbatim in [PollVoteForm.hashCheck], and rejected downstream by
 *   the submit repository. Dropping it here would also hide the open-poll capability from cache
 *   refresh policy.
 * - **`max_votes` is never read.** That input lives only on the poll CREATION form
 *   (`message.php`, handled by `TopicFormParser`), never on the vote form. The per-poll limit is
 *   the « Sondage à N choix possibles » caption instead (see [PollChoiceCaption]).
 *
 * @return the parsed [PollVoteForm], or `null` when the page carries no vote form or when the form
 *   exposes no readable choice (a `div.sondage` with `<li>`s that carry neither a `reponse*` input
 *   nor a bound `<label>`). A `null` keeps a caller inert rather than emitting a half-formed model.
 *
 */
class PollVoteFormParser {

    fun parse(html: String): PollVoteForm? =
        parse(Jsoup.parse(html))

    fun parse(document: Document): PollVoteForm? =
        document.selectFirst(HfrSelectors.POLL_VOTE_FORM)?.let(::toForm)

    private fun toForm(form: Element): PollVoteForm? {
        val choices = parseChoices(form) ?: return null
        val multipleChoice = form.selectFirst(HfrSelectors.POLL_FORM_MULTI_INPUT) != null
        return PollVoteForm(
            hashCheck = resolveHashCheck(form),
            hiddenFields = collectHiddenFields(form),
            choices = choices,
            multipleChoice = multipleChoice,
            // Single-choice = a radio group, which allows exactly one pick (factual → 1). For a
            // multiple-choice poll we read the caption; a missing caption leaves the cap unknown
            // (null), never an invented number.
            maxSelections = if (multipleChoice) PollChoiceCaption.maxSelections(form.text()) else 1,
        )
    }

    /**
     * The vote form's hidden ids, `hash_check` excluded (it is surfaced separately). Only
     * `input[type=hidden]` is swept, so the `reponse*` choice inputs and the `sondage_submit`
     * buttons never leak in. Insertion order is preserved (LinkedHashMap) so a diff against a live
     * capture reads naturally. A duplicate hidden name would collapse to its last value in this map
     * — NOT what a browser does (it submits every occurrence) — but the only duplicate observed
     * (`hash_check`) is surfaced separately and excluded here, so it never applies.
     */
    private fun collectHiddenFields(form: Element): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        form.select(HfrSelectors.POLL_VOTE_HIDDEN_INPUT).forEach { input ->
            val name = input.attr("name")
            if (name.isEmpty() || name == HASH_CHECK) return@forEach
            fields[name] = input.attr("value")
        }
        return fields
    }

    /**
     * Resolves `hash_check`, which HFR renders twice on the vote form (once outside, once inside
     * `div.sondage`). Last non-empty wins; when both are empty on a logged-out form this returns
     * `""`. That empty token is expected and is not a parse failure (see class KDoc).
     */
    private fun resolveHashCheck(form: Element): String {
        var resolved = ""
        form.select(HfrSelectors.POLL_VOTE_HASH_CHECK).forEach { input ->
            val value = input.attr("value")
            if (value.isNotEmpty()) resolved = value
        }
        return resolved
    }

    /**
     * Reads the `<ol><li>` options into [PollVoteChoice]s. Mirrors the read-side FORM-shape parser
     * (#697): each `<li>` must carry both a `reponse*` input and a bound `<label>`. If any matched
     * `<li>` is incomplete (count mismatch) or none match, returns `null` so the form is dropped
     * rather than surfaced half-parsed.
     */
    private fun parseChoices(form: Element): List<PollVoteChoice>? {
        val options = form.select(HfrSelectors.POLL_FORM_OPTION)
        val choices = options.mapNotNull { option ->
            val input = option.selectFirst(HfrSelectors.POLL_FORM_OPTION_INPUT) ?: return@mapNotNull null
            val label = option.selectFirst(HfrSelectors.POLL_FORM_OPTION_LABEL)
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val name = input.attr("name").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            PollVoteChoice(
                id = input.attr("id"),
                name = name,
                value = input.attr("value"),
                label = label,
            )
        }
        return choices.takeIf { it.isNotEmpty() && it.size == options.size }
    }

    private companion object {
        private const val HASH_CHECK = "hash_check"
    }
}
