package fr.forumhfr.redface2.core.parser.forum

import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses an HFR topic-list page (`forum1.php?config=hfr.inc&cat=X&subcat=Y&page=Z`) into
 * a [TopicListPage]. The column mapping (`td.sujetCase{1..9}`) is identical to the
 * drapeaux page (`FlagsListParser`) — same HFR rendering pipeline — so a couple of small
 * helpers (`digitsOnly`, icon name extraction) are duplicated here rather than hoisted
 * into a shared util whose abstraction would feel artificial.
 *
 * Notable shape differences from the drapeaux page :
 *
 * - The cat ID lives in `<input type="hidden" name="cat" value="X">` ; there is no
 *   `&cat=` in the topic anchor's href when mod_rewrite slugs are used. The hidden
 *   input is the **only** authoritative source for the numeric cat ID at this level.
 * - The subcat hidden input is **absent** when listing all subcategories
 *   (`subcat=0`) — we model that by `subcat = null` in the resulting page.
 * - Pagination uses both anchors (`<a href="liste_sujet-N.htm">N</a>`) and obfuscated
 *   `<span class="md_cryptlinkXXXX">N</span>` placeholders (HFR's anti-scraping for the
 *   "next/prev" CTAs). We extract page numbers from both — the URL of the request page
 *   itself is reflected as `<b>N</b>` (no anchor), which gives us `currentPage`.
 * - `td.sujetCase5` (drapeau column) is empty (`&nbsp;`) when fetched anonymously, so
 *   we don't read it here at all (kept on `Flag` for the authenticated drapeaux page).
 */
class TopicListParser {

    fun parse(html: String): TopicListPage {
        val document = Jsoup.parse(html)
        val cat = document.selectFirst("input[type=hidden][name=cat]")?.attr("value")?.toIntOrNull()
            ?: error("Missing <input name=\"cat\"> in topic-list HTML")
        val subcat = document.selectFirst("input[type=hidden][name=subcat]")?.attr("value")?.toIntOrNull()
            ?.takeIf { it > 0 }
        val (currentPage, totalPages) = parsePagination(document)
        // Selector covers both legacy markup (`tr.sujet.ligne_booleen`) and any future
        // variant where additional classes wedge between `sujet` and `ligne_*` —
        // `tr.sujet` is enough provided we drop rows missing the title anchor (header
        // rows or the legend at the bottom of the page).
        val topics = document.select("tr.sujet").mapNotNull { row -> parseRow(row, cat, subcat) }
        return TopicListPage(
            cat = cat,
            subcat = subcat,
            currentPage = currentPage,
            totalPages = totalPages,
            topics = topics,
        )
    }

    @Suppress("ReturnCount")
    private fun parseRow(row: Element, cat: Int, subcat: Int?): TopicSummary? {
        val titleAnchor = row.selectFirst("td.sujetCase3 a.cCatTopic") ?: return null
        val title = titleAnchor.text().trim().ifEmpty { return null }
        // Sujet n°<post> — explicit and stable; falls back on the slug-encoded
        // `sujet_<post>_<page>.htm` href when the title attribute is absent (defensive,
        // current fixtures always set it).
        val post = titleAnchor.attr("title")
            .removePrefix("Sujet n°")
            .toIntOrNull()
            ?: SUJET_HREF.find(titleAnchor.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        // td.sujetCase4 is the "Dern. page" anchor — its text is the topic's last page.
        // coerceAtLeast(1) so a single-page topic still surfaces "1/1" in the UI.
        val totalPages = (row.selectFirst("td.sujetCase4 a")?.text()?.toIntOrNull() ?: 1)
            .coerceAtLeast(1)

        // sujetCase6 may carry either a username text node (anonymous capture) OR an
        // <a class="Tableau"> wrapping the username (authenticated capture, where HFR
        // turns the author into a profile link). Try the anchor first; fall back to the
        // raw text. ifBlank protects against a moderator-redacted author.
        val firstPostAuthor = row.selectFirst("td.sujetCase6 a.Tableau")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: row.selectFirst("td.sujetCase6")?.text()?.trim().orEmpty()

        val replyCount = row.selectFirst("td.sujetCase7")?.text()?.digitsOnly() ?: 0
        val views = row.selectFirst("td.sujetCase8")?.text()?.digitsOnly() ?: 0

        val lastReplyAnchor = row.selectFirst("td.sujetCase9 a")
        val lastReplyAt = lastReplyAnchor?.ownText()?.trim().orEmpty()
        val lastReplyAuthor = lastReplyAnchor?.selectFirst("b")?.text().orEmpty()

        // Status icon on td.sujetCase1: `closedb_new.gif` = new topic, `closedb.gif` =
        // new posts in old topic, `closed.gif` = no new posts, `closedm.gif`/`closedm_new.gif` =
        // locked. The legend at the bottom of the page (cf. forum_root and topic_list
        // fixtures) confirms this naming. `closedm` is not present in the captured
        // page1/page20 fixtures (no locked sticky in Discussions at capture time) —
        // the lock detection is implemented by name pattern as documented by HFR's
        // legend so that future fixtures with locked rows parse correctly without a
        // re-capture.
        val statusIcon = row.selectFirst("td.sujetCase1 img[src]")?.iconName()
        // hasUnread covers "Nouveaux sujets" (closedb_new) AND "Nouveaux messages dans
        // un ancien sujet" (closedb without _new) per HFR's own legend at the bottom of
        // the topic-list page. `closed` (no `b`) is the explicit "Pas de nouveau message"
        // marker.
        val hasUnread = statusIcon == "closedb" || statusIcon?.endsWith("_new") == true
        val isLocked = statusIcon?.startsWith("closedm") == true

        val isSticky = row.classNames().contains("ligne_sticky")

        return TopicSummary(
            cat = cat,
            subcat = subcat,
            post = post,
            title = title,
            firstPostAuthor = firstPostAuthor,
            replyCount = replyCount,
            views = views,
            totalPages = totalPages,
            lastReplyAuthor = lastReplyAuthor,
            lastReplyAt = lastReplyAt,
            isSticky = isSticky,
            isLocked = isLocked,
            hasUnread = hasUnread,
        )
    }

    /**
     * Returns `(currentPage, totalPages)`. `currentPage` is the page number wrapped in
     * `<b>` without an `<a>` parent inside the pagination block. `totalPages` is the
     * maximum page number observed across :
     *
     * - regular anchors `<a href=".../liste_sujet-N.htm">N</a>`,
     * - obfuscated `<span class="md_cryptlinkXXXX">N</span>` placeholders (HFR's
     *   anti-scraping wrapper for the prev/next/extreme-pages CTAs).
     *
     * Falls back to `(1, 1)` if neither block is found — that's the single-page case.
     */
    private fun parsePagination(document: Document): Pair<Int, Int> {
        // The pagination block is the cell that opens with "Page <strong>:</strong>" and
        // hosts the page anchors immediately after it. We look at all liste_sujet-N
        // anchors and md_cryptlink spans across the document — both populate the same
        // block, and there's only one such block per page.
        val anchorPages = document.select("a[href]").mapNotNull { anchor ->
            LISTE_SUJET_HREF.find(anchor.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val cryptPages = document.select("span[class^=md_cryptlink]").mapNotNull { span ->
            span.text().toIntOrNull()
        }
        val all = anchorPages + cryptPages
        val totalPages = all.maxOrNull() ?: 1

        // Current page: HFR wraps it in <b>N</b> sitting *between* siblings of the
        // pagination block. Multiple <b> tags exist on the page (sticky/strong text in
        // titles), so we restrict the search to the page-cell context: any <b> whose
        // text parses as Int AND is followed in the document by at least one of the
        // pagination anchors/spans we just collected. We pick the largest page number
        // that satisfies "appears as <b>" AND "no liste_sujet-N anchor for that N
        // matches it as a sibling" — more pragmatically, since HFR always wraps the
        // current page in <b> and never repeats it as an anchor, we look at all <b>
        // texts that are Int AND not already in `anchorPages`.
        val currentPage = document.select("b").mapNotNull { it.text().toIntOrNull() }
            .firstOrNull { candidate -> candidate !in anchorPages && candidate <= totalPages }
            ?: 1
        return currentPage to totalPages
    }

    private fun Element.iconName(): String? {
        val src = attr("src").ifEmpty { return null }
        return src.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun String.digitsOnly(): Int? = filter(Char::isDigit).toIntOrNull()

    private companion object {
        private val LISTE_SUJET_HREF = Regex("""liste_sujet-(\d+)\.htm""")
        private val SUJET_HREF = Regex("""sujet_(\d+)_\d+\.htm""")
    }
}
