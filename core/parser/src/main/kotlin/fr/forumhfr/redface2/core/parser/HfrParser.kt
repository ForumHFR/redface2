package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.parser.profile.ProfileParser

class HfrParser(
    private val topicPageParser: TopicPageParser = TopicPageParser(),
    private val bbcodeContentParser: BbcodeContentParser = BbcodeContentParser(),
    private val profileParser: ProfileParser = ProfileParser(),
) {
    fun parseTopicPage(html: String): Topic = topicPageParser.parse(html)

    /**
     * Phase 2 finish (#208) — parses a HFR user profile page (`/hfr/profil-{userId}.htm`)
     * into a [UserProfile]. [userId] is the numeric id used to build the request URL;
     * it is threaded through to the model as the canonical navigation key.
     */
    fun parseUserProfile(html: String, userId: Int): UserProfile =
        profileParser.parse(html, userId)

    /**
     * Best-effort BBCode → [PostContent] for the Phase 2B editor preview. Tolerant by
     * design: malformed or unknown markup degrades to plain text so user input never
     * crashes the renderer. See `BbcodeContentParser` for the supported subset and
     * `docs/specs/architecture.md` for the documented contract.
     */
    fun parsePostContentFromBbcode(bbcode: String): PostContent =
        bbcodeContentParser.parse(bbcode)
}
