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
        val pageInfo = parsePageInfo(document)
        val posts = parsePosts(document)

        return Topic(
            cat = requireInputValue(document, HfrSelectors.CATEGORY_ID_INPUT),
            post = requireInputValue(document, HfrSelectors.TOPIC_ID_INPUT),
            title = document.selectFirst(HfrSelectors.TOPIC_TITLE)?.text()?.trim()
                ?: error("Topic title not found"),
            posts = posts,
            page = pageInfo.current,
            totalPages = pageInfo.total,
            isFirstPostOwner = false,
            poll = parsePoll(document),
        )
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
            content = content.content,
            contentAst = content.ast,
            avatarUrl = postTable.selectFirst(HfrSelectors.POST_AVATAR)?.attr("src"),
            isEditable = false,
            isOwnPost = false,
            quotedAuthors = content.quotedAuthors,
            postIndex = null,
        )
    }

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
