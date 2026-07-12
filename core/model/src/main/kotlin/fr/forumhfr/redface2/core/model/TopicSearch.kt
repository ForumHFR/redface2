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
 *   HFR uses it as the search anchor (« search from the current page onwards ») ; forwarded
 *   verbatim. **Nullable since #894** : the field is only present in the form of a NORMAL topic
 *   page — a `transsearch` RESPONSE ships a form with `currentnum` but NO `firstnum` input
 *   (verified live 2026-07-12, anonymous AND authenticated). A null must never be silently
 *   promoted to « search the whole topic » on a fresh submit — the caller either reuses the
 *   anchor it captured from the last real topic page, or fails explicitly.
 * @property owntopic the `owntopic` flag verbatim from the form (`0` on a normal topic, `1` observed
 *   on the cat-IA owned-topic capture). Wire detail, kept as-is.
 * @property currentNum the server-side navigation cursor parsed from the form's `currentnum` hidden
 *   input. `null` on a NORMAL topic page (HFR's static form ships no `currentnum` input — its own JS
 *   creates one at runtime), and non-null only on a `transsearch` RESPONSE page, where it points at the
 *   `numreponse` of the match HFR anchored. The ViewModel reads it back after a search to drive the
 *   next/previous result navigation (Chantier B / #546) and to detect the end of results — a cursor
 *   value that is no longer a post present on the page means HFR ran past the last match.
 */
data class TopicSearchForm(
    val hashCheck: String,
    val topicId: Int,
    val cat: Int,
    val firstnum: Int?,
    val owntopic: Int = 0,
    val currentNum: Int? = null,
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
 *   for a FRESH search (the documented "clear on submit" behaviour : HFR re-anchors on the first
 *   match) ; carries the current match's `numreponse` for a next/previous STEP so HFR advances to the
 *   following match. Built by the ViewModel from the previous response's [TopicSearchForm.currentNum].
 * @property isStep `true` for a next/previous navigation step (as opposed to a fresh search). When
 *   stepping, the repository OMITS `firstnum` (and `dep`) from the POST : re-sending `firstnum`
 *   re-anchors HFR on the FIRST match and the cursor never progresses (the live-verified stepping bug
 *   — Chantier B / #546). A fresh search keeps `firstnum` (the page anchor HFR expects).
 */
data class TopicSearchRequest(
    val form: TopicSearchForm,
    val word: String,
    val spseudo: String,
    val onlyMatches: Boolean,
    val currentNum: String? = null,
    val isStep: Boolean = false,
    /**
     * #879 — page of the transsearch RESULTS to fetch (HFR's `p` form field, historically frozen
     * to 1 : only the first page of a filtered result list was ever reachable, so late matches
     * were silently dropped). The result pager is OWN to the search — never the canonical topic
     * pager. Fresh submits send 1 ; « résultats suivants » re-submits with the next page.
     */
    val page: Int = 1,
) {
    /** HFR needs at least a term or an author ; an all-blank search is meaningless. */
    val isMeaningful: Boolean get() = word.isNotBlank() || spseudo.isNotBlank()
}
