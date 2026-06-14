package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageSeedOutcome
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultMpStorageReadPositionSeederTest {

    private val mpStorageRepository = mockk<MpStorageRepository>()
    private val readPositionStore = mockk<PrivateMessageReadPositionStore>(relaxed = true)

    private fun seeder(authState: AuthState = AuthState.Authenticated(OWNER)) =
        DefaultMpStorageReadPositionSeeder(
            mpStorageRepository = mpStorageRepository,
            readPositionStore = readPositionStore,
            authRepository = mockk<AuthRepository> {
                every { observeAuthState() } returns MutableStateFlow(authState)
            },
            diagnostics = DiagnosticsLog(),
        )

    @Test
    fun `an anonymous session does not even fetch the storage`() = runTest {
        assertEquals(MpStorageSeedOutcome.NotAuthenticated, seeder(AuthState.Anonymous).seed())
        coVerify(exactly = 0) { mpStorageRepository.fetchStorage() }
    }

    @Test
    fun `no storage maps to NoStorage and seeds nothing`() = runTest {
        coEvery { mpStorageRepository.fetchStorage() } returns MpStorageResult.NotFound

        assertEquals(MpStorageSeedOutcome.NoStorage, seeder().seed())
        coVerify(exactly = 0) { readPositionStore.savePage(any(), any(), any()) }
    }

    @Test
    fun `an unreadable document maps to Unreadable and seeds nothing`() = runTest {
        coEvery { mpStorageRepository.fetchStorage() } returns MpStorageResult.Unreadable

        assertEquals(MpStorageSeedOutcome.Unreadable, seeder().seed())
        coVerify(exactly = 0) { readPositionStore.savePage(any(), any(), any()) }
    }

    @Test
    fun `each DT entry with no local position is seeded`() = runTest {
        coEvery { mpStorageRepository.fetchStorage() } returns found(
            MpStorageFlagEntry(threadId = 100, page = 5, numreponse = null, uri = null),
            MpStorageFlagEntry(threadId = 200, page = 12, numreponse = null, uri = null),
        )
        coEvery { readPositionStore.readPage(OWNER, any()) } returns null

        assertEquals(MpStorageSeedOutcome.Seeded(total = 2, applied = 2), seeder().seed())
        coVerify { readPositionStore.savePage(OWNER, 100, 5) }
        coVerify { readPositionStore.savePage(OWNER, 200, 12) }
    }

    @Test
    fun `local-priority — a local position further than the stored DT page is not rewound`() = runTest {
        coEvery { mpStorageRepository.fetchStorage() } returns found(
            MpStorageFlagEntry(threadId = 100, page = 5, numreponse = null, uri = null),
            MpStorageFlagEntry(threadId = 200, page = 12, numreponse = null, uri = null),
        )
        // 100: local page 8 is FURTHER than stored 5 → skip. 200: local page 3 is behind 12 → advance.
        coEvery { readPositionStore.readPage(OWNER, 100) } returns 8
        coEvery { readPositionStore.readPage(OWNER, 200) } returns 3

        assertEquals(MpStorageSeedOutcome.Seeded(total = 2, applied = 1), seeder().seed())
        coVerify(exactly = 0) { readPositionStore.savePage(OWNER, 100, any()) }
        coVerify { readPositionStore.savePage(OWNER, 200, 12) }
    }

    private fun found(vararg entries: MpStorageFlagEntry) = MpStorageResult.Found(
        MpStorageDocument(sourceName = "DTCloud_GM", mpFlags = entries.toList(), rawEnvelope = "{}"),
    )

    private companion object {
        const val OWNER = "XaTriX"
    }
}
