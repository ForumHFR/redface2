package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-2 review of PR #207 caught that the logout ordering invariant — `clearSessionCache()`
 * fires **before** `authRepository.logout()` — was no longer covered by a live test:
 * `MessagesViewModelTest` (where the invariant used to live) was deleted in #198 and
 * `FlagsViewModelTest.logout clears the private flags cache before resetting auth state`
 * was exercising `FlagsViewModel.logout()`, which the same PR turned into dead code by
 * removing every UI call-site.
 *
 * This file restores the contract on the live class — [AppAccountViewModel.logout] — using
 * the same fake-pair pattern (FakeAuthRepository captures the flag-cache clear count at the
 * exact moment its `logout()` runs, so a flipped order would leave the snapshot at the
 * pre-logout baseline and fail this test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAccountViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `logout clears the private flags cache before resetting auth state`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = AppAccountViewModel(auth, flags)

        val clearsBeforeLogout = flags.clearSessionCacheCallCount

        vm.logout()

        assertTrue("AuthRepository.logout() must be reached", auth.logoutCalled)
        val observed = auth.cacheClearsObservedBeforeLogout
        assertTrue(
            "logout must call clearSessionCache() before AuthRepository.logout() — " +
                "saw $clearsBeforeLogout clears pre-logout, $observed snapshot at logout",
            observed > clearsBeforeLogout,
        )
    }

    @Test
    fun `authState mirrors the AuthRepository observation`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = AppAccountViewModel(auth, flags)

        // SharingStarted.Eagerly + initialValue = null. The first emission of the upstream
        // flow lands synchronously through the UnconfinedTestDispatcher, so we should see
        // the Authenticated state immediately.
        assertEquals(AuthState.Authenticated("xaat"), vm.authState.value)

        auth.emit(AuthState.Anonymous)
        assertEquals(AuthState.Anonymous, vm.authState.value)
    }

    private class FakeAuthRepository(
        initial: AuthState,
        private val flagRepository: FakeFlagRepository? = null,
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)
        var logoutCalled: Boolean = false
            private set

        /**
         * Snapshot of [FakeFlagRepository.clearSessionCacheCallCount] taken at the moment
         * this fake's [logout] runs. If `AppAccountViewModel.logout` ever flips the order
         * (`authRepository.logout()` *before* `flagRepository.clearSessionCache()`), this
         * snapshot stays at the pre-logout baseline and the ordering test fails.
         */
        var cacheClearsObservedBeforeLogout: Int = 0
            private set

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()

        override suspend fun login(pseudo: String, password: String) =
            Result.failure<AuthState.Authenticated>(LoginError.Unknown("not used"))

        override suspend fun logout() {
            cacheClearsObservedBeforeLogout = flagRepository?.clearSessionCacheCallCount ?: 0
            logoutCalled = true
            state.value = AuthState.Anonymous
        }

        fun emit(next: AuthState) {
            state.value = next
        }
    }

    private class FakeFlagRepository : FlagRepository {
        private val perType: Map<FlagType, MutableSharedFlow<FlagsResult>> = FlagType.entries
            .associateWith { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        var clearSessionCacheCallCount: Int = 0
            private set

        override fun observe(type: FlagType): Flow<FlagsResult> =
            perType.getValue(type).asSharedFlow()

        override suspend fun refresh(type: FlagType) {
            // Not exercised in these tests — included to satisfy the interface.
        }

        override fun clearSessionCache() {
            clearSessionCacheCallCount += 1
        }
    }
}
