package fr.forumhfr.redface2.core.parser.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.parser.common.HfrDateParser
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses the HFR private message list page (`forum1.php?config=hfr.inc&cat=prive`).
 *
 * Each MP is rendered as a `<tr class="sujet …">` row whose cells carry, in order, the
 * read/unread icon (`td.sujetCase1`), the subject link embedding the thread `post` id
 * (`td.sujetCase3 a.cCatTopic`), the correspondent (`td.sujetCase6`) and the last-activity
 * date (`td.sujetCase9`). The icon filename encodes the read state:
 * - `closedp.gif`  → MP read by the current user
 * - `closedbp.gif` → MP unread by the current user (the `b` is the bold/new marker)
 *
 * Convention extracted from the legacy v1 client (`HTMLToPrivateMessageList.java:31-32`),
 * proven in production for ~10 years against forum.hardware.fr.
 */
class PrivateMessageListParser(
    private val dateParser: HfrDateParser = HfrDateParser(),
) {

    /**
     * Counts unread MPs in the page HTML. Returns 0 when the page contains no MP rows
     * (e.g. an empty inbox or a redirect to login — in the latter case the caller is
     * expected to detect the unauthenticated response upstream).
     */
    fun countUnread(html: String): Int {
        val document = Jsoup.parse(html)
        return document.select(HfrSelectors.MP_LIST_ROW)
            .count { row ->
                row.selectFirst(HfrSelectors.MP_LIST_ICON)?.attr("src")?.let(::isUnreadIcon) == true
            }
    }

    /**
     * Parses the full inbox page into a [PrivateMessageListPage]: the conversations on this
     * page plus the pagination read from the Forum1 pager. Rows that are not MP entries
     * (no subject link / no thread id) are skipped, so an empty or login-redirect page yields
     * an empty item list.
     */
    fun parseList(html: String): PrivateMessageListPage {
        val document = Jsoup.parse(html)
        val items = document.select(HfrSelectors.MP_LIST_ROW)
            .mapNotNull { row -> parseRow(row) }
        val (current, total) = parsePageInfo(document)
        return PrivateMessageListPage(page = current, totalPages = total, items = items)
    }

    private fun parseRow(row: Element): PrivateMessageSummary? {
        val subjectLink = row.selectFirst(HfrSelectors.MP_LIST_SUBJECT_LINK)
        val threadId = subjectLink
            ?.let { THREAD_ID_REGEX.find(it.attr("href")) }
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
        val date = dateParser.parseListDateOrNull(
            row.selectFirst(HfrSelectors.MP_LIST_DATE)?.text().orEmpty(),
        )
        // A row without a subject link / parseable thread id / parseable date is not a usable
        // MP entry (header, separator, login redirect, DOM drift) — skip it rather than crash
        // the whole inbox. The null-checks fold into one guard so the happy path keeps a single
        // trailing return (detekt ReturnCount).
        if (subjectLink == null || threadId == null || date == null) return null

        // Interlocuteur cell, three shapes:
        // - profile link (`<a>`)               → one-to-one, correspondent = link text.
        // - "Interlocuteurs multiples" `<span>` → multi-recipient (MultiMP / "DT"); HFR
        //   truncates the participant list, so [correspondent] stays empty and the UI shows a
        //   localized label. Keyed on the exact marker text, NOT on "any span", so an
        //   anchor-less plain-text pseudo (banned / anonymized correspondent) is not misread.
        // - bare text `<span>` (no link)        → one-to-one with a non-clickable pseudo,
        //   correspondent = span text (the pseudo is already public in the listing).
        val correspondentLink = row.selectFirst(HfrSelectors.MP_LIST_CORRESPONDENT)
        val groupText = row.selectFirst(HfrSelectors.MP_LIST_CORRESPONDENT_GROUP)?.text()?.trim()
        val isMultiRecipient = correspondentLink == null &&
            groupText != null &&
            groupText.equals(MULTI_RECIPIENT_MARKER, ignoreCase = true)
        val correspondent = when {
            correspondentLink != null -> correspondentLink.text().trim()
            isMultiRecipient -> ""
            else -> groupText.orEmpty()
        }
        val hasUnread = row.selectFirst(HfrSelectors.MP_LIST_ICON)
            ?.attr("src")
            ?.let(::isUnreadIcon)
            ?: false

        // "Pages" cell (#430): a link to the conversation's last page, rendered by HFR only for
        // multi-page conversations — its absence means the conversation fits on one page.
        val lastPage = row.selectFirst(HfrSelectors.MP_LIST_LAST_PAGE_LINK)
            ?.let { PAGE_REGEX.find(it.attr("href")) }
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1

        return PrivateMessageSummary(
            threadId = threadId,
            correspondent = correspondent,
            subject = subjectLink.text().trim(),
            date = date,
            hasUnread = hasUnread,
            isMultiRecipient = isMultiRecipient,
            lastPage = lastPage,
        )
    }

    private fun parsePageInfo(document: Document): Pair<Int, Int> {
        val pagerLeft = document
            .select(HfrSelectors.MP_LIST_TOP_PAGER)
            .firstOrNull()
            ?.selectFirst(HfrSelectors.TOP_PAGER_LEFT)

        val current = pagerLeft
            ?.select(HfrSelectors.TOP_PAGER_CURRENT)
            ?.mapNotNull { it.text().trim().toIntOrNull() }
            ?.lastOrNull()
            ?: 1
        // Page-number links are `a.cHeader` on page 1 but obfuscated `span.md_cryptlink…` on
        // authenticated pages 2+. Read BOTH shapes, otherwise the max linked page collapses to the
        // current page from page 2 on and `totalPages` is under-reported (a paged inbox scan would
        // then stop after page 2 — MPStorage discovery #6).
        val linkedPages = pagerLeft
            ?.select("${HfrSelectors.TOP_PAGER_LINK}, ${HfrSelectors.MP_LIST_PAGER_CRYPTLINK}")
            ?.mapNotNull { it.text().trim().toIntOrNull() }
            .orEmpty()
        val total = maxOf(current, linkedPages.maxOrNull() ?: current)
        return current to total
    }

    private fun isUnreadIcon(src: String): Boolean =
        src.substringAfterLast('/').substringBeforeLast('.') == UNREAD_ICON

    private companion object {
        const val UNREAD_ICON = "closedbp"
        // HFR's exact label shown in the Interlocuteur cell of a multi-recipient conversation
        // (MultiMP / "DT"), in place of a profile link.
        const val MULTI_RECIPIENT_MARKER = "Interlocuteurs multiples"
        val THREAD_ID_REGEX = Regex("""[?&]post=(\d+)""")
        val PAGE_REGEX = Regex("""[?&]page=(\d+)""")
    }
}
