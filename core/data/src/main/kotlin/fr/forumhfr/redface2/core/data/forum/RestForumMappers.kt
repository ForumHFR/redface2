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
 * - `totalPages = ceil(posts.count / postsResultsPerPage)` — the HATEOAS
 *   `links.posts.href?results_per_page=N` query param drives the divisor. We
 *   intentionally do **not** assume HFR's HTML 40-posts-per-page convention
 *   globally: it is a per-user setting on the legacy HTML reader. The REST
 *   contract advertises the pagination bucket on every topic, so we trust it.
 *   `POSTS_PER_PAGE_FALLBACK` is only used as a defensive last resort when the
 *   href is missing / malformed, never as the canonical truth.
 * - `lastReadPage` is parsed from `links.posts.href?page=N` (the auth payload
 *   already points to the page of the last read post). `last_position` is the
 *   per-post index inside that page, **not** a page number — see ADR-003 and
 *   the `rest_cat23_participated.source.txt` capture notes.
 * - Authenticated-only fields stay nullable so anonymous responses round-trip cleanly.
 *
 * Functions are pure; tests live in `core/data/src/test/.../RestForumMappersTest.kt`
 * and consume the captured `rest_*.json` fixtures.
 */
internal object RestForumMappers {

    /**
     * Defensive fallback when `links.posts.href` does not advertise its
     * `results_per_page`. Matches the historical HFR HTML default but is **not**
     * a global truth — REST's `links.posts.href?results_per_page=N` is.
     */
    private const val POSTS_PER_PAGE_FALLBACK = 40

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
        val postsHref = dto.links.posts?.href
        val postsCount = dto.links.posts?.count ?: 0
        val postsResultsPerPage = postsHref
            ?.let { extractQueryParam(it, "results_per_page") }
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: POSTS_PER_PAGE_FALLBACK
        val replyCount = max(postsCount - 1, 0)
        val totalPages = if (postsCount <= 0) {
            1
        } else {
            ceil(postsCount.toDouble() / postsResultsPerPage).toInt().coerceAtLeast(1)
        }
        val subcatFromHref = dto.links.subcategory?.href?.let(::extractSubcatId)
        val isAuthenticatedRow = dto.isRead != null || dto.lastPosition != null || dto.lastPostReadId != null
        val lastReadPage = if (isAuthenticatedRow) {
            postsHref
                ?.let { extractQueryParam(it, "page") }
                ?.toIntOrNull()
                ?.takeIf { it >= 1 }
        } else {
            null
        }
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
            // hasUnread = !is_read — anonymous payloads omit `is_read` so we expose `null`.
            hasUnread = dto.isRead?.let { !it },
            lastReadPage = lastReadPage,
            lastPostReadId = dto.lastPostReadId,
        )
    }

    private fun extractSubcatId(href: String): Int? =
        SUBCAT_FROM_HREF.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Extracts a query parameter value from a raw href string. We avoid pulling
     * `okhttp3.HttpUrl` here because `:core:data` doesn't depend on `:core:network`.
     * Returns `null` if the param is absent or the href has no query part.
     */
    private fun extractQueryParam(href: String, name: String): String? {
        val queryStart = href.indexOf('?').takeIf { it >= 0 } ?: return null
        val query = href.substring(queryStart + 1)
        return query.split('&').firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@firstNotNullOfOrNull null
            val key = pair.substring(0, eq)
            if (key == name) pair.substring(eq + 1) else null
        }
    }
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
    "nbsp" to " ",
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
