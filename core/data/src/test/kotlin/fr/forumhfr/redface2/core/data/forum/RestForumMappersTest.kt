package fr.forumhfr.redface2.core.data.forum

import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Anonymous response → hasUnread / lastReadPage / lastPostReadId stay null
        assertNull(tmListTopic.hasUnread)
        assertNull(tmListTopic.lastReadPage)
        assertNull(tmListTopic.lastPostReadId)
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
    fun `authenticated topic surfaces is_read flag_owntopic last_position last_post_read_id`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_cat23_participated.json"),
        )

        val page = RestForumMappers.toTopicListPage(envelope, cat = 23, subcat = null)

        val topic = page.topics.single()
        assertEquals(35395, topic.topicId)
        // is_read = false → hasUnread = true
        assertEquals(true, topic.hasUnread)
        // last_position = 479 → exposed as lastReadPage in the model
        assertEquals(479, topic.lastReadPage)
        assertEquals(2_783_256, topic.lastPostReadId)
        // subcat extracted from links.subcategory.href
        assertEquals(550, topic.subcat)
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
