package fr.forumhfr.redface2.core.parser.search

import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchPivotCategory
import fr.forumhfr.redface2.core.model.search.SearchResultPage
import fr.forumhfr.redface2.core.model.search.SearchTopicResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Phase 2G-A (#150 partiel) — Jsoup parser for HFR's search result page.
 *
 * Input HTML comes from `GET /forum1.php?recherches=1&...` (anonymous). The
 * page can take four structurally distinct shapes — see [SearchResultPage]
 * KDoc and the `search_*.html` fixtures under `core/parser/src/test/resources/fixtures/`.
 *
 * The parser maps all four onto a single [SearchResultPage]. The empty result
 * variant is NOT raised as an exception — it's a normal happy path with
 * empty `topics` and empty `pivotCategories`. Structurally broken pages
 * (missing `cat` context, or malformed topic rows once a `cat` is known) raise
 * [ParseException] instead of silently dropping rows.
 */
class SearchResultParser {

    /**
     * Parses [html] into a [SearchResultPage]. [query] and [requestedCategory]
     * are echoed back into the returned page so the ViewModel can correlate
     * with the originating request without storing it separately. [requestedPage]
     * is used as the fallback when the pagination row is absent.
     *
     * @throws ParseException when the HTML carries topic rows but no detectable
     * cat context (no pivot AND no explicit cat in the request), or when a row
     * misses a field required for navigation.
     */
    fun parse(
        html: String,
        query: String,
        requestedCategory: SearchCategoryScope,
        requestedPage: Int = 1,
    ): SearchResultPage {
        val document = Jsoup.parse(html)

        // Step 1 — no-results detection. The minimal `.hop` page exists with or
        // without an explicit cat (HFR returns the same template either way).
        // The wrapper `<div class="mesdiscussions">` is also present on the
        // no-results page, so it cannot be the discriminator on its own.
        val hopBlock = document.selectFirst("div.hop")
        if (hopBlock != null && hopBlock.text().contains(NO_RESULTS_MARKER)) {
            return SearchResultPage(
                query = query,
                requestedCategory = requestedCategory,
                selectedCategory = null,
                pivotCategories = emptyList(),
                topics = emptyList(),
                currentPage = 1,
                totalPages = 1,
            )
        }

        // Step 2 — pivot detection. The search pivot is ALWAYS inside
        // `div.search`. The footer `form#goto select[name=cat]` (present on every
        // forum1.php page) uses plain integer values (`<option value="10">`) and
        // must NOT be confused with the pivot ; we scope the selector by `div.search`
        // to dodge that trap.
        val pivotCategories = parsePivotCategories(document)
        val selectedPivot = pivotCategories.firstOrNull { it.isSelected }

        // Step 3 — pick the `cat` to attach to each topic row.
        // Priority : pivot-selected → request scope → fail.
        val effectiveCatId = selectedPivot?.id
            ?: (requestedCategory as? SearchCategoryScope.Category)?.id

        // Step 4 — parse the listing rows. `tr.sujet` is specific enough on this
        // endpoint (no MP section visible on anonymous search responses).
        val rowElements = document.select("tr.sujet")
        if (rowElements.isNotEmpty() && effectiveCatId == null) {
            // We have rows but cannot tag them with a cat — the response shape
            // is unexpected (e.g. a future HFR variant). Fail typed rather than
            // emit rows with a bogus cat or silently drop the listing.
            throw ParseException(
                "Search response has ${rowElements.size} topic rows but neither a pivot " +
                    "selection nor an explicit requested category — cannot attribute cat.",
            )
        }
        val topics = if (effectiveCatId != null) {
            rowElements.mapIndexed { index, row -> parseTopicRow(row, effectiveCatId, index) }
        } else {
            emptyList()
        }

        val (currentPage, totalPages) = parsePagination(document, requestedPage)

        return SearchResultPage(
            query = query,
            requestedCategory = requestedCategory,
            selectedCategory = selectedPivot,
            pivotCategories = pivotCategories,
            topics = topics,
            currentPage = currentPage,
            totalPages = totalPages,
        )
    }

    /**
     * Pivot dropdown lives in `div.search select[name=cat]`. Each option's
     * `value` is `<catId>*hfr.inc` ; the parser tolerates other `*<config>`
     * suffixes for forward-compat (HFR could rename `hfr.inc` to `hfr.inc.v2`
     * without breaking the contract). Walks all `<optgroup>` blocks since HFR
     * could one day surface multiple communities under the same select.
     */
    private fun parsePivotCategories(document: org.jsoup.nodes.Document): List<SearchPivotCategory> {
        val select = document.selectFirst("div.search select[name=cat]") ?: return emptyList()
        return select.select("option").mapNotNull { option ->
            val rawValue = option.attr("value")
            val catId = PIVOT_OPTION_VALUE_REGEX.find(rawValue)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            SearchPivotCategory(
                id = catId,
                label = option.text().trim(),
                isSelected = option.hasAttr("selected"),
            )
        }
    }

    private fun parseTopicRow(row: Element, cat: Int, index: Int): SearchTopicResult {
        val titleAnchor = requireTopicAnchor(row, index)
        val href = requireTopicHref(titleAnchor, index)
        val topicId = requireTopicId(href, titleAnchor.attr("title"), index)
        val (categorySlug, subcategorySlug) = extractSlugs(href)
        // Lock indicator : `<img src="…/lock.gif" title="Sujet fermé" />` placed
        // INSIDE `td.sujetCase3`, BEFORE the anchor. Historical bug from the
        // legacy v1 client : detecting via `closedm.gif` in sujetCase1 misses
        // the modern lock icon. Anchor on the filename suffix instead.
        val isLocked = row.select("td.sujetCase3 img[src$=lock.gif]").isNotEmpty()
        val title = titleAnchor.text()
        val author = row.selectFirst("td.sujetCase6")?.text().orEmpty().trim()
        val replyCount = row.selectFirst("td.sujetCase7")?.text()?.trim()?.toIntOrNull() ?: 0
        val viewCount = row.selectFirst("td.sujetCase8")?.text()?.trim()?.toIntOrNull() ?: 0
        val lastReplyCell = row.selectFirst("td.sujetCase9 a.Tableau")
        val (lastReplyAt, lastReplyAuthor) = parseLastReply(lastReplyCell)
        val matchedPost = parseMatchedPost(row)

        return SearchTopicResult(
            cat = cat,
            topicId = topicId,
            title = title,
            author = author,
            replyCount = replyCount,
            viewCount = viewCount,
            lastReplyAt = lastReplyAt,
            lastReplyAuthor = lastReplyAuthor,
            topicUrl = href,
            categorySlug = categorySlug,
            subcategorySlug = subcategorySlug,
            isLocked = isLocked,
            // Title-only rows don't carry this second anchor. Content-search
            // rows can expose the matching post via `forum2.php?...numreponse=`.
            page = matchedPost?.page,
            numreponse = matchedPost?.numreponse,
            matchedExcerpt = matchedPost?.excerpt,
        )
    }

    private fun requireTopicAnchor(row: Element, index: Int): Element =
        row.selectFirst("td.sujetCase3 a.cCatTopic")
            ?: throw ParseException("Search topic row #$index is missing td.sujetCase3 a.cCatTopic.")

    private fun requireTopicHref(titleAnchor: Element, index: Int): String =
        titleAnchor.attr("href").takeIf { it.isNotBlank() }
            ?: throw ParseException("Search topic row #$index has an empty topic href.")

    private fun requireTopicId(href: String, title: String, index: Int): Int =
        extractTopicId(href, title)
            ?: throw ParseException("Search topic row #$index has no topic id in href/title.")

    /**
     * `<a class="Tableau" href="…#bas">22-05-2026&nbsp;à&nbsp;06:48<br /><b>Lt Ripley</b></a>`
     * — date is the leading text node, author is the `<b>` child. NBSPs are
     * normalised to plain spaces so downstream consumers don't have to.
     */
    private fun parseLastReply(anchor: Element?): Pair<String, String> {
        if (anchor == null) return "" to ""
        val author = anchor.selectFirst("b")?.text()?.trim().orEmpty()
        // Strip the trailing `<b>...</b>` then normalise NBSP.
        val rawDate = anchor.html().substringBefore("<b>").substringBefore("<br")
        val date = Jsoup.parse(rawDate).text().replace(NBSP, ' ').trim()
        return date to author
    }

    /**
     * Content-search rows add a second link below the title:
     * `forum2.php?...page=N&numreponse=M` wrapping a `.citation` snippet.
     * Plain title-search rows do not have this block, so absence is expected.
     */
    private fun parseMatchedPost(row: Element): MatchedPost? {
        val matchAnchor: Element? = row.selectFirst("td.sujetCase3 a[href*=numreponse]")
        return matchAnchor?.let { anchor ->
            val href = anchor.attr("href")
            val numreponse = QUERY_NUMREPONSE_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it > 0 }
            val page = QUERY_PAGE_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it > 0 }
            val excerpt = anchor.selectFirst("div.citation span.s1")?.text()?.trim()?.ifBlank { null }
            numreponse?.let { MatchedPost(page = page, numreponse = it, excerpt = excerpt) }
        }
    }

    /**
     * Pagination header lives in `tr.fondForum1PagesHaut`. The current page is
     * the `<b>` immediately following the `Page :` label ; other pages (when
     * any) are anchor links in the same row.
     *
     * For the MVP fixtures all pages have a single result page, so the parser
     * returns `(1, 1)` reliably. A multi-page fixture can refine this without
     * breaking callers.
     */
    private fun parsePagination(
        document: org.jsoup.nodes.Document,
        requestedPage: Int,
    ): Pair<Int, Int> {
        val pagerRow = document.selectFirst("tr.fondForum1PagesHaut") ?: return requestedPage to requestedPage
        val bolds = pagerRow.select("div.left b").mapNotNull { it.text().trim().toIntOrNull() }
        val current = bolds.firstOrNull() ?: requestedPage
        val linkPages = pagerRow.select("a").mapNotNull { it.text().trim().toIntOrNull() }
        val maxPage = (linkPages + current).maxOrNull() ?: current
        return current to maxPage
    }

    /**
     * Topic id is encoded in the URL as `…-sujet_<id>_<page>.htm`. Fall back to
     * the anchor's `title` attribute (`Sujet n°<id>`) which HFR also serves on
     * every row, in case a future URL refactor breaks the slug pattern.
     */
    private fun extractTopicId(href: String, title: String): Int? {
        TOPIC_ID_URL_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return TOPIC_ID_TITLE_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts `(categorySlug, subcategorySlug)` from `/hfr/<cat>/<subcat>/<slug>-sujet_<id>_<page>.htm`.
     * Both may legitimately be null if the URL doesn't match the pattern.
     */
    private fun extractSlugs(href: String): Pair<String?, String?> {
        val match = SLUG_REGEX.find(href) ?: return null to null
        return match.groupValues[1] to match.groupValues[2]
    }

    class ParseException(message: String) : RuntimeException(message)

    private data class MatchedPost(
        val page: Int?,
        val numreponse: Int,
        val excerpt: String?,
    )

    private companion object {
        const val NO_RESULTS_MARKER = "Désolé, aucune réponse n'a été trouvée"
        const val NBSP = ' '

        // `1*hfr.inc` → 1 ; tolerates other `*<config>` suffixes for forward-compat.
        val PIVOT_OPTION_VALUE_REGEX = Regex("""^(\d+)\*[A-Za-z0-9_.-]+$""")
        // `…-sujet_148695_1.htm` → 148695.
        val TOPIC_ID_URL_REGEX = Regex("""-sujet_(\d+)_\d+\.htm""")
        // `Sujet n°148695` → 148695. Lenient on the `°` character to allow
        // variants from older HFR servers.
        val TOPIC_ID_TITLE_REGEX = Regex("""Sujet\s*n.?\s*(\d+)""")
        // `/hfr/<cat>/<subcat>/<slug>-sujet_<id>_<page>.htm`.
        val SLUG_REGEX = Regex("""^/hfr/([^/]+)/([^/]+)/[^/]+\.htm""")
        val QUERY_PAGE_REGEX = Regex("""[?&]page=(\d+)""")
        val QUERY_NUMREPONSE_REGEX = Regex("""[?&]numreponse=(\d+)""")
    }
}
