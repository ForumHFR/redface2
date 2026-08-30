package fr.forumhfr.redface2.core.parser.url

/** Topic coordinates encoded by an HFR pretty URL. */
data class HfrTopicUrl(
    val categorySlug: String,
    val post: Int,
    val page: Int,
    val scrollTo: Int?,
)

/**
 * Parses the public HFR topic shape without depending on Android or `java.net.URI`.
 *
 * Both relative and absolute forms are accepted. The path depth below the category is variable:
 * `/hfr/<category>[/<subcategory>...]/<slug>-sujet_<post>_<page>.htm[#t<numreponse>]`.
 * Listing URLs (`liste_sujet-...`) do not match the topic suffix and are rejected.
 */
object HfrTopicUrlParser {
    @Suppress("ReturnCount") // Regex miss + per-field positive-int validation, each an early return.
    fun parse(url: String): HfrTopicUrl? {
        val match = TOPIC_URL_REGEX.matchEntire(url) ?: return null
        val post = match.groupValues[2].toPositiveIntOrNull() ?: return null
        val page = match.groupValues[3].toPositiveIntOrNull() ?: return null
        val scrollValue = match.groupValues[4]
        val scrollTo = if (scrollValue.isEmpty()) null else scrollValue.toPositiveIntOrNull() ?: return null

        return HfrTopicUrl(
            categorySlug = match.groupValues[1],
            post = post,
            page = page,
            scrollTo = scrollTo,
        )
    }

    private fun String.toPositiveIntOrNull(): Int? = toIntOrNull()?.takeIf { it > 0 }

    private val TOPIC_URL_REGEX = Regex(
        pattern = """^(?:https?://[^/?#]+)?/hfr/([^/?#]+)/""" +
            """(?:[^/?#]+/)*[^/?#]+-sujet_(\d+)_(\d+)\.htm(?:#t(\d+))?$""",
        option = RegexOption.IGNORE_CASE,
    )
}
