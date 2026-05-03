package fr.forumhfr.redface2.core.data.cache

import fr.forumhfr.redface2.core.database.dao.FlagDao
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CacheInvalidatorTest {

    @Test
    fun `first login from Anonymous does not purge anyone`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Anonymous)
        val (invalidator, flagDao, flagRepository) = invalidator(state)
        invalidator.start()

        state.value = AuthState.Authenticated("alice")

        coVerify(exactly = 0) { flagDao.deleteAllForUser(any()) }
        verify(exactly = 0) { flagRepository.clearSessionCache() }
    }

    @Test
    fun `logout purges the previous user's flag rows and the session cache`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val (invalidator, flagDao, flagRepository) = invalidator(state)
        invalidator.start()

        state.value = AuthState.Anonymous

        coVerifyOrder {
            flagDao.deleteAllForUser("alice")
        }
        verify { flagRepository.clearSessionCache() }
    }

    @Test
    fun `account switch purges the outgoing pseudo not the incoming one`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("Alice"))
        val (invalidator, flagDao, flagRepository) = invalidator(state)
        invalidator.start()

        state.value = AuthState.Authenticated("Bob")

        // Pseudo lowercased so "Alice" and "alice" both purge the same row set —
        // HFR is case-insensitive on pseudo display but cookies sometimes mix case.
        coVerify { flagDao.deleteAllForUser("alice") }
        coVerify(exactly = 0) { flagDao.deleteAllForUser("bob") }
        verify { flagRepository.clearSessionCache() }
    }

    @Test
    fun `same pseudo emitted twice does not trigger a purge`() = runTest {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("alice"))
        val (invalidator, flagDao, flagRepository) = invalidator(state)
        invalidator.start()

        // distinctUntilChanged collapses identical Authenticated emissions, but
        // even if it didn't, the case-folding pseudo comparison must short-circuit.
        state.value = AuthState.Authenticated("Alice")

        coVerify(exactly = 0) { flagDao.deleteAllForUser(any()) }
        verify(exactly = 0) { flagRepository.clearSessionCache() }
    }

    private fun invalidator(stateFlow: Flow<AuthState>): Triple<CacheInvalidator, FlagDao, FlagRepository> {
        val authRepository = mockk<AuthRepository>(relaxed = true) {
            io.mockk.every { observeAuthState() } returns stateFlow
        }
        val flagDao = mockk<FlagDao>(relaxed = true)
        val flagRepository = mockk<FlagRepository>(relaxed = true)
        val invalidator = CacheInvalidator(
            authRepository = authRepository,
            flagDao = flagDao,
            flagRepository = flagRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return Triple(invalidator, flagDao, flagRepository)
    }
}
