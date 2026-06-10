package fr.forumhfr.redface2.feature.forum

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import kotlin.math.ceil
import java.text.Normalizer

/**
 * UI state for the Forum home screen (list of HFR top-level categories). The state
 * machine mirrors [fr.forumhfr.redface2.core.domain.forum.ForumResult] without exposing
 * domain types directly, so the screen stays Compose-only and the VM stays easy to
 * unit-test.
 */
sealed interface ForumUiState {
    data object Loading : ForumUiState
    data class Content(val categories: List<Category>) : ForumUiState

    /**
     * [kind] (#324) is the type-derived classification: ServerDown / Network make the
     * screen render the shared `:core:ui` label instead of the raw [message]; Other keeps
     * the pre-existing rendering ([message] when non-blank).
     */
    data class Error(
        val message: String?,
        val kind: HfrErrorKind = HfrErrorKind.Other,
    ) : ForumUiState
}

/**
 * UI state for a category detail screen. We keep subcategories and topic list as
 * independent sub-states so the screen can render the topic list under a loading
 * skeleton while the subcategories are already shown (or vice-versa).
 *
 * `categoryName` is the HFR display name for [cat] when known (sourced from the
 * cached categories list); `null` when categories haven't loaded yet, in which case
 * the screen falls back to the bare numeric category id.
 *
 * `pageCount` is the number of listing pages computed from
 * `TopicListPage.totalTopics / resultsPerPage` — used by the pager to disable
 * "Suivant" on the last page. Defaults to `1` until topics finish loading.
 */
data class CategoryUiState(
    val cat: Int,
    val categoryName: String?,
    val initialSubcat: Int?,
    val selectedSubcat: Int?,
    val page: Int,
    val pageCount: Int,
    val subcategories: SubcategoriesUiState,
    val topics: TopicsUiState,
    /**
     * Local search filter. The query never hits the network — it filters the
     * currently loaded `topics.page` (when [topics] is `Content`). The
     * predicate matches the topic title, the original author, and the last
     * reply author, accent- and case-insensitively (cf. [matchesTopicQuery]).
     * Empty / blank query disables the filter and `filteredTopics` mirrors
     * the full page.
     *
     * The query is preserved across page / subcat changes by design (it is a
     * filtering preference, not a per-page search) — when the new page yields
     * 0 matches the screen renders an explicit empty state.
     */
    val searchQuery: String,
    /** Filtered view over `topics.page.topics` per [searchQuery]. */
    val filteredTopics: List<TopicSummary>,
    /**
     * `true` while a user-driven refresh (PullToRefresh, "Réessayer") is in
     * flight. Independent of the underlying [topics] / [subcategories] state
     * machines: the indicator stays on top of the existing content while the
     * refresh round-trip resolves, and the list is **not** wiped during that
     * window.
     */
    val isRefreshing: Boolean,
    /**
     * `true` when the active session is `AuthState.Authenticated`. Drives the
     * « Nouveau topic » FAB visibility (Phase 2E #149) — HFR refuses the wire
     * POST in anonymous mode and Redface 2 has decided not to surface the
     * legacy anonymous composer (cf. `docs/specs/protocol-hfr.md` § Note
     * anonyme).
     */
    val canCreateTopic: Boolean = false,
)

/**
 * Pure helper kept top-level so it can be tested without spinning up a ViewModel.
 *
 * The matcher is :
 * - case-insensitive (`Foo` matches `foo bar`)
 * - accent-insensitive — Unicode NFD-decomposes both sides then strips combining
 *   marks, so `electronique` matches `Electronique` and `CŒUR` matches `cœur`
 *   without pulling a heavy dependency
 * - matches against `title`, `author` and `lastReplyAuthor` only — listing
 *   payloads do not currently expose subcategory names per topic, so we keep
 *   the surface narrow and predictable.
 *
 * Returns `true` for blank queries so callers can use it as an unconditional
 * predicate.
 */
internal fun matchesTopicQuery(topic: TopicSummary, query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.foldForSearch()
    return topic.title.foldForSearch().contains(needle) ||
        topic.author.foldForSearch().contains(needle) ||
        topic.lastReplyAuthor.foldForSearch().contains(needle)
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")

/**
 * #206 workaround (« Exact post-création »). Returns `true` when [topic]'s title is the
 * one the user just posted, so the listing can highlight that exact row.
 *
 * Direct navigation to the created topic is impossible — HFR redirects a create to the
 * category listing and never returns the new topic id (#214). The only reliable handle is
 * the title the user typed, so the match is **exact** : both sides are trimmed (HFR strips
 * surrounding whitespace on the title) and compared case-insensitively. This is deliberately
 * strict — a `contains` match would risk highlighting an older topic whose title is a
 * substring of the new one. Exact duplicate titles can still match together; this is the
 * residual ambiguity of the workaround because HFR exposes no created topic id.
 *
 * Returns `false` for a `null` or blank [highlightTitle] (the normal nav path) so callers can
 * use it as an unconditional predicate that degrades to "no highlight".
 */
internal fun matchesHighlightedTitle(topic: TopicSummary, highlightTitle: String?): Boolean {
    val needle = highlightTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return topic.title.trim().equals(needle, ignoreCase = true)
}

/**
 * Keep the #206 create-topic highlight scoped to the exact listing reached after the POST.
 * The route argument may survive while the same screen lets the user change page or subcat;
 * ignoring it outside the initial `(subcat, page)` prevents highlighting an unrelated
 * same-title topic elsewhere in the category.
 */
internal fun routeScopedHighlightTitle(
    request: CategoryRequest,
    selectedSubcat: Int?,
    page: Int,
): String? =
    if (
        selectedSubcat == request.initialSubcat &&
        page == request.initialPage.coerceAtLeast(1)
    ) {
        request.highlightTitle
    } else {
        null
    }

/**
 * Pure helper kept top-level so it can be exercised in isolation. Falls back to `1`
 * when either input is non-positive or when the math underflows — the pager renders
 * a "Page 1 / 1" cell in that case which is what we want for empty listings.
 */
internal fun listingPageCount(totalTopics: Int, resultsPerPage: Int): Int {
    if (totalTopics <= 0 || resultsPerPage <= 0) return 1
    return ceil(totalTopics.toDouble() / resultsPerPage).toInt().coerceAtLeast(1)
}

sealed interface SubcategoriesUiState {
    data object Loading : SubcategoriesUiState
    data class Content(val subcategories: List<SubCategory>) : SubcategoriesUiState

    /** [kind] (#324): same contract as [ForumUiState.Error.kind]. */
    data class Error(
        val message: String?,
        val kind: HfrErrorKind = HfrErrorKind.Other,
    ) : SubcategoriesUiState
}

sealed interface TopicsUiState {
    data object Loading : TopicsUiState
    data class Content(val page: TopicListPage) : TopicsUiState

    /** [kind] (#324): same contract as [ForumUiState.Error.kind]. */
    data class Error(
        val message: String?,
        val kind: HfrErrorKind = HfrErrorKind.Other,
    ) : TopicsUiState
}
