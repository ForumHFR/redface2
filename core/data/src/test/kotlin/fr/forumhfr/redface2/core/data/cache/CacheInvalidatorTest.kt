package fr.forumhfr.redface2.core.data.cache

import fr.forumhfr.redface2.core.data.messages.PrivateMessageThreadSessionCache
import fr.forumhfr.redface2.core.database.dao.EditorDraftDao
import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.database.dao.MpReadPositionDao
import fr.forumhfr.redface2.core.database.dao.MpStorageLocationDao
import fr.forumhfr.redface2.core.database.dao.UploadedImageDao
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.cache.ImageCacheMaintenance
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheInvalidatorTest {

    @Test
    fun `first login from Anonymous does not purge anyone`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Anonymous)
        val fixture = invalidator(state)
        fixture.invalidator.start()

        state.value = AuthState.Authenticated("alice")

        coVerify(exactly = 0) { fixture.flagDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.mpReadPositionDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.editorDraftDao.deletePrivateForUser(any()) }
        coVerify(exactly = 0) { fixture.uploadedImageDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.mpStorageLocationDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.imageCacheMaintenance.clearImageCache() }
        verify(exactly = 0) { fixture.threadSessionCache.clearAndAdvanceGeneration() }
        verify(exactly = 0) { fixture.flagRepository.clearSessionCache() }
    }

    @Test
    fun `logout purges user rows, MP session and the global image cache`() =
        runTest {
            val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
            val fixture = invalidator(state)
            fixture.invalidator.start()

            state.value = AuthState.Anonymous

            coVerifyOrder {
                fixture.imageCacheMaintenance.clearImageCache()
                fixture.flagDao.deleteAllForUser("alice")
                fixture.mpReadPositionDao.deleteAllForUser("alice")
                fixture.editorDraftDao.deletePrivateForUser("alice")
                fixture.uploadedImageDao.deleteAllForUser("alice")
                fixture.mpStorageLocationDao.deleteAllForUser("alice")
            }
            verify { fixture.threadSessionCache.clearAndAdvanceGeneration() }
            verify { fixture.flagRepository.clearSessionCache() }
        }

    @Test
    fun `RAM generation advances synchronously before the first suspending purge`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val fixture = invalidator(state)
        var generationAdvanced = false
        var generationWasAdvancedBeforeImagePurge = false
        every { fixture.threadSessionCache.clearAndAdvanceGeneration() } answers {
            generationAdvanced = true
        }
        coEvery { fixture.imageCacheMaintenance.clearImageCache() } answers {
            generationWasAdvancedBeforeImagePurge = generationAdvanced
        }
        fixture.invalidator.start()

        state.value = AuthState.Anonymous

        verify(exactly = 1) { fixture.threadSessionCache.clearAndAdvanceGeneration() }
        coVerify(exactly = 1) { fixture.imageCacheMaintenance.clearImageCache() }
        assertTrue(
            "the RAM generation must advance before any suspending purge",
            generationWasAdvancedBeforeImagePurge,
        )
    }

    @Test
    fun `an image cache failure does not skip the other logout purges`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val fixture = invalidator(state)
        coEvery { fixture.imageCacheMaintenance.clearImageCache() } throws IllegalStateException("cache unavailable")
        fixture.invalidator.start()

        state.value = AuthState.Anonymous

        coVerify(exactly = 1) { fixture.imageCacheMaintenance.clearImageCache() }
        coVerify(exactly = 1) { fixture.flagDao.deleteAllForUser("alice") }
        coVerify(exactly = 1) { fixture.mpReadPositionDao.deleteAllForUser("alice") }
        coVerify(exactly = 1) { fixture.editorDraftDao.deletePrivateForUser("alice") }
        coVerify(exactly = 1) { fixture.uploadedImageDao.deleteAllForUser("alice") }
        coVerify(exactly = 1) { fixture.mpStorageLocationDao.deleteAllForUser("alice") }
        verify(exactly = 1) { fixture.flagRepository.clearSessionCache() }
    }

    @Test
    fun `account switch purges the outgoing pseudo not the incoming one`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
        val fixture = invalidator(state)
        fixture.invalidator.start()

        state.value = AuthState.Authenticated("Bob")

        // Pseudo lowercased so "Alice" and "alice" both purge the same row set —
        // HFR is case-insensitive on pseudo display but cookies sometimes mix case.
        coVerify { fixture.flagDao.deleteAllForUser("alice") }
        coVerify { fixture.mpReadPositionDao.deleteAllForUser("alice") }
        coVerify { fixture.editorDraftDao.deletePrivateForUser("alice") }
        coVerify { fixture.uploadedImageDao.deleteAllForUser("alice") }
        coVerify { fixture.mpStorageLocationDao.deleteAllForUser("alice") }
        coVerify { fixture.imageCacheMaintenance.clearImageCache() }
        coVerify(exactly = 0) { fixture.flagDao.deleteAllForUser("bob") }
        coVerify(exactly = 0) { fixture.mpReadPositionDao.deleteAllForUser("bob") }
        coVerify(exactly = 0) { fixture.editorDraftDao.deletePrivateForUser("bob") }
        coVerify(exactly = 0) { fixture.uploadedImageDao.deleteAllForUser("bob") }
        coVerify(exactly = 0) { fixture.mpStorageLocationDao.deleteAllForUser("bob") }
        verify { fixture.threadSessionCache.clearAndAdvanceGeneration() }
        verify { fixture.flagRepository.clearSessionCache() }
    }

    @Test
    fun `same pseudo emitted twice does not trigger a purge`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val fixture = invalidator(state)
        fixture.invalidator.start()

        // distinctUntilChanged collapses identical Authenticated emissions, but
        // even if it didn't, the case-folding pseudo comparison must short-circuit.
        state.value = AuthState.Authenticated("Alice")

        coVerify(exactly = 0) { fixture.flagDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.mpReadPositionDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.editorDraftDao.deletePrivateForUser(any()) }
        coVerify(exactly = 0) { fixture.uploadedImageDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.mpStorageLocationDao.deleteAllForUser(any()) }
        coVerify(exactly = 0) { fixture.imageCacheMaintenance.clearImageCache() }
        verify(exactly = 0) { fixture.threadSessionCache.clearAndAdvanceGeneration() }
        verify(exactly = 0) { fixture.flagRepository.clearSessionCache() }
    }

    private data class Fixture(
        val invalidator: CacheInvalidator,
        val flagDao: FlagDao,
        val mpReadPositionDao: MpReadPositionDao,
        val editorDraftDao: EditorDraftDao,
        val uploadedImageDao: UploadedImageDao,
        val mpStorageLocationDao: MpStorageLocationDao,
        val threadSessionCache: PrivateMessageThreadSessionCache,
        val imageCacheMaintenance: ImageCacheMaintenance,
        val flagRepository: FlagRepository,
    )

    private fun invalidator(stateFlow: Flow<AuthState>): Fixture {
        val authRepository = mockk<AuthRepository>(relaxed = true) {
            io.mockk.every { observeAuthState() } returns stateFlow
        }
        val flagDao = mockk<FlagDao>(relaxed = true)
        val mpReadPositionDao = mockk<MpReadPositionDao>(relaxed = true)
        val editorDraftDao = mockk<EditorDraftDao>(relaxed = true)
        val uploadedImageDao = mockk<UploadedImageDao>(relaxed = true)
        val mpStorageLocationDao = mockk<MpStorageLocationDao>(relaxed = true)
        val threadSessionCache = mockk<PrivateMessageThreadSessionCache>(relaxed = true)
        val imageCacheMaintenance = mockk<ImageCacheMaintenance>(relaxed = true)
        val flagRepository = mockk<FlagRepository>(relaxed = true)
        val invalidator = CacheInvalidator(
            authRepository = authRepository,
            flagDao = flagDao,
            mpReadPositionDao = mpReadPositionDao,
            editorDraftDao = editorDraftDao,
            uploadedImageDao = uploadedImageDao,
            mpStorageLocationDao = mpStorageLocationDao,
            flagRepository = flagRepository,
            privateMessageThreadSessionCache = threadSessionCache,
            imageCacheMaintenance = imageCacheMaintenance,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Fixture(
            invalidator,
            flagDao,
            mpReadPositionDao,
            editorDraftDao,
            uploadedImageDao,
            mpStorageLocationDao,
            threadSessionCache,
            imageCacheMaintenance,
            flagRepository,
        )
    }
}
