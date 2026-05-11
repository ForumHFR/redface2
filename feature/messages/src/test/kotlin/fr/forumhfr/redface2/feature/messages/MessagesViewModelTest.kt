package fr.forumhfr.redface2.feature.messages

import app.cash.turbine.test
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

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `authState mirrors AuthRepository`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val flags = FakeFlagRepository()
        val vm = MessagesViewModel(auth, flags)

        vm.authState.test {
            // SharingStarted.Eagerly + UnconfinedTestDispatcher collapses the
            // (initial null, upstream value) pair into the most recent state by the time
            // turbine subscribes — use expectMostRecentItem rather than chaining awaitItem
            // calls that may or may not see the transient null.
            assertEquals(AuthState.Authenticated("xaat"), expectMostRecentItem())

            auth.emit(AuthState.Anonymous)
            assertEquals(AuthState.Anonymous, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logout clears the private flags cache before resetting auth state`() = runTest {
        // Same ordering guarantee as FlagsViewModel.logout — the per-user cache must drop
        // before AuthRepository flips to Anonymous so the Flags tab can't redraw the
        // previous user's CYAN/RED rows for a frame after the logout completes.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = MessagesViewModel(auth, flags)

        vm.logout()

        assertEquals(
            "cache must be cleared exactly once during logout",
            1,
            flags.clearSessionCacheCallCount,
        )
        assertTrue("expected logout to delegate to AuthRepository", auth.logoutCalled)
        assertEquals(
            "cache must be cleared *before* AuthRepository.logout() runs",
            1,
            auth.cacheClearsObservedBeforeLogout,
        )
    }

    private class FakeAuthRepository(
        initial: AuthState,
        private val flagRepository: FakeFlagRepository? = null,
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)
        var logoutCalled: Boolean = false
            private set
        var cacheClearsObservedBeforeLogout: Int = 0
            private set

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()
        override suspend fun login(pseudo: String, password: String) =
            Result.failure<AuthState.Authenticated>(LoginError.Unknown("not used"))

        override suspend fun logout() {
            // Snapshot the flag repository's clear count at the moment logout() runs:
            // the ViewModel must have called clearSessionCache() *before* delegating to
            // AuthRepository.logout(). The order matters because a stale auth state
            // observed mid-logout would re-trigger a flag fetch under the old user.
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

        override suspend fun refresh(type: FlagType) = Unit

        override fun clearSessionCache() {
            clearSessionCacheCallCount += 1
        }
    }
}
