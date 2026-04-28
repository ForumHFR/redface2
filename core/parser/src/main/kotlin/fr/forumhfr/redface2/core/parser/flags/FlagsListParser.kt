package fr.forumhfr.redface2.core.parser.flags

import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses the HFR drapeaux page (`forum1f.php?config=hfr.inc&owntopic=N`).
 *
 * Each filter view (owntopic=1, 2 or 3) returns a different category of drapeau, so
 * callers MUST pass the [FlagType] they queried — there is no reliable way to recover
 * the type from the row alone, since:
 *
 * - `td.sujetCase5` carries the flag icon (`flag0/flag1/favoris.gif`) **only when the
 *   topic has unread posts** — for read topics, the cell is `&nbsp;` with no icon.
 * - The unread/read state itself is canonically encoded on `td.sujetCase1` via
 *   `closedb.gif` (unread) or `closed.gif` (all read).
 *
 * When an icon IS present on `sujetCase5`, it can occasionally disagree with the
 * filter URL (e.g. a `favoris.gif` showing up in an `owntopic=1` capture for legacy
 * reasons). In that case the icon wins — the user has explicitly classified that
 * topic as a favorite, and we surface it as such.
 */
class FlagsListParser {

    /**
     * @param html raw HTML of the drapeaux page.
     * @param defaultType the flag type implied by the listing URL — used as a fallback
     *   for rows whose `sujetCase5` is empty (read topics) and for the owntopic=N hint
     *   in general.
     */
    fun parse(html: String, defaultType: FlagType): List<Flag> {
        val document = Jsoup.parse(html)
        return document.select("tr.sujet.ligne_booleen").mapNotNull { row ->
            parseRow(row, defaultType)
        }
    }

    @Suppress("ReturnCount")
    private fun parseRow(row: Element, defaultType: FlagType): Flag? {
        val titleAnchor = row.selectFirst("td.sujetCase3 a.cCatTopic") ?: return null
        val title = titleAnchor.text().trim().ifEmpty { return null }
        val topicHref = titleAnchor.attr("href")
        val topicId = titleAnchor.attr("title")
            .removePrefix("Sujet n°")
            .toIntOrNull() ?: return null
        val cat = topicHref.queryParam("cat")?.toIntOrNull() ?: return null
        val subcat = topicHref.queryParam("subcat")?.toIntOrNull()
        // td.sujetCase4 is the "Dern. page" column — its anchor text is the topic's last
        // page number, NOT the reply count. The actual reply count lives on td.sujetCase7
        // ("Rép." header) and the view count on td.sujetCase8 ("Lues" header).
        val totalPages = row.selectFirst("td.sujetCase4 a")?.text()?.toIntOrNull() ?: 0

        // Canonical unread signal: line marker on sujetCase1.
        val lineMarker = row.selectFirst("td.sujetCase1 img[src]")?.iconName()
        val hasUnread = lineMarker?.startsWith("closedb") == true

        // Type defaults to the listing's filter, overridden when the row carries an
        // explicit flag icon that disagrees (rare but seen in production captures).
        val flagAnchor = row.selectFirst("td.sujetCase5 a[href]")
        val flagIcon = row.selectFirst("td.sujetCase5 img[src]")?.iconName()
        val type = flagIcon?.let(::iconToType) ?: defaultType

        val flagHref = flagAnchor?.attr("href").orEmpty()
        val lastReadPage = flagHref.queryParam("page")?.toIntOrNull()
            ?: if (hasUnread) 1 else totalPages.coerceAtLeast(1)
        val firstUnreadPostId = flagHref.fragment("t")?.toLongOrNull() ?: 0L

        val firstPostAuthor = row.selectFirst("td.sujetCase6 a.Tableau")?.text().orEmpty()
        val replyCount = row.selectFirst("td.sujetCase7")?.text()?.replace(" ", "")?.toIntOrNull() ?: 0
        val views = row.selectFirst("td.sujetCase8")?.text()?.replace(" ", "")?.toIntOrNull() ?: 0
        val lastReplyAt = row.selectFirst("td.sujetCase9 a")?.ownText()?.trim().orEmpty()
        val lastReplyAuthor = row.selectFirst("td.sujetCase9 a b")?.text().orEmpty()

        return Flag(
            cat = cat,
            subcat = subcat,
            topicId = topicId,
            title = title,
            totalPages = totalPages,
            replyCount = replyCount,
            views = views,
            type = type,
            hasUnread = hasUnread,
            lastReadPage = lastReadPage,
            firstUnreadPostId = firstUnreadPostId,
            firstPostAuthor = firstPostAuthor,
            lastReplyAuthor = lastReplyAuthor,
            lastReplyAt = lastReplyAt,
        )
    }

    private fun iconToType(name: String): FlagType? = when (name) {
        "flag0", "flagn0" -> FlagType.CYAN
        "flag1", "flagn1" -> FlagType.RED
        "favoris", "favorisn" -> FlagType.FAVORITE
        else -> null
    }

    private fun Element.iconName(): String? {
        val src = attr("src").ifEmpty { return null }
        return src.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun String.queryParam(name: String): String? {
        val pattern = "[?&]$name=([^&#]*)".toRegex()
        return pattern.find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.fragment(name: String): String? {
        val hashIndex = indexOf('#').takeIf { it >= 0 } ?: return null
        val fragment = substring(hashIndex + 1)
        return fragment.removePrefix(name).takeIf { it.length < fragment.length }
    }
}
