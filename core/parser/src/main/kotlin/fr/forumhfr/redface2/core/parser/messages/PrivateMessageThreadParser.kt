package fr.forumhfr.redface2.core.parser.messages

import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.parser.CryptlinkDecoder
import fr.forumhfr.redface2.core.parser.PostsParser
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.Jsoup

/**
 * Parses one page of a private-message conversation (`forum2.php?config=hfr.inc&cat=prive&post={threadId}`).
 *
 * An MP thread page is structurally a topic page, so the message list and pagination are
 * delegated to the shared [PostsParser]. The page-level metadata differs: there is no numeric
 * category (`input[name=cat]` is the literal string `"prive"`, which is exactly why an MP
 * thread cannot reuse [fr.forumhfr.redface2.core.model.Topic] — its `cat` is an `Int`), the
 * title `h3` carries the conversation subject, and the conversation has a single correspondent.
 */
class PrivateMessageThreadParser(
    private val postsParser: PostsParser = PostsParser(),
) {
    /**
     * @param html the raw `forum2.php?cat=prive` page.
     * @param fallbackCorrespondent the correspondent carried from the inbox row, used when the
     *   thread page cannot reveal it on its own (the current user is the only sender, so no
     *   message authored by the other participant is present on this page).
     */
    fun parse(html: String, fallbackCorrespondent: String? = null): PrivateMessageThread {
        val document = Jsoup.parse(html)
        // Same anti-aspirateur obfuscation as topics — recover the toolbar links so the shared
        // PostsParser can read the per-message edit/quote anchors. No-op on a clear page.
        CryptlinkDecoder.materialize(document)

        val pageInfo = postsParser.parsePageInfo(document)
        val messages = postsParser.parsePosts(document)

        val threadId = document.selectFirst(HfrSelectors.TOPIC_ID_INPUT)
            ?.attr("value")
            ?.toIntOrNull()
            ?: error("Private message thread id not found")
        val subject = document.selectFirst(HfrSelectors.TOPIC_TITLE)?.text()?.trim().orEmpty()
        val canReply = document.selectFirst(REPLY_FORM_SELECTOR) != null

        // The correspondent is the first message NOT authored by the current user. HFR marks
        // the current user's own messages with an edit link (`isOwnPost`); the other
        // participant's never carry one. When the user is the only sender on this page (the
        // correspondent has not replied yet), fall back to the inbox-row value, then to the
        // first author as a last resort.
        val correspondent = messages.firstOrNull { !it.isOwnPost }?.author
            ?: fallbackCorrespondent
            ?: messages.firstOrNull()?.author
            ?: ""

        return PrivateMessageThread(
            threadId = threadId,
            subject = subject,
            correspondent = correspondent,
            messages = messages,
            page = pageInfo.current,
            totalPages = pageInfo.total,
            canReply = canReply,
        )
    }

    private companion object {
        // Mirrors TopicPageParser: the reply form posts to `/bddpost.php`. Its presence is the
        // signal that HFR rendered a writable thread for the current authenticated session.
        const val REPLY_FORM_SELECTOR = "form[action*=bddpost.php]"
    }
}
