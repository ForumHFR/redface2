package fr.forumhfr.redface2.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.forumhfr.redface2.core.database.RedfaceDatabase
import fr.forumhfr.redface2.core.database.entities.UploadedImageEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UploadedImageDaoTest {

    private lateinit var database: RedfaceDatabase
    private lateinit var dao: UploadedImageDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            RedfaceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.uploadedImageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `observeForUser returns rows for that user, most recent first`() = runTest {
        dao.upsert(row(userId = "alice", picId = "A", uploadedAt = Instant.ofEpochMilli(1_000)))
        dao.upsert(row(userId = "alice", picId = "B", uploadedAt = Instant.ofEpochMilli(3_000)))
        dao.upsert(row(userId = "alice", picId = "C", uploadedAt = Instant.ofEpochMilli(2_000)))

        assertEquals(listOf("B", "C", "A"), dao.observeForUser("alice").first().map { it.picId })
    }

    @Test
    fun `observeForUser isolates rows by userId`() = runTest {
        dao.upsert(row(userId = "alice", picId = "A"))
        dao.upsert(row(userId = "bob", picId = "B"))

        assertEquals(listOf("A"), dao.observeForUser("alice").first().map { it.picId })
        assertEquals(listOf("B"), dao.observeForUser("bob").first().map { it.picId })
    }

    @Test
    fun `upsert replaces a row with the same composite key`() = runTest {
        dao.upsert(row(userId = "alice", picId = "A", imageUrl = "https://old/A"))
        dao.upsert(row(userId = "alice", picId = "A", imageUrl = "https://new/A"))

        val rows = dao.observeForUser("alice").first()
        assertEquals(1, rows.size)
        assertEquals("https://new/A", rows.single().imageUrl)
    }

    @Test
    fun `same picId under different providers are distinct rows`() = runTest {
        dao.upsert(row(userId = "alice", provider = "DIBERIE", picId = "DUP"))
        dao.upsert(row(userId = "alice", provider = "IMGUR", picId = "DUP"))

        assertEquals(2, dao.observeForUser("alice").first().size)
    }

    @Test
    fun `delete removes only the matching userId, provider and picId`() = runTest {
        dao.upsert(row(userId = "alice", provider = "DIBERIE", picId = "A"))
        dao.upsert(row(userId = "alice", provider = "IMGUR", picId = "A"))
        dao.upsert(row(userId = "bob", provider = "DIBERIE", picId = "A"))

        dao.delete(userId = "alice", provider = "DIBERIE", picId = "A")

        // The IMGUR row under the same picId survives — provider is part of the key.
        assertEquals(listOf("IMGUR"), dao.observeForUser("alice").first().map { it.provider })
        assertEquals(1, dao.observeForUser("bob").first().size)
    }

    @Test
    fun `deleteAllForUser purges every row for that user only`() = runTest {
        dao.upsert(row(userId = "alice", picId = "A"))
        dao.upsert(row(userId = "alice", picId = "B"))
        dao.upsert(row(userId = "bob", picId = "C"))

        dao.deleteAllForUser("alice")

        assertTrue(dao.observeForUser("alice").first().isEmpty())
        assertEquals(listOf("C"), dao.observeForUser("bob").first().map { it.picId })
    }

    @Test
    fun `nullable columns round-trip`() = runTest {
        dao.upsert(
            row(userId = "alice", picId = "A", thumbnailUrl = null, deleteHandle = null, expiresAt = null),
        )

        val stored = dao.observeForUser("alice").first().single()
        assertEquals(null, stored.thumbnailUrl)
        assertEquals(null, stored.deleteHandle)
        assertEquals(null, stored.expiresAt)
    }

    @Suppress("LongParameterList") // Test row factory: every field has a default so call-sites stay terse.
    private fun row(
        userId: String,
        picId: String,
        provider: String = "DIBERIE",
        imageUrl: String = "https://host/Picture/Get/f/$picId",
        thumbnailUrl: String? = "https://host/Picture/Get/t/$picId",
        deleteHandle: String? = picId,
        uploadedAt: Instant = Instant.ofEpochMilli(1_000),
        expiresAt: Instant? = null,
    ): UploadedImageEntity = UploadedImageEntity(
        userId = userId,
        provider = provider,
        picId = picId,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        deleteHandle = deleteHandle,
        uploadedAt = uploadedAt,
        expiresAt = expiresAt,
    )
}
