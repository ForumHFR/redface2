package fr.forumhfr.redface2.core.parser.messages

import org.jsoup.Jsoup

/**
 * #612 — extracts the REAL « répondre » link a private-message conversation page
 * (`forum2.php?cat=prive&post={threadId}`) embeds, so the write repository can follow it to the
 * dedicated `message.php` reply form.
 *
 * Why this exists : the conversation page embeds a *quick-reply* form whose action is
 * `bddpost.php` and which carries **no** `newdest` field. HFR only serves the owner-only `newdest`
 * (the full member list of a DT / MultiMP) on the standalone `message.php` reply form — reachable
 * via the « Ajouter une réponse » button. That button (and its sibling `repondre_form`) carries
 * the real `message.php` href, with the server-controlled `numrep` / `ref` / `page` already filled
 * in. We never invent those params : we forward the href HFR rendered, verbatim.
 *
 * Two equivalent sources on the page, in priority order :
 *  1. `<form id="repondre_form" action="/message.php?…">` — the most stable anchor.
 *  2. The `<a … href="/message.php?…">` wrapping the `repondre.gif` button (same URL).
 *
 * The returned value is the href EXACTLY as HFR wrote it (an HTML-entity-decoded relative path such
 * as `/message.php?config=hfr.inc&cat=prive&post=3195237&page=1&p=1&subcat=0&sondage=0&owntopic=0&new=0`).
 * The network layer resolves it against the base URL and guards it (`message.php`, `cat=prive`).
 * Returns `null` when no such link is present — a one-to-one MP, a session that lost the writable
 * form, or a page shape HFR reshaped — and the caller falls back to the embedded quick-reply form.
 */
class PrivateMessageReplyLinkParser {

    fun parse(html: String): String? {
        val document = Jsoup.parse(html)

        // 1. The reply form's action is the canonical, most stable source. Jsoup decodes the
        //    `&amp;` entities for us; we take the raw `action` attribute (a relative `/message.php…`
        //    path), not the resolved absolute URL, so the network layer keeps full control of the host.
        document.selectFirst(REPLY_FORM_SELECTOR)
            ?.attr("action")
            ?.takeIf { it.isMessagePhpReplyLink() }
            ?.let { return it }

        // 2. Fallback : the « Ajouter une réponse » anchor (repondre.gif) carries the same href.
        return document.select(MESSAGE_PHP_ANCHOR_SELECTOR)
            .firstOrNull { it.selectFirst("img[src*=repondre.gif]") != null }
            ?.attr("href")
            ?.takeIf { it.isMessagePhpReplyLink() }
    }

    /**
     * A defensive shape check : the link must target `message.php` and the private category. This
     * keeps the parser from ever returning, say, the search or print link. The network layer
     * re-validates host + path before following it.
     */
    private fun String.isMessagePhpReplyLink(): Boolean =
        contains("message.php") && contains("cat=prive")

    private companion object {
        // The conversation page's reply form posts to message.php (NOT the bddpost.php quick-reply).
        const val REPLY_FORM_SELECTOR = "form#repondre_form[action*=message.php]"
        const val MESSAGE_PHP_ANCHOR_SELECTOR = "a[href*=message.php]"
    }
}
