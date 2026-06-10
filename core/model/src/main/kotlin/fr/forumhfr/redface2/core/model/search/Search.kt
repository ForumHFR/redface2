package fr.forumhfr.redface2.core.model.search

/**
 * Phase 2G-A/B (#150 partiel) — domain models for the HFR forum search.
 *
 * The search endpoint reachable at `/forum1.php?recherches=1&...` returns four
 * structurally distinct HTML shapes (cf. fixtures `search_*.html` under
 * `core/parser/src/test/resources/fixtures/`) :
 *
 *  1. **pivot single** — `cat=` request, one category matched. Banner +
 *     `<select name="cat">` with a single `<option>` selected + listing.
 *  2. **pivot multi** — `cat=` request, several categories matched. Banner +
 *     pivot dropdown with N options + listing of the first hit category.
 *  3. **explicit cat** — `cat=X*hfr.inc` request. No banner, no pivot, plain
 *     `forum1.php` listing constrained to cat X.
 *  4. **no results** — minimal `.hop` page with the canonical
 *     « Désolé, aucune réponse n'a été trouvée ! » string.
 *
 * The parser maps all four onto [SearchResultPage]. Empty result sets are NOT
 * exceptions ; they are an empty [SearchResultPage.topics] list with
 * [SearchResultPage.pivotCategories] empty too.
 *
 * HFR's `titre` parameter controls where the text is searched. Title matches
 * return topic-level rows only. Post-body matches can additionally include a
 * « Dernier message correspondant » snippet with a `forum2.php?...numreponse=`
 * link ; the parser maps that optional detail to
 * [SearchTopicResult.page], [SearchTopicResult.numreponse], and
 * [SearchTopicResult.matchedExcerpt].
 */

/**
 * Caller-side description of a search. Built by the ViewModel from the user's
 * input and passed verbatim to [fr.forumhfr.redface2.core.domain.search.SearchRepository].
 *
 * [pseudo] maps to HFR's `pseud` form field — an author filter. HFR supports an
 * author-only search (`search=` empty, `pseud=` set), verified live 2026-06-11 in
 * all three shapes : explicit cat + `titre=1` (fixture
 * `search_pseud_filter_lt_ripley.html`), explicit cat + `titre=3` (content rows
 * with « Dernier message correspondant » snippets), and all-categories + `titre=3`
 * (302 redirect onto the standard multi-cat pivot, which OkHttp follows). The
 * result semantics are « topics where this user posted » — HFR does NOT
 * distinguish authored vs participated. At least one of [query] / [pseudo] must
 * be non-blank ; the ViewModel enforces this before building the request.
 */
data class SearchRequest(
    val query: String,
    val category: SearchCategoryScope = SearchCategoryScope.All,
    val textScope: SearchTextScope = SearchTextScope.TitlesAndPosts,
    val page: Int = 1,
    val pseudo: String? = null,
)

/**
 * HFR's `titre` form field. The app defaults to [TitlesAndPosts] because a
 * mobile search field is expected to search broadly, while [TitlesOnly] keeps
 * the stricter legacy mode available.
 */
enum class SearchTextScope(val hfrTitreValue: Int) {
    TitlesOnly(hfrTitreValue = 1),
    TitlesAndPosts(hfrTitreValue = 3),
    PostsOnly(hfrTitreValue = 0),
}

/**
 * Whether the search should run across all categories or be scoped to a single
 * one. The wire encoding differs : `All` → `cat=`, `Category(id)` → `cat=<id>*hfr.inc`.
 * The `name` on [Category] is informational only ; HFR ignores it.
 */
sealed interface SearchCategoryScope {
    data object All : SearchCategoryScope
    data class Category(val id: Int, val name: String? = null) : SearchCategoryScope
}

/**
 * Result of a single search round-trip. Wraps the listing rows + the category
 * pivot (when HFR returned one) + the originating request context.
 *
 * `pivotCategories` is empty for explicit-cat queries (HFR doesn't render the
 * pivot when the caller already scoped the search) AND for no-result pages.
 *
 * `selectedCategory` points at the currently-selected pivot entry when
 * applicable, useful for the UI to render the selection state without
 * re-walking the list. `null` outside multi-cat mode.
 */
data class SearchResultPage(
    val query: String,
    val requestedCategory: SearchCategoryScope,
    val selectedCategory: SearchPivotCategory?,
    val pivotCategories: List<SearchPivotCategory>,
    val topics: List<SearchTopicResult>,
    val currentPage: Int,
    val totalPages: Int,
)

/**
 * One option of the HFR search pivot dropdown — a category that matched the
 * current query and can be navigated into. `id` is the canonical HFR cat id
 * (`1` Hardware, `10` Programmation, etc.), `label` is the human-facing name
 * verbatim from HFR ("Hardware", "Technologies Mobiles", ...).
 */
data class SearchPivotCategory(
    val id: Int,
    val label: String,
    val isSelected: Boolean,
)

/**
 * One row of a search result listing. Distinct from [TopicSummary] because the
 * HTML search response does not expose the per-user fields the REST listing
 * does (`hasUnread`, `lastReadPage`, `flagType`). We project the fields HFR
 * actually serves on the search page instead of pretending this is a regular
 * forum listing.
 *
 * `numreponse`, `page`, and `matchedExcerpt` are nullable on purpose : title
 * matches give `(cat, topicId)` only. Post-body matches can add a second link
 * to the matching message, but mixed searches may still contain plain title
 * rows without snippet.
 *
 * `lastReplyAt` is left as a normalized String (NBSP-stripped) for this MVP
 * because HFR's serialization (« 22-05-2026 à 06:48 ») mixes localisation with
 * the timestamp and we'd rather defer typed-date work until a localized
 * date renderer lands across the app.
 */
data class SearchTopicResult(
    val cat: Int,
    val topicId: Int,
    val title: String,
    val author: String,
    val replyCount: Int,
    val viewCount: Int,
    val lastReplyAt: String,
    val lastReplyAuthor: String,
    val topicUrl: String,
    val categorySlug: String?,
    val subcategorySlug: String?,
    val isLocked: Boolean,
    val page: Int?,
    val numreponse: Int?,
    val matchedExcerpt: String?,
)
