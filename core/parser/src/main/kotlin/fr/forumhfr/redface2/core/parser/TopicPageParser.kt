package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.parser.common.HfrDateParser
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class TopicPageParser(
    private val postContentParser: PostContentParser = PostContentParser(),
    private val dateParser: HfrDateParser = HfrDateParser(),
) {
    fun parse(html: String): Topic {
        val document = Jsoup.parse(html)
        // #227 — HFR obfuscates the per-post toolbar `message.php` links (quote + edit) in
        // `md_*cryptlink` spans on many pages (anti-aspirateur, intermittent: cat IA, pinned
        // topics, and — observed 2026-05-31 — most sections even logged-out). A no-JS client
        // (Redface 2) then sees no clear `<a href>`, so « Modifier » (`parseHasEditLink`) would
        // break. `materialize()` replays HFR's `md_forum_decryptlink.init()` to turn those spans
        // back into anchors before any toolbar extraction. No-op on a clear page. Profile links
        // (`/hfr/profil-`) ship in clear and are unaffected; « Citer » self-generates by `numrep`.
        CryptlinkDecoder.materialize(document)
        val pageInfo = parsePageInfo(document)
        val posts = parsePosts(document)
        val replyForm = document.selectFirst(REPLY_FORM_SELECTOR)

        return Topic(
            cat = requireInputValue(document, HfrSelectors.CATEGORY_ID_INPUT),
            post = requireInputValue(document, HfrSelectors.TOPIC_ID_INPUT),
            // #213 — postability is driven by the presence of the `bddpost` reply
            // form, which HFR renders only on an authenticated, non-locked topic.
            // The POST subcat is the `input[name=subcat]` of THAT form (it can be
            // `0` for a category without sub-category, e.g. cat IA — a valid,
            // postable value). When no reply form is present (logged-out / prefetch
            // anon page, locked topic), the page only ships the fast-search widget
            // subcat which is useless for writing : we fall back to the
            // SUBCAT_UNKNOWN sentinel and leave the topic read-only.
            subcat = replyFormSubcat(replyForm),
            title = document.selectFirst(HfrSelectors.TOPIC_TITLE)?.text()?.trim()
                ?: error("Topic title not found"),
            posts = posts,
            page = pageInfo.current,
            totalPages = pageInfo.total,
            // True only when HFR rendered the `bddpost` reply form on this page.
            // See [subcat] above and `Topic.canReply` KDoc.
            canReply = replyForm != null,
            // Phase 2D #148 : the « Modifier le premier message » action only
            // makes sense when the page actually contains the first post, i.e.
            // on page 1. We additionally require HFR to have rendered the edit
            // link on that post (`firstPost.isEditable`, parsed from the
            // toolbar in `parseHasEditLink`). Page 2+ stays false even if a
            // later post is editable — that one already has its own
            // « Modifier » via the per-post button. Cache rows read from
            // pre-#148 captures keep their stored value because Room ships the
            // column since v1.
            isFirstPostOwner = pageInfo.current == 1 && posts.firstOrNull()?.isEditable == true,
            poll = parsePoll(document),
        )
    }

    /**
     * #213 — the POST subcat is read from the `input[name=subcat]` of the `bddpost`
     * reply form, never from the fast-search widget that also ships a `subcat` input
     * on the topic page. HFR renders the reply form only in an authenticated session
     * on a non-locked topic, so this is also the source of truth for [Topic.canReply].
     *
     * `0` is kept verbatim — a category without sub-category (e.g. cat IA, cat=32)
     * posts with `subcat=0` (proven by a live capture, see protocol-hfr.md). Only the
     * absence of the form (or of its subcat input) falls back to [Topic.SUBCAT_UNKNOWN],
     * which write flows refuse.
     */
    private fun replyFormSubcat(replyForm: Element?): Int {
        if (replyForm == null) return Topic.SUBCAT_UNKNOWN
        return replyForm.selectFirst(HfrSelectors.SUBCATEGORY_ID_INPUT)
            ?.attr("value")
            ?.toIntOrNull()
            ?: Topic.SUBCAT_UNKNOWN
    }

    private fun parsePosts(
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
            postIndex = null,
            quoteRef = parseQuoteRef(postTable),
            profileId = parseProfileId(postTable),
        )
    }

    /**
     * Phase 2D (#147) — returns `true` when the post's left toolbar exposes an
     * edit link of the shape `<a href="…message.php?…&numreponse={N}…">`. The
     * quote link is at the same place but uses `numrep` (not `numreponse`), and
     * the post body may carry unrelated `numreponse=` links (`viewbbcode.php`,
     * `forum2.php?…&numreponse=0`, modo / addflag). Scoping the lookup to
     * `POST_TOOLBAR_LEFT` + matching only `message.php` + `numreponse=` is what
     * separates « this post is editable » from « this post happens to link to
     * another post ».
     */
    private fun parseHasEditLink(postTable: Element): Boolean {
        val toolbar = postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT) ?: return false
        return toolbar
            .select("a[href*=message.php]")
            .any { EDIT_NUMREPONSE_REGEX.containsMatchIn(it.attr("href")) }
    }

    /**
     * Phase 2C (#146) — extracts the `ref` query parameter from the post's quote
     * link when HFR exposes it in clear HTML. `ref` is opaque (correlates with the
     * post's position on the current topic page, exact semantic undocumented), so
     * we forward whatever HFR provided and never compute it client-side. When the
     * link is absent or obfuscated we return `null`; « Citer » still works on
     * postable topics because the write flow quotes by `numrep={numreponse}` alone.
     */
    // The quote action lives on the post's left toolbar — HFR renders it as
    // an `<img src="…quote.gif">` wrapped in an `<a href="…message.php?…
    // &numrep=…&ref=N…">`. The body of a post may legitimately contain links
    // to other posts (e.g. an inline reference to `message.php?…&numrep=…`),
    // so scoping the lookup to the toolbar is what makes « Citer » mean
    // « cite this post » and not « cite whichever post this one mentions ».
    //
    // Two filters use `QUOTE_REF_REGEX` (`[?&]ref=…`) so a future HFR href
    // embedding `…&myref=…` / `…&referrer=…` does not pretend to be a quote
    // link. `&amp;` is normalised by Jsoup to `&` in the parsed attribute
    // value. The chain is null-safe end-to-end : missing toolbar, missing
    // quote link, or unparseable ref all return `null` without a fallback.
    /**
     * Phase 2 finish (#208) — extracts the HFR numeric user id from the profile link
     * `<a href="/hfr/profil-{userId}.htm">` in the post's left toolbar. The profile
     * icon link is rendered adjacent to the timestamp and quote/edit links.
     *
     * Pattern observed on `topic_khakha_page_1.html`:
     * `<a href="https://forum.hardware.fr/hfr/profil-599674.htm" target="_blank" rel="nofollow">
     *   <img ... title="Voir son profil" ...></a>`
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

    private fun parseQuoteRef(postTable: Element): Int? =
        postTable.selectFirst(HfrSelectors.POST_TOOLBAR_LEFT)
            ?.select("a[href*=numrep=]")
            ?.firstOrNull { QUOTE_REF_REGEX.containsMatchIn(it.attr("href")) }
            ?.attr("href")
            ?.let { QUOTE_REF_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

    private fun parsePageInfo(document: Document): PageInfo {
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

    private fun requireInputValue(
        document: Document,
        selector: String,
    ): Int {
        return document.selectFirst(selector)
            ?.attr("value")
            ?.toIntOrNull()
            ?: error("Required input not found for $selector")
    }

    private fun parsePoll(document: Document): Poll? {
        val pollElement = document.selectFirst(HfrSelectors.POLL) ?: return null
        val question = pollElement
            .selectFirst(HfrSelectors.POLL_QUESTION)
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        val optionBars = pollElement.select(HfrSelectors.POLL_OPTION_BAR)
        val optionLabels = pollElement.select(HfrSelectors.POLL_OPTION_LABEL)
        return if (question == null || optionBars.isEmpty() || optionBars.size != optionLabels.size) {
            null
        } else {
            val options = optionBars.mapIndexed { index, optionBar ->
                val percentText = optionBar
                    .select(HfrSelectors.POLL_OPTION_PERCENT)
                    .firstOrNull()
                    ?.text()
                    .orEmpty()
                val votesText = optionBar
                    .select(HfrSelectors.POLL_OPTION_PERCENT)
                    .lastOrNull()
                    ?.text()
                    .orEmpty()

                PollOption(
                    text = optionLabels[index].text().trim(),
                    votes = firstInt(votesText),
                    percentage = firstFloat(percentText),
                )
            }

            val trailingText = pollElement.childNodes()
                .filterIsInstance<TextNode>()
                .joinToString(" ") { it.text() }
            val summaryText = buildString {
                append(pollElement.text())
                append(' ')
                append(trailingText)
            }

            Poll(
                question = question,
                options = options,
                multipleChoice = choiceCount(summaryText) > 1,
                totalVotes = firstInt(
                    Regex("""Total\s*[:\s]\s*(\d+)\s+votes?""", RegexOption.IGNORE_CASE)
                        .find(summaryText)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty(),
                ),
                hasVoted = false,
            )
        }
    }

    private fun firstInt(text: String): Int =
        Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun firstFloat(text: String): Float =
        Regex("""(\d+(?:[.,]\d+)?)""").find(text)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: 0f

    private fun choiceCount(text: String): Int =
        Regex("""Sondage à\s+(\d+)\s+choix""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 1
}

private data class PageInfo(
    val current: Int,
    val total: Int,
)

// #213 — the reply form posts to `/bddpost.php` (possibly with query params, e.g.
// `?config=hfr.inc`). We match `action*=bddpost.php` and deliberately NOT
// `action*=bdd` : the latter would also match the `bdd.php` edit endpoint and the
// fast-search `forum1.php` form must never count as a reply form. Mirrors the proven
// `ReplyFormParser` selector contract.
private const val REPLY_FORM_SELECTOR: String = "form[action*=bddpost.php]"
private val QUOTE_REF_REGEX: Regex = Regex("""[?&]ref=(\d+)""")
private val EDIT_NUMREPONSE_REGEX: Regex = Regex("""[?&]numreponse=(\d+)""")
// Matches `/hfr/profil-{userId}.htm` — the `\d+` captures the numeric user id.
private val PROFILE_ID_REGEX: Regex = Regex("""/hfr/profil-(\d+)\.htm""")
