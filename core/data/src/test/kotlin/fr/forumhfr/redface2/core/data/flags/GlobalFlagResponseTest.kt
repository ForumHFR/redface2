package fr.forumhfr.redface2.core.data.flags

import fr.forumhfr.redface2.core.database.dao.FlagCacheSnapshot
import fr.forumhfr.redface2.core.database.entities.FlagCategoryCoverageEntity
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.FlagType
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalFlagResponseTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-11-15T12:00:00Z")

    @Test
    fun `three captured bucket shapes map group categories without trusting results count`() {
        listOf("participated", "read", "favorites").zip(FlagType.entries).forEach { (bucket, type) ->
            val flags = decodeGlobalFlags(json, fixture(bucket), type)
            assertTrue(flags.isNotEmpty())
            assertTrue(flags.all { it.type == type && it.cat > 0 && it.title.startsWith("Sujet ") })
            assertTrue(flags.all { it.firstPostAuthor == "auteur1" && it.lastReplyAuthor == "auteur1" })
            assertEquals(flags.size, flags.distinctBy { it.cat to it.topicId }.size)
        }
    }

    @Test
    fun `duplicates are observations of one logical row`() {
        assertEquals(decodeGlobalFlags(json, fixture("participated"), FlagType.CYAN),
            decodeGlobalFlags(json, fixture("duplicate"), FlagType.CYAN))
        val conflicting = fixture("duplicate").replaceFirst("Sujet 1", "Conflicting observation")
        assertTrue(runCatching { decodeGlobalFlags(json, conflicting, FlagType.CYAN) }.exceptionOrNull()
            is SerializationException)
    }

    @Test
    fun `wrong category and truncated group reject the response`() {
        listOf("wrong_category", "truncated").forEach { variant ->
            val failure = runCatching { decodeGlobalFlags(json, fixture(variant), FlagType.CYAN) }.exceptionOrNull()
            assertTrue("$variant must fail schema validation", failure is SerializationException)
        }
    }

    @Test
    fun `missing post identity cannot fabricate a read position`() {
        val body = fixture("participated").replace("\"posts\":", "\"missing_posts\":")
        assertTrue(runCatching { decodeGlobalFlags(json, body, FlagType.CYAN) }.exceptionOrNull()
            is SerializationException)
    }

    @Test
    fun `empty global preserves every old row position unread state and source date after Room mapping`() {
        val flag = decodeGlobalFlags(json, fixture("participated"), FlagType.CYAN).first()
        val oldTime = now.minusSeconds(300)
        val rows = listOf(flag.toEntity("agent", oldTime, FlagSource.STICKY),
            flag.copy(topicId = 999, hasUnread = false, lastReadPage = 7, lastPosition = 240)
                .toEntity("agent", oldTime, FlagSource.BUCKET))
        val old = FlagCacheSnapshot(rows, listOf(FlagCategoryCoverageEntity("agent", FlagType.CYAN,
            flag.cat, FlagSource.STICKY, oldTime, oldTime, null)))
        val merged = mergeFlagSnapshot(old, "agent", FlagType.CYAN,
            FlagRefreshBatch(null, emptyList(), now, globalOnly = true))
        assertEquals(rows, merged.rows)
        assertEquals(old, merged)
        assertFalse(merged.toResult().coverage.isComplete)
        assertFalse(merged.toResult().coverage.isGlobalObservation)
        assertEquals(FlagRefreshStatus.FAILED, merged.toResult().refreshStatus)
    }

    @Test
    fun `contradictory subcategory category or posts subcategory rejects the whole global batch`() {
        val original = "categories/1/subcategories/109/"
        for (replacement in listOf("categories/23/subcategories/484/", "categories/1/subcategories/484/")) {
            val body = fixture("participated").replaceFirst(original, replacement)
            assertTrue(runCatching { decodeGlobalFlags(json, body, FlagType.CYAN) }.exceptionOrNull()
                is SerializationException)
        }
    }

    @Test
    fun `global read page seven cannot overwrite an unread favorite at page two`() {
        val observed = decodeGlobalFlags(json, fixture("favorites"), FlagType.FAVORITE).first()
            .copy(hasUnread = false, isFavorite = false, lastReadPage = 7, lastPosition = 280,
                lastPostReadId = 700L, totalPages = 7, replyCount = 280)
        val previous = observed.copy(hasUnread = true, isFavorite = true, lastReadPage = 2,
            lastPosition = 60, lastPostReadId = 200L, totalPages = 9, replyCount = 350)
            .toEntity("agent", now.minusSeconds(60), FlagSource.BUCKET)
        val merged = mergeFlagSnapshot(FlagCacheSnapshot(listOf(previous), emptyList()), "agent", FlagType.FAVORITE,
            FlagRefreshBatch(null, emptyList(), now, globalOnly = true, observations = listOf(observed)))
        assertEquals(previous, merged.rows.single())
    }

    @Test
    fun `global may signal unread favorite and growth but never move a reading anchor backwards`() {
        val observed = decodeGlobalFlags(json, fixture("participated"), FlagType.CYAN).first()
            .copy(hasUnread = true, isFavorite = true, lastReadPage = 1, lastPosition = null,
                lastPostReadId = null, totalPages = 12, replyCount = 450, lastReplyAt = "2026-11-16 12:00",
                lastReplyAuthor = "new author")
        val previous = observed.copy(hasUnread = false, isFavorite = false, lastReadPage = 8,
            lastPosition = 310, lastPostReadId = 800L, totalPages = 10, replyCount = 380,
            lastReplyAt = "2026-11-15 12:00", lastReplyAuthor = "old author")
            .toEntity("agent", now.minusSeconds(60), FlagSource.BUCKET)
        val merged = mergeFlagSnapshot(FlagCacheSnapshot(listOf(previous), emptyList()), "agent", FlagType.CYAN,
            FlagRefreshBatch(null, emptyList(), now, globalOnly = true, observations = listOf(observed)))
        assertEquals(previous.copy(hasUnread = true, isFavorite = true, totalPages = 12, replyCount = 450,
            lastReplyAt = observed.lastReplyAt, lastReplyAuthor = observed.lastReplyAuthor), merged.rows.single())
    }

    @Test
    fun `baseline catalogue failure removes live and persisted global markers and reports failure`() {
        val observed = decodeGlobalFlags(json, fixture("participated"), FlagType.CYAN).first()
        val global = mergeFlagSnapshot(FlagCacheSnapshot(emptyList(), emptyList()), "agent", FlagType.CYAN,
            FlagRefreshBatch(null, emptyList(), now, globalOnly = true, observations = listOf(observed)))
        for (source in listOf(FlagSource.GLOBAL, FlagSource.LEGACY_UNKNOWN)) {
            val old = global.copy(coverage = global.coverage.map { it.copy(source = source) })
            val failed = mergeFlagSnapshot(old, "agent", FlagType.CYAN,
                FlagRefreshBatch(null, emptyList(), now.plusSeconds(1), cause = java.io.IOException("offline")))
            assertFalse(failed.toResult().coverage.isGlobalObservation)
            assertEquals(FlagRefreshStatus.FAILED, failed.toResult().refreshStatus)
            assertTrue(failed.coverage.all { it.cat > 0 && it.failure != null })
        }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader)
        .getResource("fixtures/rest_global_$name.json")!!.readText()
}
