package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.profile.ProfileRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // UnconfinedTestDispatcher dispatches eagerly through `Dispatchers.Main.immediate`,
        // which is what `viewModelScope.launch { ... }` resolves to in the prod ViewModel.
        // That is the only reason `vm.logout()` and `stateIn(SharingStarted.Eagerly, ...)`
        // produce synchronous side-effects in the assertions below — switching to
        // StandardTestDispatcher would require explicit `runCurrent()` / `advanceUntilIdle()`
        // calls between the action and the assertion.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // #718 — the avatar appearance is irrelevant to the logout/avatar-URL contracts under test here;
    // a relaxed mock returning the default appearance keeps the constructor satisfied without a full
    // hand-written UserPreferencesRepository fake (~85 methods). Fresh per call so tests stay isolated.
    private fun fakeUserPrefs(): UserPreferencesRepository =
        mockk(relaxed = true) {
            every { observeAvatarAppearance() } returns flowOf(AvatarAppearance())
        }

    @Test
    fun `logout clears the private flags cache exactly once before resetting auth state`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = AppAccountViewModel(auth, flags, FakeProfileRepository(), fakeUserPrefs())

        val clearsBeforeLogout = flags.clearSessionCacheCallCount

        vm.logout()

        assertTrue("AuthRepository.logout() must be reached", auth.logoutCalled)
        // Round-3 review (PR #207): tightened from `observed > clearsBeforeLogout` to a
        // strict `clearsBeforeLogout + 1`. The previous form passed when `clearSessionCache()`
        // ran any number of times before `authRepository.logout()`; the stricter assertion
        // also catches future regressions that inadvertently add redundant cache clears
        // in the logout coroutine (each would silently widen the surface tested here).
        assertEquals(
            "logout must call clearSessionCache() exactly once before AuthRepository.logout()",
            clearsBeforeLogout + 1,
            auth.cacheClearsObservedBeforeLogout,
        )
    }

    @Test
    fun `authState mirrors the AuthRepository observation across login and logout transitions`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = AppAccountViewModel(auth, flags, FakeProfileRepository(), fakeUserPrefs())

        // SharingStarted.Eagerly + initialValue = null. The first upstream emission lands
        // synchronously through Dispatchers.Main.immediate (= UnconfinedTestDispatcher in
        // setUp), so reading `.value` immediately is deterministic. Documented for the
        // future maintainer who might swap dispatchers.
        assertEquals(AuthState.Authenticated("xaat"), vm.authState.value)

        // Anonymous → Authenticated transition (post-login).
        auth.emit(AuthState.Anonymous)
        assertEquals(AuthState.Anonymous, vm.authState.value)
        auth.emit(AuthState.Authenticated("xaat"))
        assertEquals(AuthState.Authenticated("xaat"), vm.authState.value)
    }

    @Test
    fun `avatarUrl resolves the connected user's avatar from the profile when a userId is present`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat", userId = 42), flagRepository = flags)
        val profiles = FakeProfileRepository(avatarByUserId = mapOf(42 to "https://img/42.png"))

        val vm = AppAccountViewModel(auth, flags, profiles, fakeUserPrefs())

        assertEquals("https://img/42.png", vm.avatarUrl.value)
        assertEquals("the avatar is fetched exactly once", 1, profiles.getProfileCallCount)
    }

    @Test
    fun `avatarUrl stays null and does not fetch when the session carries no userId`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat", userId = null), flagRepository = flags)
        val profiles = FakeProfileRepository()

        val vm = AppAccountViewModel(auth, flags, profiles, fakeUserPrefs())

        assertNull(vm.avatarUrl.value)
        assertEquals("no userId → no profile fetch", 0, profiles.getProfileCallCount)
    }

    @Test
    fun `avatarUrl falls back to null when the profile has no avatar`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat", userId = 42), flagRepository = flags)
        // userId present but the profile resolves to no avatar (HFR rendered no img) → fall back.
        val profiles = FakeProfileRepository(avatarByUserId = mapOf(42 to null))

        val vm = AppAccountViewModel(auth, flags, profiles, fakeUserPrefs())

        assertNull(vm.avatarUrl.value)
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

        override suspend fun removeFlag(flag: fr.forumhfr.redface2.core.model.Flag): Result<Unit> {
            // Not exercised in these tests — included to satisfy the interface (#99).
            return Result.success(Unit)
        }
    }

    /**
     * #479 — fakes the profile fetch used to resolve the connected user's avatar. Records the
     * call count so a test can assert the avatar is fetched once and never for a session without
     * a userId. A userId absent from [avatarByUserId] resolves to a failed [Result].
     */
    private class FakeProfileRepository(
        private val avatarByUserId: Map<Int, String?> = emptyMap(),
    ) : ProfileRepository {
        var getProfileCallCount: Int = 0
            private set

        override suspend fun getProfile(userId: Int): Result<UserProfile> {
            getProfileCallCount += 1
            if (userId !in avatarByUserId) {
                return Result.failure(IllegalStateException("no profile for $userId"))
            }
            return Result.success(
                UserProfile(
                    userId = userId,
                    pseudo = "xaat",
                    avatarUrl = avatarByUserId.getValue(userId),
                    registeredAt = null,
                    postCount = null,
                    location = null,
                    signatureText = null,
                ),
            )
        }
    }
}
