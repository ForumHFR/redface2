package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import org.jsoup.nodes.Element

class PostContentParser {
    fun parse(contentElement: Element?): ParsedPostContent {
        if (contentElement == null) {
            return ParsedPostContent(
                content = "[Message supprimé]",
                quotedAuthors = emptyList(),
            )
        }

        val quotedAuthors = contentElement
            .select(HfrSelectors.POST_CITATION_AUTHOR)
            .mapNotNull { citation ->
                citation.text()
                    .substringBefore(" a écrit")
                    .trim()
                    .takeIf(String::isNotEmpty)
            }
            .distinct()

        val sanitized = contentElement.clone().apply {
            select("${HfrSelectors.POST_EDITED}, ${HfrSelectors.POST_SIGNATURE}").remove()
        }

        return ParsedPostContent(
            content = sanitized.html().trim().ifEmpty { "[Message supprimé]" },
            quotedAuthors = quotedAuthors,
        )
    }
}

data class ParsedPostContent(
    val content: String,
    val quotedAuthors: List<String>,
)
