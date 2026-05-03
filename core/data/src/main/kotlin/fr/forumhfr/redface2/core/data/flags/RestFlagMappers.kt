package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.data.forum.decodeHtmlEntities
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import kotlin.math.ceil
import kotlin.math.max

/**
 * Maps REST drapeaux payloads from the per-cat endpoint
 * `forums/hardwarefr/categories/{cat}/topics/{bucket}/` to the domain [Flag] model.
 * The envelope shape is [RestListEnvelope] / [RestTopic], proven by the captured
 * fixture `core/data/src/test/resources/fixtures/rest_cat23_participated.json`.
 *
 * The mapper also handles a hypothetical "global" payload via [fallbackCat] = null —
 * the cat would then come from each row's `links.category.href`. This is **not**
 * exercised by Phase 1D-1 production code : `HfrApiClient` does not expose a global
 * helper today (no fixture has been captured for the grouped-by-cat shape advertised
 * by HFR's docs). The `fallbackCat = null` branch is kept here under direct test
 * coverage so a follow-up that captures the global fixture can wire the helper
 * without re-deriving the parsing.
 *
 * Mapping rules :
 * - `flag_owntopic` → [FlagType] (1=CYAN, 2=RED, 3=FAVORITE) ; unknown / null buckets
 *   fall back to the [defaultType] passed by the caller (the bucket the request was
 *   issued for) so a future server-side bucket addition does not crash the screen.
 * - `is_read == false` → `hasUnread = true`. When `is_read` is missing (defensive
 *   path, REST flag responses always carry it for an authenticated request) we
 *   default to `true` because surfacing a likely-stale "read" badge is a worse UX
 *   than the inverse.
 * - `lastReadPage` is read from `links.posts.href?page=N`. Defaults to 1 when the
 *   payload does not advertise a page (a fresh topic the user has just opened).
 * - `lastPostReadId` is the REST `last_post_read_id` — the id of the **last** post
 *   the user read, not the first unread. The navigation layer uses this as a scroll
 *   anchor when opening the topic.
 * - `replyCount = max(posts.count - 1, 0)` and `totalPages = ceil(posts.count /
 *   posts_results_per_page)` mirror the existing forum mapper semantics so a topic
 *   summary surfaced from a flag list and from a category list look identical.
 * - `cat` is taken from [fallbackCat] when present (per-cat REST endpoint) ; otherwise
 *   it is parsed from `links.category.href`. When neither is available, the row is
 *   skipped — the UI cannot route a tap on a flag without a known cat.
 *
 * The mapper is pure ; tests live in `core/data/src/test/.../RestFlagMappersTest.kt`
 * and consume the captured fixture.
 */
internal object RestFlagMappers {

    private const val POSTS_PER_PAGE_FALLBACK = 40

    private val CAT_FROM_HREF = Regex("/categories/(\\d+)/")
    private val SUBCAT_FROM_HREF = Regex("/categories/\\d+/subcategories/(\\d+)/")

    fun toFlags(
        envelope: RestListEnvelope<RestTopic>,
        defaultType: FlagType,
        fallbackCat: Int? = null,
    ): List<Flag> = envelope.resource.resources.mapNotNull { dto ->
        toFlag(dto, defaultType, fallbackCat)
    }

    private fun toFlag(
        dto: RestTopic,
        defaultType: FlagType,
        fallbackCat: Int?,
    ): Flag? {
        val cat = fallbackCat
            ?: dto.links.category?.href?.let(::extractCatId)
            ?: return null
        val subcat = dto.links.subcategory?.href?.let(::extractSubcatId)
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
        val lastReadPage = postsHref
            ?.let { extractQueryParam(it, "page") }
            ?.toIntOrNull()
            ?.takeIf { it >= 1 }
            ?: 1
        return Flag(
            cat = cat,
            subcat = subcat,
            topicId = dto.id,
            title = dto.title.decodeHtmlEntities(),
            totalPages = totalPages,
            replyCount = replyCount,
            type = toFlagType(dto.flagOwntopic) ?: defaultType,
            hasUnread = dto.isRead?.let { !it } ?: true,
            lastReadPage = lastReadPage,
            lastPostReadId = dto.lastPostReadId,
            firstPostAuthor = dto.links.author?.title.orEmpty(),
            lastReplyAuthor = dto.links.lastAuthor?.title.orEmpty(),
            lastReplyAt = dto.lastPostDate,
        )
    }

    private fun toFlagType(rawFlagOwntopic: Int?): FlagType? = when (rawFlagOwntopic) {
        1 -> FlagType.CYAN
        2 -> FlagType.RED
        3 -> FlagType.FAVORITE
        else -> null
    }

    private fun extractCatId(href: String): Int? =
        CAT_FROM_HREF.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractSubcatId(href: String): Int? =
        SUBCAT_FROM_HREF.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
