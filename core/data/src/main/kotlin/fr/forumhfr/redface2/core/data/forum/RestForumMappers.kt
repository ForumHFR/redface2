package fr.forumhfr.redface2.core.data.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import kotlin.math.ceil
import kotlin.math.max

/**
 * Maps REST DTOs to domain models per ADR-003 mapping rules:
 * - HTML entities in `name` / `title` are decoded so the UI gets plain text.
 * - `replyCount = max(posts.count - 1, 0)` — `posts.count` is the total post count.
 * - `totalPages = ceil(posts.count / 40)` — HFR topic pages are 40 posts/page,
 *   independent of the REST listing's `results_per_page`.
 * - Authenticated-only fields stay nullable so anonymous responses round-trip cleanly.
 *
 * Functions are pure; tests live in `core/data/src/test/.../RestForumMappersTest.kt`
 * and consume the captured `rest_*.json` fixtures.
 */
internal object RestForumMappers {

    private const val POSTS_PER_HFR_PAGE = 40

    /** `categories/{cat}/subcategories/{sub}/` extracted from a HATEOAS subcategory link. */
    private val SUBCAT_FROM_HREF = Regex("/categories/\\d+/subcategories/(\\d+)/")

    fun toCategories(envelope: RestListEnvelope<RestCategory>): List<Category> =
        envelope.resource.resources.map { dto ->
            Category(
                id = dto.id,
                name = dto.name.decodeHtmlEntities(),
                forceSubcat = dto.forceSubcat,
                subcategoryCount = dto.numberOfSubcategories,
            )
        }

    fun toSubcategories(
        envelope: RestListEnvelope<RestSubcategory>,
        parentCategoryId: Int,
    ): List<SubCategory> = envelope.resource.resources.map { dto ->
        SubCategory(
            id = dto.id,
            name = dto.name.decodeHtmlEntities(),
            parentCategoryId = parentCategoryId,
        )
    }

    fun toTopicListPage(
        envelope: RestListEnvelope<RestTopic>,
        cat: Int,
        subcat: Int?,
    ): TopicListPage = TopicListPage(
        cat = cat,
        subcat = subcat,
        page = envelope.resource.page,
        resultsPerPage = envelope.resource.resultsPerPage,
        totalTopics = envelope.resource.resultsCount,
        topics = envelope.resource.resources.map { toTopicSummary(it, cat, fallbackSubcat = subcat) },
    )

    fun toTopicSummary(envelope: RestSingleEnvelope<RestTopic>, cat: Int): TopicSummary =
        toTopicSummary(envelope.resource, cat, fallbackSubcat = null)

    private fun toTopicSummary(
        dto: RestTopic,
        cat: Int,
        fallbackSubcat: Int?,
    ): TopicSummary {
        val postsCount = dto.links.posts?.count ?: 0
        val replyCount = max(postsCount - 1, 0)
        val totalPages = if (postsCount <= 0) {
            1
        } else {
            ceil(postsCount.toDouble() / POSTS_PER_HFR_PAGE).toInt().coerceAtLeast(1)
        }
        val subcatFromHref = dto.links.subcategory?.href?.let { extractSubcatId(it) }
        return TopicSummary(
            cat = cat,
            subcat = subcatFromHref ?: fallbackSubcat,
            topicId = dto.id,
            title = dto.title.decodeHtmlEntities(),
            author = dto.links.author?.title.orEmpty(),
            lastReplyAuthor = dto.links.lastAuthor?.title.orEmpty(),
            lastReplyAt = dto.lastPostDate,
            replyCount = replyCount,
            totalPages = totalPages,
            isSticky = dto.isSticky,
            isLocked = dto.isClosed,
            hasUnread = dto.isRead?.let { !it },
            lastReadPage = dto.lastPosition,
            lastPostReadId = dto.lastPostReadId,
        )
    }

    private fun extractSubcatId(href: String): Int? =
        SUBCAT_FROM_HREF.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

/**
 * Decodes the small subset of HTML entities seen in REST payloads (e.g. `&amp;` in
 * category names like "Overclocking, Cooling &amp; Modding"). Numeric forms are
 * supported defensively — captured fixtures only exhibit `&amp;` today, but a
 * server-side change to encode accents could surface them.
 */
internal fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        i = appendNextRun(out, i)
    }
    return out.toString()
}

private fun String.appendNextRun(out: StringBuilder, i: Int): Int {
    val c = this[i]
    val semi = if (c == '&') indexOf(';', startIndex = i + 1) else -1
    val decoded = if (semi != -1) decodeEntity(substring(i + 1, semi)) else null
    return if (decoded != null) {
        out.append(decoded)
        semi + 1
    } else {
        out.append(c)
        i + 1
    }
}

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "#39" to "'",
    "nbsp" to " ",
)

private fun decodeEntity(entity: String): String? = when {
    entity.isEmpty() -> null
    entity in NAMED_ENTITIES -> NAMED_ENTITIES[entity]
    entity.startsWith("#x", ignoreCase = true) -> decodeNumericEntity(entity.substring(2), radix = 16)
    entity.startsWith("#") -> decodeNumericEntity(entity.substring(1), radix = 10)
    else -> null
}

private fun decodeNumericEntity(digits: String, radix: Int): String? =
    digits.toIntOrNull(radix)?.let { String(Character.toChars(it)) }
