package fr.forumhfr.redface2.core.data.smiley

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageFailure
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageMutationResult
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageRepository
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSmileyUsageRepositoryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreSmileyUsageRepository
    private var storeJob = SupervisorJob()
    private val clock = MutableClock()

    @Before
    fun setUp() = openStore()

    @After
    fun tearDown() = runTest { storeJob.cancelAndJoin() }

    @Test
    fun `empty owner history does not create a document`() = runTest {
        assertTrue(top().isEmpty())
        assertEquals(SmileyUsageMutationResult.Unchanged, repository.record(OWNER, emptyMap()))
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `counts accumulate and rank by count then date then exact token`() = runTest {
        repository.record(OWNER, mapOf(A to 3, B to 2, C to 2))
        clock.now = 2_000
        repository.record(OWNER, mapOf(B to 1))
        assertEquals(
            listOf(SmileyUsageEntry(B, 3, 2_000), SmileyUsageEntry(A, 3, 1_000), SmileyUsageEntry(C, 2, 1_000)),
            top(),
        )
        clock.now = 3_000
        repository.record(OWNER, mapOf(C to 1, A to 1, B to 1))
        assertEquals(listOf(A, B, C), top().map { it.token })
        assertEquals(listOf(4L, 4L, 3L), top().map { it.count })
    }

    @Test
    fun `top defaults to 24 and supports a caller limit without erasing tracked entries`() = runTest {
        repository.record(OWNER, (0 until 40).associate { "[:fictif_$it]" to it + 1 })
        assertEquals(24, top().size)
        assertEquals(listOf("[:fictif_39]", "[:fictif_38]"), top(limit = 2).map { it.token })
        assertEquals(40, top(limit = 512).size)
        assertTrue(top(limit = 0).isEmpty())
        assertTrue(top(limit = -1).isEmpty())
        assertEquals(40, top(limit = Int.MAX_VALUE).size)
    }

    @Test
    fun `eviction keeps exactly 512 highest counts newest uses and lexical ties`() = runTest {
        repository.record(OWNER, (0 until 510).associate { "[:fictif_kept_$it]" to 5 })
        repository.record(OWNER, mapOf("[:fictif_old_a]" to 2, "[:fictif_old_b]" to 2))
        clock.now = 2_000
        repository.record(
            OWNER,
            mapOf("[:fictif_new_b]" to 2, "[:fictif_new_a]" to 2, "[:fictif_low]" to 1),
        )
        val expected = (0 until 510).map { SmileyUsageEntry("[:fictif_kept_$it]", 5, 1_000) }
            .sortedBy { it.token } +
            listOf(SmileyUsageEntry("[:fictif_new_a]", 2, 2_000), SmileyUsageEntry("[:fictif_new_b]", 2, 2_000))
        assertEquals(expected, top(limit = 512))

        // Same count and date: keep the lexically earlier token at the cutoff.
        repository.record(OWNER, mapOf("[:fictif_new_aa]" to 2))
        assertEquals(
            expected.dropLast(1) + SmileyUsageEntry("[:fictif_new_aa]", 2, 2_000),
            top(limit = 512),
        )
    }

    @Test
    fun `owners normalize lowercase only and never adopt anonymous or other accounts`() = runTest {
        repository.record("ALIce", mapOf(A to 1))
        repository.record(" Alice ", mapOf(B to 1))
        repository.record("Bob", mapOf(C to 1))
        assertEquals(listOf(A), top("alice").map { it.token })
        assertEquals(listOf(B), top(" ALICE ").map { it.token })
        assertEquals(listOf(C), top("BOB").map { it.token })
        assertTrue(top("Carol").isEmpty())
        assertTrue(top("").isEmpty())
    }

    @Test
    fun `tokens preserve exact case spaces and variants without image metadata`() = runTest {
        val tokens = listOf("[:fictif_a]", "[:Fictif_A]", "[:fictif b]", "[:fictif b:3]")
        repository.record(OWNER, tokens.associateWith { 1 })
        assertEquals(tokens.sorted(), top().map { it.token })
        repository.record(OWNER, mapOf("[:fictif b:3]" to 1))
        assertEquals(SmileyUsageEntry("[:fictif b:3]", 2, 1_000), top().first())
        assertEquals(4, top().size)
    }

    @Test
    fun `blank owners and invalid occurrences refuse writes without creating keys`() = runTest {
        listOf("", " ", "\t\n").forEach { owner ->
            assertEquals(failure(SmileyUsageFailure.INVALID_OWNER), repository.record(owner, mapOf(A to 1)))
            assertEquals(failure(SmileyUsageFailure.INVALID_OWNER), repository.clear(owner))
            assertTrue(top(owner).isEmpty())
        }
        listOf(mapOf(" " to 1), mapOf(A to 0), mapOf(A to -1), mapOf(A to 1, B to 0)).forEach { usages ->
            assertEquals(failure(SmileyUsageFailure.INVALID_USAGES), repository.record(OWNER, usages))
        }
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `corrupt unknown incomplete duplicate or invalid documents are preserved even on clear`() = runTest {
        val entry = """{"token":"[:fictif_a]","count":1,"lastUsedAt":1000}"""
        val documents = listOf(
            "not json", "{}", """{"version":1}""", """{"version":2,"entries":[]}""",
            """{"version":1,"entries":[],"future":true}""",
            """{"version":1,"entries":[$entry,$entry]}""",
            """{"version":1,"entries":[${entry.replace("\"count\":1", "\"count\":0")}]}""",
            """{"version":1,"entries":[${entry.replace("[:fictif_a]", " ")}]}""",
            """{"version":1,"entries":[${(0..512).joinToString { entry.replace("fictif_a", "fictif_$it") }}]}""",
        )
        documents.forEach { raw ->
            dataStore.edit { it[ownerKey(OWNER)] = raw }
            assertTrue(top().isEmpty())
            assertEquals(failure(SmileyUsageFailure.UNREADABLE_DOCUMENT), repository.record(OWNER, mapOf(B to 1)))
            assertEquals(failure(SmileyUsageFailure.UNREADABLE_DOCUMENT), repository.clear(OWNER))
            repository.record("Bob", mapOf(C to 1))
            assertEquals(raw, rawDocument())
            assertEquals(listOf(C), top("Bob").map { it.token })
        }
    }

    @Test
    fun `persisted versioned document contains only tokens counts and dates without owner`() = runTest {
        repository.record(OWNER, mapOf(A to 2, "[:fictif b:3]" to 1))
        val expected = top()
        val raw = requireNotNull(rawDocument())
        val document = Json.parseToJsonElement(raw).jsonObject
        assertEquals(setOf("version", "entries"), document.keys)
        document.getValue("entries").jsonArray.forEach { entry ->
            assertEquals(setOf("token", "count", "lastUsedAt"), entry.jsonObject.keys)
        }
        assertFalse(raw.contains(OWNER, ignoreCase = true))
        storeJob.cancelAndJoin()
        openStore()
        assertEquals(expected, top())
        repository.record(OWNER, mapOf(A to 1))
        assertEquals(3L, top().first().count)
    }

    @Test
    fun `parallel records across repository instances lose no increments`() = runTest {
        val other = DataStoreSmileyUsageRepository(dataStore, clock)
        val results = (0 until 64).map { index ->
            async(Dispatchers.Default) {
                val target = if (index % 2 == 0) repository else other
                target.record(OWNER, mapOf(A to 1, "[:fictif_$index]" to 1))
            }
        }.awaitAll()
        assertTrue(results.all { it == SmileyUsageMutationResult.Updated })
        assertEquals(64L, top().first().count)
        assertEquals(65, top(limit = 512).size)
    }

    @Test
    fun `clear erases only the explicit owner and is idempotent`() = runTest {
        repository.record(OWNER, mapOf(A to 1))
        repository.record("Bob", mapOf(B to 1))
        assertEquals(SmileyUsageMutationResult.Updated, repository.clear(OWNER))
        assertNull(rawDocument())
        assertTrue(top().isEmpty())
        assertEquals(SmileyUsageMutationResult.Unchanged, repository.clear(OWNER))
        assertEquals(listOf(B), top("Bob").map { it.token })
    }

    @Test
    fun `counter overflow saturates instead of corrupting the document`() = runTest {
        dataStore.edit {
            it[ownerKey(OWNER)] = """{"version":1,"entries":[
                {"token":"$A","count":${Long.MAX_VALUE - 1},"lastUsedAt":0}]}"""
        }
        repository.record(OWNER, mapOf(A to 5))
        assertEquals(listOf(SmileyUsageEntry(A, Long.MAX_VALUE, 1_000)), top())
    }

    @Test
    fun `storage failures degrade observation and return explicit mutation failures`() = runTest {
        val broken = object : DataStore<Preferences> by dataStore {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("unavailable")
        }
        val target = DataStoreSmileyUsageRepository(broken, clock)
        assertTrue(target.observeTop(OWNER).first().isEmpty())
        assertEquals(failure(SmileyUsageFailure.STORAGE_FAILURE), target.record(OWNER, mapOf(A to 1)))
        assertEquals(failure(SmileyUsageFailure.STORAGE_FAILURE), target.clear(OWNER))
    }

    @Test
    fun `physical preferences corruption is never repaired or overwritten`() = runTest {
        storeJob.cancelAndJoin()
        val file = tempFolder.root.resolve("usage.preferences_pb")
        val corruptBytes = byteArrayOf(0x0A, 0x05, 0x41)
        file.writeBytes(corruptBytes)
        openStore()
        listOf(OWNER, "Bob").forEach { owner ->
            assertTrue(top(owner).isEmpty())
            assertEquals(failure(SmileyUsageFailure.STORAGE_FAILURE), repository.record(owner, mapOf(A to 1)))
            assertEquals(failure(SmileyUsageFailure.STORAGE_FAILURE), repository.clear(owner))
            assertArrayEquals(corruptBytes, file.readBytes())
        }
    }

    private fun openStore() {
        storeJob = SupervisorJob()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { tempFolder.root.resolve("usage.preferences_pb") },
        )
        repository = DataStoreSmileyUsageRepository(dataStore, clock)
    }

    private suspend fun top(owner: String = OWNER, limit: Int = SmileyUsageRepository.TOP_LIMIT) =
        repository.observeTop(owner, limit).first()

    private suspend fun rawDocument() = dataStore.data.first()[ownerKey(OWNER)]

    private fun ownerKey(owner: String) = stringPreferencesKey("smiley_usage_${owner.lowercase()}")

    private fun failure(reason: SmileyUsageFailure) = SmileyUsageMutationResult.Failure(reason)

    private class MutableClock(var now: Long = 1_000) : Clock() {
        override fun millis(): Long = now
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    private companion object {
        const val OWNER = "Alice"
        const val A = "[:fictif_a]"
        const val B = "[:fictif_b]"
        const val C = "[:fictif_c]"
    }
}
