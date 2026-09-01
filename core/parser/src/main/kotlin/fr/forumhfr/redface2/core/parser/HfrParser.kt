package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.parser.profile.ProfileParser
import fr.forumhfr.redface2.core.parser.staff.StaffParser
import org.jsoup.Jsoup

class HfrParser(
    private val topicPageParser: TopicPageParser = TopicPageParser(),
    private val bbcodeContentParser: BbcodeContentParser = BbcodeContentParser(),
    private val profileParser: ProfileParser = ProfileParser(),
    private val topicSearchFormParser: TopicSearchFormParser = TopicSearchFormParser(),
    private val staffParser: StaffParser = StaffParser(),
    private val postsParser: PostsParser = PostsParser(),
) {
    fun parseTopicPage(html: String): Topic = topicPageParser.parse(html)

    /**
     * #783 — parses HFR's `quote_only=1` response through the shared topic/MP post extractor.
     * The response is a filtered topic page with ordinary `messagetable` rows; only its page-level
     * metadata is special. HFR may deduplicate rows independently from the citation counter, so the
     * returned list size is deliberately not compared with `Post.citedCount`.
     */
    fun parseCitingPosts(html: String): List<Post> {
        val document = Jsoup.parse(html)
        CryptlinkDecoder.materialize(document)
        return postsParser.parsePosts(document)
    }

    /**
     * Chantier C (#546) — extracts the intra-topic search form (`transsearch.php`) hidden fields
     * (`hash_check`, `post`, `cat`, `firstnum`, …) from a loaded topic page so the data layer can
     * build the search POST. Returns `null` when the page carries no usable search form. The
     * `transsearch` RESPONSE is itself a topic page and is re-parsed with [parseTopicPage].
     */
    fun parseTopicSearchForm(html: String): TopicSearchForm? = topicSearchFormParser.parse(html)

    /**
     * Phase 2 finish (#208) — parses a HFR user profile page (`/hfr/profil-{userId}.htm`)
     * into a [UserProfile]. [userId] is the numeric id used to build the request URL;
     * it is threaded through to the model as the canonical navigation key.
     */
    fun parseUserProfile(html: String, userId: Int): UserProfile =
        profileParser.parse(html, userId)

    /**
     * Rôle HFR (#1112, #221 — PR A) — source **secondaire** : extrait le champ « Statut » d'une
     * page profil (`/hfr/profil-{userId}.htm`) en [AuthorRole]. Retourne `null` quand le statut est
     * absent, vide ou non reconnu (résultat HTTP valide, à ne pas confondre avec un échec réseau —
     * la distinction est portée par le repository). Voir [ProfileParser.parseAuthorRole].
     */
    fun parseAuthorRole(html: String): AuthorRole? =
        profileParser.parseAuthorRole(html)

    /**
     * Rôle HFR (#1112, #221 — PR A) — source **primaire** : parse l'annuaire staff GLOBAL
     * (réponse de `message-smi-mp-aj.php?responsable=1`) en `pseudo brut -> AuthorRole`. Les libellés
     * inconnus sont ignorés ; les pseudos sont **bruts** (canonicalisation faite par le repository).
     * Voir [StaffParser].
     */
    fun parseStaffList(html: String): Map<String, AuthorRole> =
        staffParser.parse(html)

    /**
     * Best-effort BBCode → [PostContent] for the Phase 2B editor preview. Tolerant by
     * design: malformed or unknown markup degrades to plain text so user input never
     * crashes the renderer. See `BbcodeContentParser` for the supported subset and
     * `docs/specs/architecture.md` for the documented contract.
     */
    fun parsePostContentFromBbcode(bbcode: String): PostContent =
        bbcodeContentParser.parse(bbcode)
}
