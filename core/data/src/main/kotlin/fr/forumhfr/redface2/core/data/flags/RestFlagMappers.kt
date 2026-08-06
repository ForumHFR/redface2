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
 * - [Flag.type] is the **requested bucket** ([type]), never derived from `flag_owntopic`.
 *   Live-verified 2026-06-11 (#384, fixture `rest_cat13_participated_favorites.json`) :
 *   the `participated` bucket returns participated-AND-favorited topics with
 *   `flag_owntopic = 3`, and the `read` bucket returns `flag_owntopic = 0` — the field
 *   describes the strongest flag ON the topic (3 = favori/étoile), NOT which bucket the
 *   row belongs to. Mapping it to [Flag.type] made `replaceForType(CYAN)` persist those
 *   rows under FAVORITE in Room, so a Room-served « Mes sujets » list silently lost its
 *   favorited topics (the #384 amputation) and a swipe-removal on such a row deleted the
 *   favori instead of the cyan drapeau. A future « étoile » badge can surface
 *   `flag_owntopic == 3` as a separate field if needed.
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

    /**
     * Defensive fallback when `links.posts.href` does not advertise its
     * `results_per_page`. Matches the historical HFR HTML default but is **not**
     * a global truth — REST's `links.posts.href?results_per_page=N` is. Mirrors
     * `RestForumMappers.POSTS_PER_PAGE_FALLBACK` (same value, same intent).
     */
    private const val POSTS_PER_PAGE_FALLBACK = 40

    /**
     * REST `flag_owntopic` value meaning « favori/étoile » (the strongest flag on the topic,
     * live-verified on the `rest_cat13_participated_favorites.json` fixture, #384). Mirrors
     * `RestForumMappers`' reading of the same field.
     */
    private const val FLAG_OWNTOPIC_FAVORITE = 3

    // #251 — `flag_owntopic` routing for the STICKY supplement read off `topics/last` (mirrors the
    // legacy `owntopic` filter, same mapping as RestForumMappers.toFlagType): 1 = participated (CYAN),
    // 2 = read-only (RED), 3 = favorite (FAVORITE).
    private const val FLAG_OWNTOPIC_PARTICIPATED = 1
    private const val FLAG_OWNTOPIC_READ = 2

    private val CAT_FROM_HREF = Regex("/categories/(\\d+)/")
    private val SUBCAT_FROM_HREF = Regex("/categories/\\d+/subcategories/(\\d+)/")

    fun toFlags(
        envelope: RestListEnvelope<RestTopic>,
        type: FlagType,
        fallbackCat: Int? = null,
    ): List<Flag> = envelope.resource.resources.mapNotNull { dto ->
        toFlag(dto, type, fallbackCat)
    }

    /**
     * #251 — supplement for flagged STICKY topics that the per-cat REST flag buckets DROP (observed
     * in categories with no subcategory, e.g. cat 32 « IA » : the sticky « Règles » topic carries a
     * cyan flag yet is absent from `topics/participated/`, present in `topics/last/`). Reads a
     * `categories/{cat}/topics/last/` envelope and keeps ONLY the sticky rows whose own
     * `flag_owntopic` routes to [type], mapped with [type] as the bucket.
     *
     * Unlike the bucket path (where `flag_owntopic` is the « strongest flag », NOT bucket membership —
     * #384), on the listing endpoint `flag_owntopic` IS the routing signal: it reports the topic's own
     * flag. A sticky participated-AND-favorited topic (`flag_owntopic = 3`) therefore surfaces only in
     * FAVORITE — an accepted edge of an edge (REST exposes no richer per-topic flag set).
     */
    fun toStickyFlags(
        envelope: RestListEnvelope<RestTopic>,
        type: FlagType,
        fallbackCat: Int,
    ): List<Flag> = envelope.resource.resources
        .filter { it.isSticky && flagOwntopicToType(it.flagOwntopic) == type }
        .mapNotNull { toFlag(it, type, fallbackCat) }

    private fun flagOwntopicToType(rawFlagOwntopic: Int?): FlagType? = when (rawFlagOwntopic) {
        FLAG_OWNTOPIC_PARTICIPATED -> FlagType.CYAN
        FLAG_OWNTOPIC_READ -> FlagType.RED
        FLAG_OWNTOPIC_FAVORITE -> FlagType.FAVORITE
        else -> null
    }

    private fun toFlag(
        dto: RestTopic,
        type: FlagType,
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
            // #384 — the requested bucket IS the type; `flag_owntopic` describes the strongest
            // flag on the topic (3 = favori), not bucket membership. See the object KDoc.
            type = type,
            // The « étoile » decoration the object KDoc anticipated: surfaced as its own field so
            // a favorited topic keeps its yellow dot in « Mes sujets » without touching the type.
            isFavorite = dto.flagOwntopic == FLAG_OWNTOPIC_FAVORITE,
            hasUnread = dto.isRead?.let { !it } ?: true,
            lastReadPage = lastReadPage,
            lastPostReadId = dto.lastPostReadId,
            // #638 — both are needed to tell « stopped at the bottom of the page » from « stopped
            // mid-page » (cf. Flag.pageToOpen). `last_position` was already deserialised but its
            // value was thrown away; only its nullity was read, as an authenticated-row probe.
            lastPosition = dto.lastPosition,
            postsPerPage = postsResultsPerPage,
            firstPostAuthor = dto.links.author?.title.orEmpty(),
            lastReplyAuthor = dto.links.lastAuthor?.title.orEmpty(),
            lastReplyAt = dto.lastPostDate,
        )
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
