package fr.forumhfr.redface2.core.model

/**
 * Chantier C (#546) — intra-topic search, a MODE of `feature/topic` (NOT an extension of
 * `feature/search`, which models the cross-topic listing).
 *
 * HFR ships a small search form in the header of every topic page (`form[action=/transsearch.php]`,
 * cf. fixtures `topic_*.html` / `write_*topic*.html`). Submitting it re-renders the SAME topic page,
 * optionally filtered to only the messages matching the term/author. The whole feature therefore
 * reuses the topic-page contract end to end :
 *
 *  - request  → POST `/transsearch.php` with the hidden form fields + `word` / `spseudo` / `filter`
 *  - response → a topic page, re-parsed with the existing `TopicPageParser` into a [Topic]
 *
 * The form's hidden fields (`hash_check`, `post`, `cat`, `firstnum`, …) are parsed from the
 * currently-loaded topic page into [TopicSearchForm]. A non-empty [TopicSearchForm.hashCheck]
 * means the page was served to an AUTHENTICATED session — the only case in which `transsearch.php`
 * accepts the search (HFR rejects an empty `hash_check`).
 */

/**
 * The hidden fields of the topic-page `transsearch.php` form, extracted from the loaded topic page.
 *
 * Every field is forwarded verbatim to [fr.forumhfr.redface2.core.network.HfrClient.searchInTopic] —
 * HFR keys the search on them. The form carries no `currentnum` input in the static HTML : HFR's own
 * JS creates and manages it at runtime (the submit button's `onclick` clears it). We therefore send
 * `currentnum` empty for a fresh search and only carry a cursor value for best-effort next/previous
 * navigation (see [TopicSearchRequest.currentNum]).
 *
 * @property hashCheck HFR anti-CSRF token. EMPTY on an anonymous capture (the form is rendered but
 *   inert) ; non-empty only on an authenticated page. `transsearch.php` requires it non-empty, hence
 *   intra-topic search is an authenticated-only feature.
 * @property topicId the `post` hidden field — the topic id.
 * @property cat the topic's category id.
 * @property firstnum the `numreponse` of the first message on the page the form was rendered on.
 *   HFR uses it as the search anchor ; forwarded verbatim.
 * @property owntopic the `owntopic` flag verbatim from the form (`0` on a normal topic, `1` observed
 *   on the cat-IA owned-topic capture). Wire detail, kept as-is.
 */
data class TopicSearchForm(
    val hashCheck: String,
    val topicId: Int,
    val cat: Int,
    val firstnum: Int,
    val owntopic: Int = 0,
) {
    /**
     * `transsearch.php` rejects an empty `hash_check`, so the search affordance must only be
     * offered when the form was parsed from an authenticated page. Mirrors the `Topic.canReply`
     * "authenticated form present" contract.
     */
    val canSearch: Boolean get() = hashCheck.isNotBlank()
}

/**
 * A caller-side intra-topic search, built by the ViewModel from the user input + the parsed
 * [TopicSearchForm] of the loaded page.
 *
 * @property word the term to look for (HFR `word`). May be blank when filtering by author only.
 * @property spseudo author filter (HFR `spseudo`). May be blank when searching by term only. At
 *   least one of [word] / [spseudo] must be non-blank — the ViewModel enforces this.
 * @property onlyMatches the real semantics of HFR's `filter` checkbox : when `true`, HFR re-renders
 *   the topic page showing ONLY the messages matching the search ; when `false` it returns the
 *   page with the matches highlighted in place. Named for intent, not for the wire (`filter=1`).
 * @property currentNum the server-side navigation cursor (HFR's JS-managed `currentnum`). `null`/blank
 *   for a fresh search (the documented "clear on submit" behaviour) ; a value is carried only for
 *   the EXPERIMENTAL next/previous navigation. **Never observed live** — see [TopicSearchForm].
 */
data class TopicSearchRequest(
    val form: TopicSearchForm,
    val word: String,
    val spseudo: String,
    val onlyMatches: Boolean,
    val currentNum: String? = null,
) {
    /** HFR needs at least a term or an author ; an all-blank search is meaningless. */
    val isMeaningful: Boolean get() = word.isNotBlank() || spseudo.isNotBlank()
}
