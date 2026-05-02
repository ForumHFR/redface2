package fr.forumhfr.redface2.core.data.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based tests for the REST → domain mappers. The fixtures live next to this
 * file in `core/data/src/test/resources/fixtures/rest_*.json`; they were captured live
 * on 2026-05-01 (cf. each `*.source.txt` for the curl + caveats).
 */
class RestForumMappersTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `categories fixture maps 19 public categories with HTML entities decoded`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestCategory>>(
            fixture("rest_categories.json"),
        )

        val categories = RestForumMappers.toCategories(envelope)

        assertEquals(19, categories.size)
        // IDs we care about must be present
        val byId = categories.associateBy(Category::id)
        assertNotNull(byId[1])   // Hardware
        assertNotNull(byId[13])  // Discussions
        assertNotNull(byId[23])  // Technologies Mobiles
        assertNotNull(byId[30])  // Electronique, domotique, DIY
        // Conditional cat=24 (Blabla) is not part of the public REST list
        assertNull(byId[24])
        // HTML entities like &amp; must be decoded — fixture has "Overclocking, Cooling &amp; Modding"
        val ocm = requireNotNull(byId[2])
        assertEquals("Overclocking, Cooling & Modding", ocm.name)
        // force_subcat is preserved per category
        assertTrue(ocm.forceSubcat)
        val electronique = requireNotNull(byId[30])
        assertFalse(electronique.forceSubcat)
        // subcategoryCount mirrors number_of_subcategories
        assertEquals(15, requireNotNull(byId[1]).subcategoryCount)
    }

    @Test
    fun `subcategories cat=13 fixture maps 15 children with parent id wired`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestSubcategory>>(
            fixture("rest_subcategories_cat13.json"),
        )

        val subcategories = RestForumMappers.toSubcategories(envelope, parentCategoryId = 13)

        assertEquals(15, subcategories.size)
        val byId = subcategories.associateBy(SubCategory::id)
        assertEquals("Actualité", requireNotNull(byId[422]).name)
        assertEquals("Politique", requireNotNull(byId[482]).name)
        // Every subcategory carries the parent id we passed in
        subcategories.forEach { assertEquals(13, it.parentCategoryId) }
    }

    @Test
    fun `topic listing maps replyCount totalPages isClosed isSticky and authors`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_topics_cat23_subcat550_p1.json"),
        )

        val page = RestForumMappers.toTopicListPage(envelope, cat = 23, subcat = 550)

        assertEquals(23, page.cat)
        assertEquals(550, page.subcat)
        assertEquals(1, page.page)
        assertTrue("expected at least 1 topic, got ${page.topics.size}", page.topics.isNotEmpty())
        // Find a known topic — id=35367 "Décès de Marc, fondateur du forum" with posts.count=1
        val marcTopic = requireNotNull(page.topics.find { it.topicId == 35367 })
        assertEquals(0, marcTopic.replyCount) // posts.count=1 → replies=0
        assertEquals(1, marcTopic.totalPages) // posts.count=1 → 1 page
        assertTrue("Marc topic should be sticky", marcTopic.isSticky)
        assertTrue("Marc topic should be locked", marcTopic.isLocked)
        // Find topic id=13 with posts.count=190 → totalPages = ceil(190/40) = 5
        val tmListTopic = requireNotNull(page.topics.find { it.topicId == 13 })
        assertEquals(189, tmListTopic.replyCount)
        assertEquals(5, tmListTopic.totalPages)
        assertFalse(tmListTopic.isLocked)
        // Anonymous response → hasUnread / lastReadPage / lastPostReadId / flagType stay null
        assertNull(tmListTopic.hasUnread)
        assertNull(tmListTopic.lastReadPage)
        assertNull(tmListTopic.lastPostReadId)
        assertNull(tmListTopic.flagType)
        // Authors are mapped from links.author / links.last_author
        assertEquals("Wolfman", marcTopic.author)
        assertEquals("Wolfman", marcTopic.lastReplyAuthor)
        // last_post_date stays raw "YYYY-MM-DD HH:mm"
        assertTrue(
            "expected raw timestamp, got ${marcTopic.lastReplyAt}",
            marcTopic.lastReplyAt.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
    }

    @Test
    fun `topic listing extracts subcat from links subcategory href when fallback is null`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_topics_cat23_subcat550_p1.json"),
        )

        // Asking the mapper for "all subcats" (subcat=null) — every topic should still
        // get its subcat populated from links.subcategory.href when present.
        val page = RestForumMappers.toTopicListPage(envelope, cat = 23, subcat = null)

        val topicWithSubcat = requireNotNull(page.topics.find { it.topicId == 13 })
        assertEquals(510, topicWithSubcat.subcat)
        // The Marc topic has no links.subcategory in the fixture → falls back to null.
        val marcTopic = requireNotNull(page.topics.find { it.topicId == 35367 })
        assertNull(marcTopic.subcat)
    }

    @Test
    fun `authenticated topic exposes lastReadPage from links posts href page param not last_position`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_cat23_participated.json"),
        )

        val page = RestForumMappers.toTopicListPage(envelope, cat = 23, subcat = null)

        val topic = page.topics.single()
        assertEquals(35395, topic.topicId)
        // is_read = false → hasUnread = true
        assertEquals(true, topic.hasUnread)
        // links.posts.href = ".../posts/?page=12&results_per_page=40" → lastReadPage = 12.
        // last_position is the per-post offset (479 / 541 posts), NOT a page index — we
        // must not surface it as such. See ADR-003 + rest_cat23_participated.source.txt.
        assertEquals(12, topic.lastReadPage)
        assertEquals(2_783_256, topic.lastPostReadId)
        // Regression guard: never expose REST `last_position` as `lastReadPage`.
        assertNotEquals(479, topic.lastReadPage)
        // subcat extracted from links.subcategory.href
        assertEquals(550, topic.subcat)
        // flag_owntopic = 1 → CYAN (sujet participé). Captured fixture is the participated bucket.
        assertEquals(FlagType.CYAN, topic.flagType)
    }

    @Test
    fun `flag_owntopic 1, 2, 3 map to CYAN, RED, FAVORITE — anything else maps to null`() {
        // Synthetic payloads — one resource per flag_owntopic value we care to assert.
        // The tuple lists the expected mapping; null covers anonymous (absent), 0/4/-1
        // (unknown buckets HFR may add or rename without warning).
        val cases: List<Pair<Int?, FlagType?>> = listOf(
            1 to FlagType.CYAN,
            2 to FlagType.RED,
            3 to FlagType.FAVORITE,
            null to null,
            0 to null,
            4 to null,
            -1 to null,
        )

        cases.forEach { (raw, expected) ->
            val payload = """
                {
                  "resource": {
                    "page": 1,
                    "results_count": 1,
                    "results_per_page": 1,
                    "resources": [
                      {
                        "id": 99,
                        "title": "synthetic",
                        ${if (raw != null) "\"flag_owntopic\": $raw," else ""}
                        "links": { "posts": { "count": 1 } }
                      }
                    ]
                  }
                }
            """.trimIndent()
            val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)
            val topic = RestForumMappers.toTopicListPage(envelope, cat = 1, subcat = null).topics.single()
            assertEquals("flag_owntopic=$raw expected $expected", expected, topic.flagType)
        }
    }

    @Test
    fun `flagType is independent from hasUnread — read CYAN topic still surfaces both`() {
        // is_read = true (drapeau lu), flag_owntopic = 1 (cyan participé).
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 42,
                    "title": "read but cyan",
                    "is_read": true,
                    "flag_owntopic": 1,
                    "links": { "posts": { "count": 1 } }
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)
        val topic = RestForumMappers.toTopicListPage(envelope, cat = 1, subcat = null).topics.single()
        assertEquals(FlagType.CYAN, topic.flagType)
        assertEquals(false, topic.hasUnread)
    }

    @Test
    fun `totalPages divides posts count by links posts href results_per_page param`() {
        // Synthetic case: 120 posts / 60-per-page bucket should yield 2 pages.
        // If totalPages is hardcoded to 40, this test fails (120/40 = 3).
        val syntheticPayload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 99,
                    "title": "synthetic",
                    "links": {
                      "posts": {
                        "href": "https://forum.hardware.fr/api/forums/x/categories/1/topics/99/posts/?page=1&results_per_page=60",
                        "count": 120
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(syntheticPayload)

        val page = RestForumMappers.toTopicListPage(envelope, cat = 1, subcat = null)

        val topic = page.topics.single()
        assertEquals(2, topic.totalPages)
        assertEquals(119, topic.replyCount)
    }

    @Test
    fun `totalPages falls back to 40 when links posts href is absent`() {
        // Defensive fallback path. If the href is missing the mapper must not crash —
        // and the captured anonymous fixture (results_per_page=40) is unaffected.
        val syntheticPayload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 99,
                    "title": "synthetic",
                    "links": {
                      "posts": { "count": 80 }
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(syntheticPayload)

        val topic = RestForumMappers.toTopicListPage(envelope, cat = 1, subcat = null).topics.single()

        // 80 / 40-fallback = 2 pages.
        assertEquals(2, topic.totalPages)
    }

    @Test
    fun `categories auth fixture maps to the same public projection as anonymous fixture`() {
        val anonymous = json.decodeFromString<RestListEnvelope<RestCategory>>(
            fixture("rest_categories.json"),
        ).let(RestForumMappers::toCategories)
        val authenticated = json.decodeFromString<RestListEnvelope<RestCategory>>(
            fixture("rest_categories_auth.json"),
        ).let(RestForumMappers::toCategories)

        // The authenticated payload exposes the same public categories — id, decoded
        // name, force_subcat and subcategoryCount must round-trip identically. The
        // private cat=24 (Blabla) is not in either projection (REST does not expose it
        // via the public list, even when authenticated).
        assertEquals(anonymous.size, authenticated.size)
        assertEquals(anonymous, authenticated)
        assertNull(authenticated.firstOrNull { it.id == 24 })
    }

    @Test
    fun `topic metadata fixture maps single resource to TopicSummary with cat`() {
        val envelope = json.decodeFromString<RestSingleEnvelope<RestTopic>>(
            fixture("rest_topic_meta_35395.json"),
        )

        val summary = RestForumMappers.toTopicSummary(envelope, cat = 23)

        assertEquals(35395, summary.topicId)
        assertEquals("Redface 2 — PHASE 1 @ ALPHA", summary.title)
        // posts.count = 541 → replyCount = 540, totalPages = ceil(541 / 40) = 14
        assertEquals(540, summary.replyCount)
        assertEquals(14, summary.totalPages)
        assertTrue(summary.isSticky)
        assertEquals("XaTriX", summary.author)
        assertEquals("qwazer", summary.lastReplyAuthor)
        assertNull(summary.subcat) // metadata fixture omits links.subcategory
    }

    @Test
    fun `decodeHtmlEntities handles common entities and numeric forms defensively`() {
        assertEquals("&", "&amp;".decodeHtmlEntities())
        assertEquals("Overclocking, Cooling & Modding", "Overclocking, Cooling &amp; Modding".decodeHtmlEntities())
        assertEquals("\"", "&quot;".decodeHtmlEntities())
        assertEquals("'", "&#39;".decodeHtmlEntities())
        assertEquals("é", "&#233;".decodeHtmlEntities())
        assertEquals("é", "&#xE9;".decodeHtmlEntities())
        // Unknown entity is left untouched
        assertEquals("&unknown;", "&unknown;".decodeHtmlEntities())
        // No-entity strings short-circuit
        assertEquals("plain text", "plain text".decodeHtmlEntities())
    }

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.bufferedReader().use { it.readText() }
    }
}
