package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.data.forum.RestListEnvelope
import fr.forumhfr.redface2.core.data.forum.RestTopic
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based tests for the REST → [fr.forumhfr.redface2.core.model.Flag] mapper.
 * The captured fixture is the per-cat variant (`rest_cat23_participated.json`); the
 * mapper is shape-compatible with the global variant since both endpoints return the
 * same [RestListEnvelope]<[RestTopic]> shape with topics carrying their own
 * `links.category.href`, exercised here through synthetic payloads.
 */
class RestFlagMappersTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `per-cat fixture maps to a single Flag with REST-derived fields`() {
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_cat23_participated.json"),
        )

        val flags = RestFlagMappers.toFlags(
            envelope = envelope,
            type = FlagType.CYAN,
            fallbackCat = 23,
        )

        assertEquals(1, flags.size)
        val flag = flags.single()
        assertEquals(35395, flag.topicId)
        assertEquals(23, flag.cat)
        assertEquals(550, flag.subcat)
        assertEquals(FlagType.CYAN, flag.type)
        assertTrue("is_read=false → hasUnread", flag.hasUnread)
        assertEquals(12, flag.lastReadPage)
        // last_post_read_id in the fixture = 2 783 256
        assertEquals(2_783_256L, flag.lastPostReadId)
        // posts.count=541, results_per_page=40 → totalPages = ceil(541/40) = 14
        assertEquals(14, flag.totalPages)
        // replyCount = max(count - 1, 0)
        assertEquals(540, flag.replyCount)
        assertEquals("XaTriX", flag.firstPostAuthor)
        assertEquals("qwazer", flag.lastReplyAuthor)
        assertEquals("2026-05-01 17:07", flag.lastReplyAt)
    }

    @Test
    fun `global form derives cat from links_category_href when fallbackCat is null`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 2,
                "results_per_page": 50,
                "resources": [
                  {
                    "id": 1,
                    "title": "Topic in cat 23",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/"},
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/23/topics/1/posts/?page=1&results_per_page=40", "count": 1}
                    },
                    "is_read": false,
                    "flag_owntopic": 2,
                    "last_post_read_id": 1000
                  },
                  {
                    "id": 2,
                    "title": "Topic in cat 13",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/2/posts/?page=5&results_per_page=40", "count": 200}
                    },
                    "is_read": true,
                    "flag_owntopic": 2,
                    "last_post_read_id": 2000
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flags = RestFlagMappers.toFlags(
            envelope = envelope,
            type = FlagType.RED,
            fallbackCat = null,
        )

        assertEquals(2, flags.size)
        assertEquals(setOf(23, 13), flags.map { it.cat }.toSet())
        val cat23 = flags.single { it.topicId == 1 }
        assertEquals(23, cat23.cat)
        assertEquals(FlagType.RED, cat23.type)
        assertTrue(cat23.hasUnread)
        assertEquals(1, cat23.lastReadPage)
        val cat13 = flags.single { it.topicId == 2 }
        assertEquals(13, cat13.cat)
        assertFalse("is_read=true → !hasUnread", cat13.hasUnread)
        assertEquals(5, cat13.lastReadPage)
    }

    @Test
    fun `topic without category link and no fallback is dropped`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 99,
                    "title": "Orphaned",
                    "links": {"posts": {"count": 1}},
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flags = RestFlagMappers.toFlags(envelope, type = FlagType.CYAN, fallbackCat = null)

        assertTrue("orphaned topic must be dropped", flags.isEmpty())
    }

    @Test
    fun `participated bucket types favorited rows as the requested bucket - regression 384`() {
        // Live-captured proof (2026-06-11): the participated bucket returns
        // participated-AND-favorited topics with flag_owntopic=3. Typing them FAVORITE made
        // replaceForType(CYAN) persist them under the wrong Room type, so a Room-served
        // « Mes sujets » silently lost its favorited topics after a tab round-trip (#384).
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(
            fixture("rest_cat13_participated_favorites.json"),
        )

        val flags = RestFlagMappers.toFlags(
            envelope = envelope,
            type = FlagType.CYAN,
            fallbackCat = 13,
        )

        assertEquals(3, flags.size)
        val favorited = flags.filter { it.topicId == 26595 || it.topicId == 55667 }
        assertEquals("the two flag_owntopic=3 rows must be present", 2, favorited.size)
        assertTrue(
            "every row of a CYAN fetch is typed CYAN, whatever flag_owntopic says",
            flags.all { it.type == FlagType.CYAN },
        )
    }

    @Test
    fun `flag_owntopic never drives the type - the requested bucket does`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 7,
                    "title": "Future bucket",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"count": 1}
                    },
                    "flag_owntopic": 4,
                    "is_read": false
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flags = RestFlagMappers.toFlags(envelope, type = FlagType.FAVORITE, fallbackCat = null)

        val flag = flags.single()
        assertEquals(FlagType.FAVORITE, flag.type)
    }

    @Test
    fun `missing last_post_read_id surfaces as null`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 8,
                    "title": "no scroll anchor",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/8/posts/?page=2&results_per_page=40", "count": 50}
                    },
                    "flag_owntopic": 1,
                    "is_read": true
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flag = RestFlagMappers.toFlags(envelope, type = FlagType.CYAN, fallbackCat = null).single()
        assertNull(flag.lastPostReadId)
        // hasUnread defensively false when REST tells us is_read=true
        assertFalse(flag.hasUnread)
    }

    @Test
    fun `posts results_per_page drives totalPages, not a hardcoded 40`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 9,
                    "title": "20-per-page bucket",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/topics/9/posts/?page=1&results_per_page=20", "count": 81}
                    },
                    "flag_owntopic": 1
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flag = RestFlagMappers.toFlags(envelope, type = FlagType.CYAN, fallbackCat = null).single()
        // ceil(81/20) = 5 — not ceil(81/40) = 3 — confirms the mapper trusts the href bucket.
        assertEquals(5, flag.totalPages)
    }

    @Test
    fun `mapper does not crash on resources omitting authenticated fields`() {
        val payload = """
            {
              "resource": {
                "page": 1,
                "results_count": 1,
                "results_per_page": 1,
                "resources": [
                  {
                    "id": 10,
                    "title": "anonymous-shaped row",
                    "links": {
                      "category": {"href": "https://forum.hardware.fr/api/forums/hardwarefr/categories/13/"},
                      "posts": {"count": 0}
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val envelope = json.decodeFromString<RestListEnvelope<RestTopic>>(payload)

        val flag = RestFlagMappers.toFlags(envelope, type = FlagType.CYAN, fallbackCat = null).single()
        assertNotNull(flag)
        assertEquals(FlagType.CYAN, flag.type)
        // Defensive: missing is_read → assume hasUnread (worse to claim "all read" than the inverse).
        assertTrue(flag.hasUnread)
        assertEquals(1, flag.lastReadPage)
        assertEquals(1, flag.totalPages)
        assertEquals(0, flag.replyCount)
    }

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "fixture missing: $name"
        }
        return resource.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
}
