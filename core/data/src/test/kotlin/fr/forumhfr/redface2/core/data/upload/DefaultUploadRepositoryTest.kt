package fr.forumhfr.redface2.core.data.upload

import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
import fr.forumhfr.redface2.core.database.entities.UploadedImageEntity
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.ImageUpload
import fr.forumhfr.redface2.core.domain.upload.UploadProvider
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadedImage
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultUploadRepositoryTest {

    private val diberie = mockk<UploadProvider>(relaxed = true) {
        io.mockk.every { id } returns UploadProviderId.DIBERIE
    }
    private val imgur = mockk<UploadProvider>(relaxed = true) {
        io.mockk.every { id } returns UploadProviderId.IMGUR
    }
    private val dao = mockk<UploadedImageDao>(relaxed = true)
    private val selectedProvider = MutableStateFlow(UploadProviderId.DIBERIE)
    private val prefs = mockk<UserPreferencesRepository> {
        io.mockk.every { observeUploadProvider() } returns selectedProvider
    }

    private fun repository(): DefaultUploadRepository = DefaultUploadRepository(
        providers = mapOf(UploadProviderId.DIBERIE to diberie, UploadProviderId.IMGUR to imgur),
        uploadedImageDao = dao,
        userPreferencesRepository = prefs,
        ioDispatcher = UnconfinedTestDispatcher(),
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
    )

    @Test
    fun `uploadWithCurrentProvider routes to the selected provider and upserts a lowercased trace`() = runTest {
        selectedProvider.value = UploadProviderId.IMGUR
        coEvery { imgur.upload(any()) } returns imgurResult()
        val entitySlot = slot<UploadedImageEntity>()
        coEvery { dao.upsert(capture(entitySlot)) } returns Unit

        val result = repository().uploadWithCurrentProvider(sampleImage(), userId = "XaTriX")

        coVerify { imgur.upload(any()) }
        coVerify(exactly = 0) { diberie.upload(any()) }
        assertEquals(UploadProviderId.IMGUR, result.provider)
        val persisted = entitySlot.captured
        assertEquals("xatrix", persisted.userId)
        assertEquals("IMGUR", persisted.provider)
        assertEquals("DELHASH", persisted.picId)
        assertEquals(FIXED_NOW, persisted.uploadedAt)
    }

    @Test
    fun `uploadWithCurrentProvider falls back to imageUrl as picId when there is no delete handle`() = runTest {
        coEvery { diberie.upload(any()) } returns UploadedImage(
            provider = UploadProviderId.DIBERIE,
            imageUrl = "https://host/f/NOHANDLE",
            thumbnailUrl = null,
            resizedUrl = null,
            deleteHandle = null,
            expiresAt = null,
        )
        val entitySlot = slot<UploadedImageEntity>()
        coEvery { dao.upsert(capture(entitySlot)) } returns Unit

        repository().uploadWithCurrentProvider(sampleImage(), userId = "alice")

        assertEquals("https://host/f/NOHANDLE", entitySlot.captured.picId)
    }

    @Test
    fun `observeUploads preserves an unknown-provider row but disables its deletion`() = runTest {
        // #474 — a row whose stored provider matches no enum value (downgrade, manual edit, future
        // provider) must be PRESERVED (still listable) and NON-DELETABLE (canDelete=false) without
        // throwing: with no known provider a deletion cannot be routed to the right host.
        io.mockk.every { dao.observeForUser("alice") } returns MutableStateFlow(
            listOf(
                entity(provider = "IMGUR", picId = "A"),
                entity(provider = "PHOTOBUCKET", picId = "B"),
            ),
        )

        val records = repository().observeUploads("ALICE").first()

        assertEquals("the unknown-provider row must not be dropped", 2, records.size)
        assertEquals(UploadProviderId.IMGUR, records[0].provider)
        assertTrue("a known-provider record with a handle can be deleted", records[0].canDelete)
        // Unknown stored provider degrades to the safe DIBERIE default instead of crashing valueOf,
        // but its delete handle is dropped so it can never be mis-routed to the wrong host.
        assertEquals(UploadProviderId.DIBERIE, records[1].provider)
        assertFalse("an unknown-provider row must be non-deletable", records[1].canDelete)
        assertEquals("the unknown row stays listable (URL preserved)", "https://host/B", records[1].imageUrl)
    }

    @Test
    fun `delete evicts the local trace even when diberie best-effort returns false`() = runTest {
        coEvery { diberie.delete("HANDLE") } returns false

        val confirmed = repository().delete(record(UploadProviderId.DIBERIE, "HANDLE"), userId = "Alice")

        assertFalse("diberie deletion is best-effort, not confirmed", confirmed)
        coVerify { dao.delete("alice", "DIBERIE", "PIC1") }
    }

    @Test
    fun `delete reports true when imgur confirms and still evicts the trace`() = runTest {
        coEvery { imgur.delete("HANDLE") } returns true

        val confirmed = repository().delete(record(UploadProviderId.IMGUR, "HANDLE"), userId = "alice")

        assertTrue(confirmed)
        coVerify { dao.delete("alice", "IMGUR", "PIC1") }
    }

    @Test
    fun `delete is a no-op host-side when the record has no delete handle, but still evicts`() = runTest {
        val confirmed = repository().delete(record(UploadProviderId.DIBERIE, deleteHandle = null), userId = "alice")

        assertFalse(confirmed)
        coVerify(exactly = 0) { diberie.delete(any()) }
        coVerify { dao.delete("alice", "DIBERIE", "PIC1") }
    }

    private fun sampleImage(): ImageUpload =
        ImageUpload(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = "p.png")

    private fun imgurResult(): UploadedImage = UploadedImage(
        provider = UploadProviderId.IMGUR,
        imageUrl = "https://i.imgur.com/x.png",
        thumbnailUrl = null,
        resizedUrl = null,
        deleteHandle = "DELHASH",
        expiresAt = null,
    )

    private fun entity(provider: String, picId: String): UploadedImageEntity = UploadedImageEntity(
        userId = "alice",
        provider = provider,
        picId = picId,
        imageUrl = "https://host/$picId",
        thumbnailUrl = null,
        deleteHandle = picId,
        uploadedAt = FIXED_NOW,
        expiresAt = null,
    )

    private fun record(provider: UploadProviderId, deleteHandle: String?): UploadedImageRecord =
        UploadedImageRecord(
            provider = provider,
            picId = "PIC1",
            imageUrl = "https://host/PIC1",
            thumbnailUrl = null,
            deleteHandle = deleteHandle,
            uploadedAt = FIXED_NOW,
            expiresAt = null,
        )

    private companion object {
        val FIXED_NOW: Instant = Instant.ofEpochMilli(1_700_000_000_000L)
    }
}
