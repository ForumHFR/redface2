package fr.forumhfr.redface2.core.data.mpstorage

import fr.forumhfr.redface2.core.database.dao.MpStorageLocationDao
import fr.forumhfr.redface2.core.database.entities.MpStorageLocationEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomMpStorageLocationStoreTest {

    private val dao = mockk<MpStorageLocationDao>(relaxed = true)

    private fun store(authState: AuthState) = RoomMpStorageLocationStore(
        authRepository = mockk<AuthRepository> {
            every { observeAuthState() } returns MutableStateFlow(authState)
        },
        mpStorageLocationDao = dao,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `anonymous session reads null and never touches the DAO`() = runTest {
        val store = store(AuthState.Anonymous)

        assertNull(store.read(owner = "xatrix"))
        store.save(owner = "xatrix", threadId = 42, numreponse = 7)
        store.clear(owner = "xatrix")

        coVerify(exactly = 0) { dao.get(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteAllForUser(any()) }
    }

    @Test
    fun `authenticated calls delegate with the lowercased owner`() = runTest {
        coEvery { dao.get("xatrix") } returns MpStorageLocationEntity("xatrix", threadId = 42, numreponse = 7)
        val store = store(AuthState.Authenticated("XaTriX"))

        assertEquals(MpStorageLocation(threadId = 42, numreponse = 7), store.read(owner = "XaTriX"))
        store.save(owner = "XaTriX", threadId = 99, numreponse = 5)

        coVerify {
            dao.upsert(MpStorageLocationEntity(userId = "xatrix", threadId = 99, numreponse = 5))
        }
    }

    @Test
    fun `clear deletes the row for the lowercased owner`() = runTest {
        val store = store(AuthState.Authenticated("XaTriX"))

        store.clear(owner = "XaTriX")

        coVerify { dao.deleteAllForUser("xatrix") }
    }

    @Test
    fun `a stale owner after an account switch is dropped, not misattributed`() = runTest {
        // alice discovered the storage, then the session switched to bob before the write landed.
        val store = store(AuthState.Authenticated("bob"))

        assertNull(store.read(owner = "alice"))
        store.save(owner = "alice", threadId = 42, numreponse = 7)
        store.clear(owner = "alice")

        coVerify(exactly = 0) { dao.get(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteAllForUser(any()) }
    }

    @Test
    fun `a null owner is a no-op`() = runTest {
        val store = store(AuthState.Authenticated("xatrix"))

        assertNull(store.read(owner = null))
        store.save(owner = null, threadId = 42, numreponse = 7)
        store.clear(owner = null)

        coVerify(exactly = 0) { dao.get(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { dao.deleteAllForUser(any()) }
    }
}
