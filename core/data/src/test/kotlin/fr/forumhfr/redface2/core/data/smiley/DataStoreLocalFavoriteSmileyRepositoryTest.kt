package fr.forumhfr.redface2.core.data.smiley

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import fr.forumhfr.redface2.core.data.preferences.normalizeLocalAccountOwner
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyExportResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyFailure
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.domain.smiley.LocalFavoriteSmileyRepository
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.LocalFavoriteSmileySnapshot
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

class DataStoreLocalFavoriteSmileyRepositoryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreLocalFavoriteSmileyRepository
    private var storeJob = SupervisorJob()
    private val clock = MutableClock()
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        openStore()
    }

    @After
    fun tearDown() = runTest {
        storeJob.cancelAndJoin()
    }

    @Test
    fun `an owner without favorites has an empty versioned snapshot without creating a key`() = runTest {
        assertEquals(emptyList<LocalFavoriteSmiley>(), repository.observeFavorites(OWNER).first())
        assertEquals(LocalFavoriteSmileySnapshot(1, emptyList(), emptyList(), 0), snapshot())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `adding twice preserves the initial metadata date and order`() = runTest {
        assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, A, "https://example.invalid/a"))
        val initial = snapshot()
        clock.now = 2_000

        assertEquals(LocalFavoriteSmileyMutationResult.AlreadyPresent, repository.add(OWNER, A, "changed"))
        assertEquals(initial, snapshot())
        assertEquals(listOf(LocalFavoriteSmiley(A, "https://example.invalid/a", 1_000)), initial.favorites)
        assertEquals(1_000L, initial.orderUpdatedAt)
    }

    @Test
    fun `removing twice records only the first removal and absent tokens do nothing`() = runTest {
        assertEquals(LocalFavoriteSmileyMutationResult.Unchanged, repository.remove(OWNER, A))
        assertTrue(dataStore.data.first().asMap().isEmpty())
        repository.add(OWNER, A)
        clock.now = 2_000
        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.remove(OWNER, A))
        val removed = snapshot()
        clock.now = 3_000

        assertEquals(LocalFavoriteSmileyMutationResult.Unchanged, repository.remove(OWNER, A))
        assertEquals(removed, snapshot())
        assertTrue(removed.favorites.isEmpty())
        assertEquals(listOf(LocalFavoriteSmileySnapshot.Tombstone(A, 2_000)), removed.tombstones)
        assertEquals(2_000L, removed.orderUpdatedAt)
    }

    @Test
    fun `reactivation removes the tombstone and appends with a new addition date`() = runTest {
        repository.add(OWNER, A, "old")
        repository.add(OWNER, B)
        repository.remove(OWNER, A)
        clock.now = 4_000

        assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, A))
        assertEquals(listOf(B, A), tokens())
        assertEquals(LocalFavoriteSmiley(A, null, 4_000), snapshot().favorites.last())
        assertTrue(snapshot().tombstones.isEmpty())
        assertEquals(4_000L, snapshot().orderUpdatedAt)
    }

    @Test
    fun `move supports middle head and end without changing addition dates`() = runTest {
        listOf(A, B, C).forEach { repository.add(OWNER, it) }
        clock.now = 2_000
        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.move(OWNER, C, B))
        assertEquals(listOf(A, C, B), tokens())
        clock.now = 3_000
        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.move(OWNER, B, A))
        assertEquals(listOf(B, A, C), tokens())
        clock.now = 4_000
        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.move(OWNER, B, null))
        assertEquals(listOf(A, C, B), tokens())
        assertEquals(4_000L, snapshot().orderUpdatedAt)
        assertTrue(snapshot().favorites.all { it.addedAt == 1_000L })
    }

    @Test
    fun `missing moved token missing anchor self move and unchanged order are no ops`() = runTest {
        listOf(A, B).forEach { repository.add(OWNER, it) }
        val initial = snapshot()
        clock.now = 2_000

        listOf(C to A, A to C, A to A, A to B, B to null).forEach { (token, before) ->
            assertEquals(LocalFavoriteSmileyMutationResult.Unchanged, repository.move(OWNER, token, before))
            assertEquals(initial, snapshot())
        }
    }

    @Test
    fun `ordered favorites and tombstones survive closing and reopening the datastore`() = runTest {
        listOf(A, B, C).forEach { repository.add(OWNER, it) }
        repository.move(OWNER, C, A)
        repository.remove(OWNER, B)
        val initial = snapshot()

        storeJob.cancelAndJoin()
        openStore()

        assertEquals(listOf(C, A), tokens())
        assertEquals(initial, snapshot())
    }

    @Test
    fun `export is a faithful serializable versioned document without account identity`() = runTest {
        repository.add(OWNER, A, "https://example.invalid/a")
        clock.now = 2_000
        repository.add(OWNER, B)
        clock.now = 3_000
        repository.remove(OWNER, A)
        val exported = snapshot()

        assertEquals(LocalFavoriteSmileySnapshot.CURRENT_VERSION, exported.version)
        assertEquals(listOf(LocalFavoriteSmiley(B, null, 2_000)), exported.favorites)
        assertEquals(listOf(LocalFavoriteSmileySnapshot.Tombstone(A, 3_000)), exported.tombstones)
        val encoded = json.encodeToString(exported)
        assertEquals(
            setOf("version", "favorites", "tombstones", "orderUpdatedAt"),
            json.parseToJsonElement(encoded).jsonObject.keys,
        )
        assertFalse(encoded.contains(OWNER, ignoreCase = true))
        assertEquals(exported, json.decodeFromString<LocalFavoriteSmileySnapshot>(encoded))
        assertEquals(encoded, rawDocument())
    }

    @Test
    fun `active favorites are limited to 256 while a duplicate remains idempotent at the limit`() = runTest {
        repeat(LocalFavoriteSmileyRepository.MAX_FAVORITES) { index ->
            assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, "[:fictif_$index]"))
        }
        val full = snapshot()
        assertEquals(LocalFavoriteSmileyMutationResult.LimitReached, repository.add(OWNER, A))
        assertEquals(LocalFavoriteSmileyMutationResult.AlreadyPresent, repository.add(OWNER, "[:fictif_0]"))
        assertEquals(full, snapshot())

        repository.remove(OWNER, "[:fictif_0]")
        assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, A))
        assertEquals(256, snapshot().favorites.size)
        assertEquals(listOf("[:fictif_0]"), snapshot().tombstones.map { it.token })
    }

    @Test
    fun `document size is bounded in utf8 bytes and oversized addition preserves all data`() = runTest {
        repository.add(OWNER, A)
        val initial = rawDocument()
        val image = "é".repeat(LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES / 2)

        assertEquals(failure(LocalFavoriteSmileyFailure.DOCUMENT_TOO_LARGE), repository.add(OWNER, B, image))
        assertEquals(initial, rawDocument())
        assertEquals(listOf(A), tokens())
    }

    @Test
    fun `the exact document byte limit is accepted and one more byte is rejected`() = runTest {
        val base = LocalFavoriteSmileySnapshot(1, listOf(LocalFavoriteSmiley(A, "", 1_000)), emptyList(), 1_000)
        val remaining = LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES - json.encodeToString(base).toByteArray().size
        assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, A, "x".repeat(remaining)))
        assertEquals(LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES, rawDocument()!!.toByteArray().size)

        repository.clear(OWNER)
        assertEquals(
            failure(LocalFavoriteSmileyFailure.DOCUMENT_TOO_LARGE),
            repository.add(OWNER, A, "x".repeat(remaining + 1)),
        )
        assertNull(rawDocument())
    }

    @Test
    fun `removal at the byte limit prunes the oldest tombstone and preserves remaining favorites`() = runTest {
        val recent = LocalFavoriteSmileySnapshot.Tombstone("[:recent]", 300)
        val oldest = LocalFavoriteSmileySnapshot.Tombstone("[:oldest]", 100)
        val middle = LocalFavoriteSmileySnapshot.Tombstone("[:middle]", 200)
        val base = LocalFavoriteSmileySnapshot(
            version = 1,
            favorites = listOf(
                LocalFavoriteSmiley(A, null, 0),
                LocalFavoriteSmiley(B, "", 0),
                LocalFavoriteSmiley(C, null, 0),
            ),
            tombstones = listOf(recent, oldest, middle),
            orderUpdatedAt = 0,
        )
        val padding = LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES - json.encodeToString(base).toByteArray().size
        val full = base.copy(
            favorites = base.favorites.map { if (it.token == B) it.copy(imageUrl = "x".repeat(padding)) else it },
        )
        val raw = json.encodeToString(full)
        assertEquals(LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES, raw.toByteArray().size)
        dataStore.edit { it[ownerKey(OWNER)] = raw }
        clock.now = Long.MAX_VALUE
        val unpruned = full.copy(
            favorites = full.favorites.drop(1),
            tombstones = full.tombstones + LocalFavoriteSmileySnapshot.Tombstone(A, clock.now),
            orderUpdatedAt = clock.now,
        )
        assertTrue(json.encodeToString(unpruned).toByteArray().size > LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES)

        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.remove(OWNER, A))

        assertEquals(unpruned.copy(tombstones = unpruned.tombstones - oldest), snapshot())
        assertTrue(rawDocument()!!.toByteArray().size <= LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES)
    }

    @Test
    fun `removal keeps every tombstone when the document fits the byte limit`() = runTest {
        repository.add(OWNER, A)
        repository.add(OWNER, B)
        repository.remove(OWNER, A)
        val previous = snapshot().tombstones
        clock.now = 2_000

        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.remove(OWNER, B))

        assertEquals(previous + LocalFavoriteSmileySnapshot.Tombstone(B, 2_000), snapshot().tombstones)
    }

    @Test
    fun `owners use the super favorite lowercase convention without trimming or anonymous adoption`() = runTest {
        repository.add("ALIce", A)
        repository.add(" Alice ", B)
        repository.add("Bob", C)

        assertEquals("alice", normalizeLocalAccountOwner("ALIce"))
        assertEquals(" alice ", normalizeLocalAccountOwner(" Alice "))
        assertEquals(listOf(A), tokens("alice"))
        assertEquals(listOf(B), tokens(" ALICE "))
        assertEquals(listOf(C), tokens("BOB"))
        assertTrue(repository.observeFavorites("").first().isEmpty())
        assertEquals(listOf(A), tokens("ALICE"))
    }

    @Test
    fun `observers stay scoped to their explicit owner`() = runTest {
        repository.observeFavorites(OWNER).test {
            assertEquals(emptyList<LocalFavoriteSmiley>(), awaitItem())
            repository.add(OWNER, A)
            assertEquals(listOf(A), awaitItem().map { it.token })
            repository.add("Bob", B)
            repository.remove(OWNER, A)
            assertEquals(emptyList<LocalFavoriteSmiley>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank owners reject every write and export without creating any key`() = runTest {
        listOf("", " ", "\t\n").forEach { owner ->
            assertTrue(repository.observeFavorites(owner).first().isEmpty())
            assertEquals(failure(LocalFavoriteSmileyFailure.INVALID_OWNER), repository.add(owner, A))
            assertEquals(failure(LocalFavoriteSmileyFailure.INVALID_OWNER), repository.remove(owner, A))
            assertEquals(failure(LocalFavoriteSmileyFailure.INVALID_OWNER), repository.move(owner, A, null))
            assertEquals(failure(LocalFavoriteSmileyFailure.INVALID_OWNER), repository.clear(owner))
            assertEquals(
                LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.INVALID_OWNER),
                repository.exportSnapshot(owner),
            )
        }
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `blank tokens and move anchors reject mutations without changing storage`() = runTest {
        val refused = failure(LocalFavoriteSmileyFailure.INVALID_TOKEN)
        listOf(emptyList(), listOf(A, B)).forEach { existingTokens ->
            existingTokens.forEach { assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add(OWNER, it)) }
            val initial = dataStore.data.first().asMap()
            listOf("", " ", "\t\n").forEach { token ->
                assertEquals(refused, repository.add(OWNER, token))
                assertEquals(refused, repository.remove(OWNER, token))
                assertEquals(refused, repository.move(OWNER, token, null))
                assertEquals(refused, repository.move(OWNER, A, token))
                assertEquals(initial, dataStore.data.first().asMap())
            }
        }
    }

    @Test
    fun `corrupt unknown incomplete inconsistent and oversized documents are never replaced`() = runTest {
        assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add("Bob", B))
        val valid = LocalFavoriteSmileySnapshot(1, listOf(LocalFavoriteSmiley(A, null, 1_000)), emptyList(), 1_000)
        val documents = listOf(
            "not json",
            "{}",
            "{\"version\":1}",
            json.encodeToString(valid.copy(version = 2)),
            json.encodeToString(valid.copy(favorites = valid.favorites + valid.favorites)),
            json.encodeToString(valid.copy(tombstones = listOf(LocalFavoriteSmileySnapshot.Tombstone(A, 2_000)))),
            json.encodeToString(valid.copy(tombstones = List(2) { LocalFavoriteSmileySnapshot.Tombstone(C, 2_000) })),
            json.encodeToString(valid.copy(favorites = List(257) { LocalFavoriteSmiley("[:fictif_$it]", null, 1) })),
            "x".repeat(LocalFavoriteSmileyRepository.MAX_DOCUMENT_BYTES + 1),
        )
        documents.forEachIndexed { index, raw ->
            dataStore.edit { it[ownerKey(OWNER)] = raw }
            assertTrue(repository.observeFavorites(OWNER).first().isEmpty())
            assertEquals(
                LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.UNREADABLE_DOCUMENT),
                repository.exportSnapshot(OWNER),
            )
            val refused = failure(LocalFavoriteSmileyFailure.UNREADABLE_DOCUMENT)
            assertEquals(refused, repository.add(OWNER, A))
            assertEquals(refused, repository.remove(OWNER, A))
            assertEquals(refused, repository.move(OWNER, A, null))
            assertEquals(refused, repository.clear(OWNER))
            val bobToken = "[:fictif_bob_$index]"
            assertEquals(LocalFavoriteSmileyMutationResult.Added, repository.add("Bob", bobToken))
            assertEquals(listOf(B) + (0..index).map { "[:fictif_bob_$it]" }, tokens("Bob"))
            assertEquals(raw, rawDocument())
        }
    }

    @Test
    fun `tokens with spaces and variants remain distinct and byte for byte unchanged`() = runTest {
        val exactTokens = listOf("[:fictif_a]", "[:fictif b]", "[:fictif b:3]", "[:Fictif_A]", " [:fictif_a] ")
        exactTokens.forEach { repository.add(OWNER, it) }
        assertEquals(exactTokens, tokens())
        repository.remove(OWNER, "[:fictif b:3]")
        assertEquals(listOf("[:fictif b:3]"), snapshot().tombstones.map { it.token })
        assertEquals(exactTokens - "[:fictif b:3]", tokens())
    }

    @Test
    fun `parallel additions across repository instances lose no favorites`() = runTest {
        val otherRepository = DataStoreLocalFavoriteSmileyRepository(dataStore, clock)
        val results = (0 until 64).map { index ->
            async(Dispatchers.Default) {
                val target = if (index % 2 == 0) repository else otherRepository
                target.add(OWNER, "[:fictif_$index]")
            }
        }.awaitAll()

        assertTrue(results.all { it == LocalFavoriteSmileyMutationResult.Added })
        assertEquals((0 until 64).map { "[:fictif_$it]" }.toSet(), tokens().toSet())
        assertEquals(64, tokens().size)
    }

    @Test
    fun `clear erases only the explicit owner including tombstones and is idempotent`() = runTest {
        repository.add(OWNER, A)
        repository.remove(OWNER, A)
        repository.add(OWNER, C)
        repository.add("Bob", B)

        assertEquals(LocalFavoriteSmileyMutationResult.Updated, repository.clear(OWNER))
        assertNull(rawDocument())
        assertEquals(LocalFavoriteSmileyMutationResult.Unchanged, repository.clear(OWNER))
        assertEquals(emptyList<LocalFavoriteSmiley>(), snapshot().favorites)
        assertTrue(snapshot().tombstones.isEmpty())
        assertEquals(listOf(B), tokens("Bob"))
    }

    @Test
    fun `datastore read failures degrade observation but explicitly refuse export and writes`() = runTest {
        val broken = object : DataStore<Preferences> by dataStore {
            override val data: Flow<Preferences> = flow { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("unavailable")
        }
        val brokenRepository = DataStoreLocalFavoriteSmileyRepository(broken, clock)

        assertTrue(brokenRepository.observeFavorites(OWNER).first().isEmpty())
        assertEquals(
            LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE),
            brokenRepository.exportSnapshot(OWNER),
        )
        assertEquals(failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE), brokenRepository.add(OWNER, A))
    }

    @Test
    fun `physical preferences corruption preserves bytes and refuses mutations and export for every owner`() = runTest {
        storeJob.cancelAndJoin()
        val file = tempFolder.root.resolve("favorites.preferences_pb")
        // Truncated protobuf field: declares five bytes but contains only one.
        val corruptBytes = byteArrayOf(0x0A, 0x05, 0x41)
        file.writeBytes(corruptBytes)
        openStore()

        listOf(OWNER, "Bob").forEach { owner ->
            assertTrue(repository.observeFavorites(owner).first().isEmpty())
            assertEquals(
                LocalFavoriteSmileyExportResult.Failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE),
                repository.exportSnapshot(owner),
            )
            val refused = failure(LocalFavoriteSmileyFailure.STORAGE_FAILURE)
            assertEquals(refused, repository.add(owner, A))
            assertEquals(refused, repository.remove(owner, A))
            assertEquals(refused, repository.move(owner, A, null))
            assertEquals(refused, repository.clear(owner))
            assertArrayEquals(corruptBytes, file.readBytes())
        }
    }

    private fun openStore() {
        storeJob = SupervisorJob()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { tempFolder.root.resolve("favorites.preferences_pb") },
        )
        repository = DataStoreLocalFavoriteSmileyRepository(dataStore, clock)
    }

    private suspend fun snapshot(owner: String = OWNER): LocalFavoriteSmileySnapshot =
        (repository.exportSnapshot(owner) as LocalFavoriteSmileyExportResult.Success).snapshot

    private suspend fun tokens(owner: String = OWNER): List<String> =
        repository.observeFavorites(owner).first().map { it.token }

    private suspend fun rawDocument(): String? = dataStore.data.first()[ownerKey(OWNER)]

    private fun ownerKey(owner: String): Preferences.Key<String> =
        stringPreferencesKey("local_favorite_smileys_${owner.lowercase()}")

    private fun failure(reason: LocalFavoriteSmileyFailure) = LocalFavoriteSmileyMutationResult.Failure(reason)

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
