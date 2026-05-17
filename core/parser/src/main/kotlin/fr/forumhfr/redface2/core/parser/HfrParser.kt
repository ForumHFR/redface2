package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic

class HfrParser(
    private val topicPageParser: TopicPageParser = TopicPageParser(),
    private val bbcodeContentParser: BbcodeContentParser = BbcodeContentParser(),
) {
    fun parseTopicPage(html: String): Topic = topicPageParser.parse(html)

    /**
     * Best-effort BBCode → [PostContent] for the Phase 2B editor preview. Tolerant by
     * design: malformed or unknown markup degrades to plain text so user input never
     * crashes the renderer. See `BbcodeContentParser` for the supported subset and
     * `docs/specs/architecture.md` for the documented contract.
     */
    fun parsePostContentFromBbcode(bbcode: String): PostContent =
        bbcodeContentParser.parse(bbcode)
}
