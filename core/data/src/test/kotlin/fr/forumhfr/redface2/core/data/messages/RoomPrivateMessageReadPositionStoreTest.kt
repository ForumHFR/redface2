package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.entities.MpReadPositionEntity
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
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
class RoomPrivateMessageReadPositionStoreTest {

    private val dao = mockk<MpReadPositionDao>(relaxed = true)

    private fun store(authState: AuthState) = RoomPrivateMessageReadPositionStore(
        authRepository = mockk<AuthRepository> {
            every { observeAuthState() } returns MutableStateFlow(authState)
        },
        mpReadPositionDao = dao,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `anonymous session reads null and never touches the DAO`() = runTest {
        val store = store(AuthState.Anonymous)

        assertNull(store.readPage(threadId = 42))
        store.savePage(threadId = 42, page = 7)

        coVerify(exactly = 0) { dao.readPage(any(), any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `authenticated calls delegate with the lowercased pseudo`() = runTest {
        coEvery { dao.readPage(userId = "xatrix", threadId = 42) } returns 7
        val store = store(AuthState.Authenticated("XaTriX"))

        assertEquals(7, store.readPage(threadId = 42))
        store.savePage(threadId = 42, page = 9)

        coVerify {
            dao.upsert(MpReadPositionEntity(userId = "xatrix", threadId = 42, page = 9))
        }
    }

    @Test
    fun `savePage rejects a page below 1`() = runTest {
        val store = store(AuthState.Authenticated("xatrix"))

        store.savePage(threadId = 42, page = 0)

        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
