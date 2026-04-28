package fr.forumhfr.redface2.core.parser.messages

import org.jsoup.Jsoup

/**
 * Parses the HFR private message list page (`forum1.php?config=hfr.inc&cat=prive`) and
 * counts unread MPs.
 *
 * Each MP is rendered as a `<tr class="sujet ligne_booleen ...">` row. The icon in the
 * first cell (`td.sujetCase1 img`) carries the read/unread state via its filename:
 * - `closedp.gif`  → MP read by the current user
 * - `closedbp.gif` → MP unread by the current user (the `b` is the bold/new marker)
 *
 * Convention extracted from the legacy v1 client (`HTMLToPrivateMessageList.java:31-32`),
 * proven in production for ~10 years against forum.hardware.fr.
 */
class PrivateMessageListParser {

    /**
     * Counts unread MPs in the page HTML. Returns 0 when the page contains no MP rows
     * (e.g. an empty inbox or a redirect to login — in the latter case the caller is
     * expected to detect the unauthenticated response upstream).
     */
    fun countUnread(html: String): Int {
        val document = Jsoup.parse(html)
        return document.select("tr.sujet img[src]")
            .count { img ->
                val src = img.attr("src")
                val filename = src.substringAfterLast('/').substringBeforeLast('.')
                filename == UNREAD_ICON
            }
    }

    private companion object {
        const val UNREAD_ICON = "closedbp"
    }
}
