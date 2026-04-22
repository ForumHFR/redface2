package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Topic

class HfrParser(
    private val topicPageParser: TopicPageParser = TopicPageParser(),
) {
    fun parseTopicPage(html: String): Topic = topicPageParser.parse(html)
}
