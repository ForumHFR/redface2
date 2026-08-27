package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.Topic
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import fr.forumhfr.redface2.core.parser.common.PollChoiceCaption
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class TopicPageParser(
    private val postsParser: PostsParser = PostsParser(),
    private val searchFormParser: TopicSearchFormParser = TopicSearchFormParser(),
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
        val pageInfo = postsParser.parsePageInfo(document)
        val posts = postsParser.parsePosts(document)
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
            // Chantier C (#546) — the intra-topic search form is part of THIS page ; parse it here so
            // the ViewModel gets it for free on every live load. Null when absent ; not persisted.
            searchForm = searchFormParser.parse(document),
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
        // #697 — HFR serves TWO poll shapes. The RESULTS shape (.sondageLeft bars, below) only
        // exists once the reader voted or clicked « voir les résultats » ; every other fetch —
        // including ALL anonymous reads, i.e. what this app receives — gets the FORM shape
        // (radio/checkbox inputs), which this parser used to drop silently (optionBars empty →
        // null → « aucun sondage ne s'affiche », CharLee's report).
        return if (question != null && optionBars.isEmpty()) {
            parseFormPoll(pollElement, question)
        } else if (question == null || optionBars.isEmpty() || optionBars.size != optionLabels.size) {
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

            // #779 (PR 1) — the vote cap comes from « Sondage à N choix possibles » on the results
            // card. A mono results poll carries no caption : it allows exactly one pick, so `1` is
            // factual, not invented. `multipleChoice` is derived from the same figure to stay in
            // lockstep with the persisted `maxSelections`.
            val maxSelections = PollChoiceCaption.maxSelections(summaryText) ?: 1
            Poll(
                question = question,
                options = options,
                multipleChoice = maxSelections > 1,
                totalVotes = firstInt(
                    Regex("""Total\s*[:\s]\s*(\d+)\s+votes?""", RegexOption.IGNORE_CASE)
                        .find(summaryText)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty(),
                ),
                hasVoted = false,
                maxSelections = maxSelections,
            )
        }
    }

    /**
     * #697 — builds a read-only [Poll] from the FORM shape: `<ol><li><input name=reponse><label>`.
     * No votes/percentages exist in this shape (fields are 0, [Poll.resultsAvailable] = false).
     * Multiple-choice detection reads the INPUT TYPE (checkbox = multi, radio = single — proven on
     * live fixtures 44713 mono / 16022 multi), the robust signal : it does not depend on the
     * caption being present. #779 (PR 1) additionally reads the « Sondage à N choix possibles »
     * caption — which IS present on the multi FORM shape (`topic_poll_form_multi_bourse`) — for
     * [Poll.maxSelections] only, never to decide [Poll.multipleChoice].
     */
    private fun parseFormPoll(pollElement: Element, question: String): Poll? {
        val formOptions = pollElement.select(HfrSelectors.POLL_FORM_OPTION)
        val labels = formOptions.mapNotNull { option ->
            option.selectFirst(HfrSelectors.POLL_FORM_OPTION_LABEL)?.text()?.trim()?.takeIf(String::isNotEmpty)
        }
        if (labels.isEmpty() || labels.size != formOptions.size) return null
        val multipleChoice = pollElement.selectFirst(HfrSelectors.POLL_FORM_MULTI_INPUT) != null
        return Poll(
            question = question,
            options = labels.map { PollOption(text = it, votes = 0, percentage = 0f) },
            multipleChoice = multipleChoice,
            totalVotes = 0,
            hasVoted = false,
            resultsAvailable = false,
            // #779 (PR 1) — a mono (radio) poll allows exactly one pick → 1. A multi (checkbox)
            // poll reads « Sondage à N choix possibles » from the `div.sondage` FORM shape (present
            // on the live `topic_poll_form_multi_bourse` capture) ; a missing caption leaves the cap
            // unknown (null), never an invented number.
            maxSelections = if (multipleChoice) PollChoiceCaption.maxSelections(pollElement.text()) else 1,
        )
    }

    private fun firstInt(text: String): Int =
        Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun firstFloat(text: String): Float =
        Regex("""(\d+(?:[.,]\d+)?)""").find(text)?.groupValues?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: 0f
}

// #213 — the reply form posts to `/bddpost.php` (possibly with query params, e.g.
// `?config=hfr.inc`). We match `action*=bddpost.php` and deliberately NOT
// `action*=bdd` : the latter would also match the `bdd.php` edit endpoint and the
// fast-search `forum1.php` form must never count as a reply form. Mirrors the proven
// `ReplyFormParser` selector contract.
private const val REPLY_FORM_SELECTOR: String = "form[action*=bddpost.php]"
