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
        assertEquals(old.coverage.single(), merged.coverage.first())
        assertFalse(merged.toResult().coverage.isComplete)
        assertTrue(merged.toResult().coverage.isGlobalObservation)
        assertEquals(FlagRefreshStatus.PARTIAL, merged.toResult().refreshStatus)
    }

    @Test
    fun `global positives preserve stickies while a successful bucket reconciles global rows only in its category`() {
        val flags = decodeGlobalFlags(json, fixture("participated"), FlagType.CYAN)
        val sticky = flags.first().toEntity("agent", now.minusSeconds(30), FlagSource.STICKY)
        val global = mergeFlagSnapshot(FlagCacheSnapshot(listOf(sticky), emptyList()), "agent", FlagType.CYAN,
            FlagRefreshBatch(null, emptyList(), now, globalOnly = true, observations = flags))
        assertEquals(sticky, global.rows.first { it.topicId == sticky.topicId })
        val cat = flags.last().cat
        val reconciled = mergeFlagSnapshot(global, "agent", FlagType.CYAN,
            FlagRefreshBatch(null, listOf(CategoryRefreshOutcome(cat, FlagSource.BUCKET,
                receivedAt = now.plusSeconds(1))), now.plusSeconds(1)))
        assertTrue(reconciled.rows.none { it.cat == cat && it.source == FlagSource.GLOBAL })
        assertTrue(reconciled.rows.contains(sticky))
        assertEquals(global.rows.filter { it.cat != cat }, reconciled.rows.filter { it.cat != cat })
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader)
        .getResource("fixtures/rest_global_$name.json")!!.readText()
}
