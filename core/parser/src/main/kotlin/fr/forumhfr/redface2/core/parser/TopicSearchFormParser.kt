package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Chantier C (#546) — extracts the intra-topic search form (`transsearch.php`) hidden fields from a
 * loaded topic page. The result feeds [fr.forumhfr.redface2.core.network.HfrClient.searchInTopic].
 *
 * The form is present on every topic page (authenticated, locked, logged-out, cat-IA), but its
 * `hash_check` is only non-empty on an AUTHENTICATED capture (cf. [TopicSearchForm.canSearch]) —
 * `transsearch.php` rejects a blank token. We still parse the form on an anonymous page so the
 * caller can read `canSearch == false` and keep the affordance disabled, mirroring how
 * `TopicPageParser` reads `canReply` from the reply-form presence.
 *
 * Every field lookup is scoped to the `transsearch` form element (not document-wide) because the
 * topic page ships sibling `cat`/`post` inputs on the reply and fast-search forms.
 */
class TopicSearchFormParser {

    /**
     * @return the parsed [TopicSearchForm], or `null` when the page carries no `transsearch` form or
     *   is missing one of its required ids (`post` / `cat`). A `null` lets the caller keep the
     *   intra-topic-search affordance hidden rather than crash a topic that lacks the form (e.g. a
     *   synthetic / truncated cache row). `firstnum` is NOT required (#894) : a `transsearch`
     *   RESPONSE ships the form without it (only topic pages carry the anchor — verified live,
     *   anonymous and authenticated) — requiring it made every search-response form parse to null,
     *   which dropped HFR's `currentnum` cursor and broke the whole non-filtered mode.
     */
    fun parse(html: String): TopicSearchForm? = parse(Jsoup.parse(html))

    /**
     * Document overload — lets [TopicPageParser] reuse its already-parsed [Document] instead of
     * re-running Jsoup over the same HTML.
     */
    fun parse(document: Document): TopicSearchForm? =
        document.selectFirst(HfrSelectors.TOPIC_SEARCH_FORM)?.let(::toForm)

    /**
     * Builds the model from the located form element, or `null` when a required id (`post` / `cat`)
     * is missing/unparsable. Single exit point keeps detekt's ReturnCount happy.
     */
    private fun toForm(form: Element): TopicSearchForm? {
        val topicId = form.intValue(HfrSelectors.TOPIC_SEARCH_POST)
        val cat = form.intValue(HfrSelectors.TOPIC_SEARCH_CAT)
        return if (topicId == null || cat == null) {
            null
        } else {
            TopicSearchForm(
                // An absent value attribute (a logged-out page renders `<input name="hash_check" />`
                // without `value=`) is normalised to "" so `canSearch` reads false.
                hashCheck = form.selectFirst(HfrSelectors.TOPIC_SEARCH_HASH_CHECK)?.attr("value").orEmpty(),
                topicId = topicId,
                cat = cat,
                // #894 — present on a NORMAL topic page (the search anchor : first numreponse of
                // the rendered page), ABSENT from a `transsearch` response form.
                firstnum = form.intValue(HfrSelectors.TOPIC_SEARCH_FIRSTNUM),
                owntopic = form.intValue(HfrSelectors.TOPIC_SEARCH_OWNTOPIC) ?: 0,
                // Chantier B (#546) — null on a normal page (no `currentnum` input ; HFR's JS injects
                // one client-side), non-null on a `transsearch` response carrying the anchored match.
                currentNum = form.intValue(HfrSelectors.TOPIC_SEARCH_CURRENTNUM),
            )
        }
    }

    private fun Element.intValue(selector: String): Int? =
        selectFirst(selector)?.attr("value")?.trim()?.toIntOrNull()
}
