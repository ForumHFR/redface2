package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.parser.common.HfrDateParser
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Top-pager pagination of a `forum2.php` page. Shared by topic and MP-thread pages.
 */
data class PageInfo(
    val current: Int,
    val total: Int,
)

/**
 * Shared extraction of the post list + pagination from a HFR `forum2.php` page.
 *
 * A topic page and a private-message thread page (`cat=prive`) have the **identical** post
 * DOM (`table.messagetable` rows, the same toolbar, the same `Posté le …` date), so both
 * [TopicPageParser] and [fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser]
 * delegate the per-post and pagination work here instead of duplicating it. The page-level
 * differences (category id for a topic, subject + correspondent for an MP) stay in the
 * respective callers.
 *
 * Contract: [CryptlinkDecoder.materialize] must have already run on the [Document] — the
 * toolbar edit/quote links are recovered from their `md_*cryptlink` spans there, and
 * [parsePosts] relies on the materialized anchors.
 */
class PostsParser(
    private val postContentParser: PostContentParser = PostContentParser(),
    private val dateParser: HfrDateParser = HfrDateParser(),
) {
    fun parsePosts(
        document: Document,
    ): List<Post> {
        val postTables = document
            .select(HfrSelectors.POST_TABLE)
            .filter { postTable -> postTable.selectFirst(HfrSelectors.POST_ANCHOR) != null }

        return postTables.map { postTable ->
            parsePost(
                postTable = postTable,
            )
        }
    }

    private fun parsePost(
        postTable: Element,
    ): Post {
        val content = postContentParser.parse(postTable.selectFirst(HfrSelectors.POST_CONTENT))
        // Phase 2D (#147) : the toolbar exposes an `<a href="…message.php?…
        // &numreponse=…">` only when HFR considers the post editable by the
        // current authenticated user (i.e. it is their own post and the
        // topic is not locked). We treat both flags as equivalent for now —
        // HFR does not distinguish « own but not editable » from « editable »
        // at the topic-page level. Quote (#146) uses `numrep` instead, so
        // these two scopes never collide. Compute once : a 40-post topic page
        // would otherwise re-run the Jsoup selector 80 times for the two
        // identical fields.
        val hasEditLink = parseHasEditLink(postTable)
        return Post(
            numreponse = postTable
                .selectFirst(HfrSelectors.POST_ANCHOR)
                ?.attr("name")
                ?.removePrefix("t")
                ?.toIntOrNull()
                ?: error("Post anchor not found"),
            author = postTable.selectFirst(HfrSelectors.POST_AUTHOR)?.text()?.trim()
                ?: error("Post author not found"),
            date = dateParser.parsePostedAt(
                postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT)?.text().orEmpty(),
            ),
            content = content.ast,
            avatarUrl = postTable.selectFirst(HfrSelectors.POST_AVATAR)?.attr("src"),
            isEditable = hasEditLink,
            isOwnPost = hasEditLink,
            quotedAuthors = content.quotedAuthors,
            // #1055 — reserved legacy field: HFR exposes no proven stable global index here.
            postIndex = null,
            quoteRef = parseQuoteRef(postTable),
            profileId = parseProfileId(postTable),
            editedAt = parseEditedAt(postTable),
            citedCount = parseCitedCount(postTable),
            // #330 — parsed from the same content element as `content` (the signature span is a
            // descendant of `div[id^=para]`, stripped from the body but surfaced here).
            signature = content.signature,
        )
    }

    /**
     * #362 — last-edit timestamp from the post's `div.edited` trailer, or `null` when the
     * post was never edited. The lookup is **scoped to the current post's table** so the
     * `div.edited` of another post on the page can never bleed in. The trailer may carry
     * only the « Message cité N fois » citation link (cited-but-never-edited post) — the
     * date parser returns `null` for that text. Reading the live DOM here is safe even
     * though [PostContentParser] strips `div.edited` from the rendered content: it clones
     * the content element before its `remove()`, so the original document is not mutated.
     */
    private fun parseEditedAt(postTable: Element): Instant? =
        postTable.selectFirst(HfrSelectors.POST_EDITED)
            ?.text()
            ?.let(dateParser::parseEditedAtOrNull)

    /**
     * #863 — HFR's server-side citation counter, read from the SAME `div.edited` trailer as
     * [parseEditedAt] : « Message cité N fois » (a `quote_only=1` link on the web). It counts the
     * citations across the WHOLE topic — the authoritative value the page-scoped client scan could
     * never produce. `null` when the trailer is absent or only carries the edit line. Tolerates
     * NBSP between the words (HFR mixes `&nbsp;` and plain spaces in trailers).
     */
    private fun parseCitedCount(postTable: Element): Int? =
        postTable.selectFirst(HfrSelectors.POST_EDITED)
            ?.text()
            ?.let { trailer -> CITED_COUNT_REGEX.find(trailer)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Phase 2D (#147) / #227 — returns `true` when the post's left toolbar exposes an
     * edit link, i.e. HFR considers the post editable by the current session (own post,
     * unlocked topic). HFR ships this link in **two URL shapes** depending on the render:
     * - legacy / `message.php` form: `…message.php?…&numreponse={N}…` (the quote link is at
     *   the same place but uses `numrep`, not `numreponse`).
     * - **pretty form (authenticated pages, observed live 2026-05-31):**
     *   `/hfr/<cat>/editer-<a>-<numreponse>-<page>.htm` — the `citer-`/`editer-`/`repondre-`
     *   slugs HFR serves once logged in, instead of `message.php`.
     *
     * Both forms are obfuscated as `md_*cryptlink` spans and turned back into anchors by
     * [CryptlinkDecoder.materialize] (called by the caller) before this runs. The lookup
     * stays scoped to `POST_TOOLBAR_LEFT` so an inline `numreponse=`/`editer-` link a user
     * pasted in the post body never promotes the host post to editable.
     */
    private fun parseHasEditLink(postTable: Element): Boolean {
        val toolbar = postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT) ?: return false
        return toolbar.select("a[href]").any { anchor ->
            val href = anchor.attr("href")
            EDIT_PRETTY_REGEX.containsMatchIn(href) ||
                ("message.php" in href && EDIT_NUMREPONSE_REGEX.containsMatchIn(href))
        }
    }

    /**
     * Phase 2 finish (#208) — extracts the HFR numeric user id from the profile link
     * `<a href="/hfr/profil-{userId}.htm">` in the post's left toolbar. The profile
     * icon link is rendered adjacent to the timestamp and quote/edit links.
     *
     * Returns null when no such link is found (« Publicité » rows, anonymous reads, or
     * future HFR changes). The profile tap is hidden in that case — no magic default.
     */
    private fun parseProfileId(postTable: Element): Int? =
        postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT)
            ?.select("a[href*=/hfr/profil-]")
            ?.firstOrNull()
            ?.attr("href")
            ?.let { PROFILE_ID_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    // Phase 2C (#146) — extracts the `ref` query parameter from the post's quote link
    // when HFR exposes it in clear HTML. The quote action lives on the post's left
    // toolbar — HFR renders it as an `<img src="…quote.gif">` wrapped in an
    // `<a href="…message.php?…&numrep=…&ref=N…">`. The body of a post may legitimately
    // contain links to other posts, so scoping the lookup to the toolbar is what makes
    // « Citer » mean « cite this post » and not « cite whichever post this one mentions ».
    // `ref` is opaque (correlates with the post's position on the current page); we forward
    // whatever HFR provided and never compute it client-side. `null` when absent/obfuscated.
    private fun parseQuoteRef(postTable: Element): Int? =
        postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT)
            ?.select("a[href*=numrep=]")
            ?.firstOrNull { QUOTE_REF_REGEX.containsMatchIn(it.attr("href")) }
            ?.attr("href")
            ?.let { QUOTE_REF_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    fun parsePageInfo(document: Document): PageInfo {
        val pagerLeft = document
            .select(HfrSelectors.TOP_PAGER)
            .firstOrNull()
            ?.selectFirst(HfrSelectors.TOP_PAGER_LEFT)

        val current = pagerLeft
            ?.select(HfrSelectors.TOP_PAGER_CURRENT)
            ?.mapNotNull { it.text().trim().toIntOrNull() }
            ?.lastOrNull()
            ?: 1
        val linkedPages = pagerLeft
            ?.select(HfrSelectors.TOP_PAGER_LINK)
            ?.mapNotNull { it.text().trim().toIntOrNull() }
            .orEmpty()
        val total = maxOf(current, linkedPages.maxOrNull() ?: current)

        return PageInfo(current = current, total = total)
    }

    private companion object {
        // #863 — « Message cité N fois » in the div.edited trailer. `[\s ]+` : Jsoup's
        // text() keeps HFR's non-breaking spaces, which are NOT matched by a plain space.
        val CITED_COUNT_REGEX: Regex = Regex("""Message[\s ]+cité[\s ]+(\d+)[\s ]+fois""")
        val QUOTE_REF_REGEX: Regex = Regex("""[?&]ref=(\d+)""")
        val EDIT_NUMREPONSE_REGEX: Regex = Regex("""[?&]numreponse=(\d+)""")
        // #227 — authenticated pages serve the toolbar edit link as a pretty URL
        // `/hfr/<cat>/editer-<a>-<numreponse>-<page>.htm` instead of `message.php?numreponse=…`.
        // `/editer-\d` is distinctive (does NOT match `/user/editprofil.php`). The link is
        // recovered from its `md_*cryptlink` span by CryptlinkDecoder.materialize() beforehand.
        val EDIT_PRETTY_REGEX: Regex = Regex("""/editer-\d""")
        // Matches `/hfr/profil-{userId}.htm` — the `\d+` captures the numeric user id.
        val PROFILE_ID_REGEX: Regex = Regex("""/hfr/profil-(\d+)\.htm""")
    }
}
