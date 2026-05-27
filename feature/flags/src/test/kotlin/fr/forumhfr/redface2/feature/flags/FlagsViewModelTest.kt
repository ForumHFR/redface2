package fr.forumhfr.redface2.feature.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Flag
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlagsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `flagsState stays null while user is anonymous`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Anonymous, flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flagsState mirrors the current tab when authenticated`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            // Initial value (null) before stateIn fires.
            awaitItem()
            // FakeFlagRepository emits Loading then Success(emptyList) on subscribe.
            flags.emit(FlagType.CYAN, FlagsResult.Loading)
            assertEquals(FlagsResult.Loading, awaitItem())
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            val success = awaitItem() as FlagsResult.Success
            assertEquals(1, success.flags.single().topicId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectTab switches the flagsState source`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            val cyan = awaitItem() as FlagsResult.Success
            assertEquals(FlagType.CYAN, cyan.flags.single().type)

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED))))
            val red = awaitItem() as FlagsResult.Success
            assertEquals(FlagType.RED, red.flags.single().type)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to the repository for the current tab`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.selectTab(FlagTab.Favorite)
        vm.refresh()

        assertEquals(listOf(FlagType.FAVORITE), flags.refreshCalls)
    }

    @Test
    fun `refresh toggles isRefreshing around the round-trip`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        assertEquals(false, vm.isRefreshing.value)
        vm.refresh()
        // FakeFlagRepository.refresh returns immediately, so by the time the launched
        // coroutine settles isRefreshing is back to false (UnconfinedTestDispatcher runs
        // it eagerly). The contract pinned here: it must end at false, never stuck true.
        assertEquals(false, vm.isRefreshing.value)
        assertEquals(listOf(FlagType.CYAN), flags.refreshCalls)
    }

    @Test
    fun `selecting the Super tab is a placeholder with no fetch and null state`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            awaitItem() // CYAN content

            vm.selectTab(FlagTab.Super)
            // Super maps to no FlagType: the state collapses back to null (placeholder body).
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // No FlagType is backing Super, so refresh() while on it must not hit the repository.
        vm.refresh()
        assertTrue("Super refresh must be a no-op", flags.refreshCalls.isEmpty())
        assertEquals(false, vm.isRefreshing.value)
    }

    @Test
    fun `re-tapping the already selected Cyan tab toggles the read participated filter`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        // Cyan is selected by default; re-tapping it flips the toggle on, then off.
        assertEquals(false, vm.showReadParticipatedTopics.value)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(true, vm.showReadParticipatedTopics.value)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(false, vm.showReadParticipatedTopics.value)
        // Re-tap must not switch the selected tab or trigger a refetch.
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertTrue("re-tap must not refetch", flags.refreshCalls.isEmpty())
    }

    @Test
    fun `selecting Cyan from another tab does not toggle the filter`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.selectTab(FlagTab.Red)
        assertEquals(false, vm.showReadParticipatedTopics.value)

        // First tap on Cyan from RED selects it without toggling the filter.
        vm.selectTab(FlagTab.Cyan)
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertEquals(false, vm.showReadParticipatedTopics.value)
    }

    // Round-2 review (PR #207): the `logout clears the private flags cache before resetting
    // auth state` test moved to `AppAccountViewModelTest`. `FlagsViewModel.logout()` is gone —
    // the global account menu (#198) now drives the logout from `AppAccountViewModel` which
    // owns the canonical `clearSessionCache → authRepository.logout` ordering. The fakes
    // below are kept because the rest of the suite still exercises auth-state transitions
    // through `clearFlagsCacheIfSessionChanged`.

    @Test
    fun `flagsState propagates SessionExpiredException cause to drive the reconnect CTA`() = runTest {
        // FlagsRoute renders the reconnect CTA branch when `current.cause is SessionExpiredException`.
        // A future refactor that drops the `cause` field on FlagsResult.Failure (e.g. flattening
        // it to a `String message`) would silently break that detection. This test pins the
        // contract: the SessionExpiredException must traverse the repository → ViewModel →
        // exposed state without being unwrapped.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Failure(expired))
            val failure = awaitItem() as FlagsResult.Failure
            assertTrue(
                "expected SessionExpiredException to traverse the stack — got ${failure.cause::class.simpleName}",
                failure.cause is SessionExpiredException,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CYAN tab hides read participated topics by default`() = runTest {
        // #154: « Mes sujets » should not pollute the actionable view with topics the user
        // already finished reading. The filter is applied at the ViewModel layer (not in
        // the repository) so toggling the preference reactively re-emits the filtered list
        // without re-fetching.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true),
                        stubFlag(2, FlagType.CYAN, hasUnread = false),
                        stubFlag(3, FlagType.CYAN, hasUnread = true),
                    ),
                ),
            )

            val filtered = awaitItem() as FlagsResult.Success
            assertEquals(
                "expected only hasUnread=true topics under default CYAN filter",
                listOf(1, 3),
                filtered.flags.map { it.topicId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setShowReadParticipatedTopics true reveals read CYAN topics without refetch`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true),
                        stubFlag(2, FlagType.CYAN, hasUnread = false),
                    ),
                ),
            )
            assertEquals(listOf(1), (awaitItem() as FlagsResult.Success).flags.map { it.topicId })

            vm.setShowReadParticipatedTopics(true)
            // No new refresh() call — the toggle alone must re-emit the unfiltered list
            // because flagsState combines the source flow with showReadParticipatedTopics.
            val full = awaitItem() as FlagsResult.Success
            assertEquals(listOf(1, 2), full.flags.map { it.topicId })
            assertTrue("toggle must not trigger a network refresh", flags.refreshCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `RED and FAVORITE tabs are never filtered by the read participated toggle`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null

            vm.selectTab(FlagTab.Red)
            flags.emit(
                FlagType.RED,
                FlagsResult.Success(
                    listOf(
                        stubFlag(10, FlagType.RED, hasUnread = true),
                        stubFlag(11, FlagType.RED, hasUnread = false),
                    ),
                ),
            )
            val red = awaitItem() as FlagsResult.Success
            assertEquals(
                "RED must include both read and unread regardless of the toggle",
                listOf(10, 11),
                red.flags.map { it.topicId },
            )

            vm.selectTab(FlagTab.Favorite)
            flags.emit(
                FlagType.FAVORITE,
                FlagsResult.Success(
                    listOf(
                        stubFlag(20, FlagType.FAVORITE, hasUnread = false),
                        stubFlag(21, FlagType.FAVORITE, hasUnread = true),
                    ),
                ),
            )
            val favorite = awaitItem() as FlagsResult.Success
            assertEquals(
                "FAVORITE must include both read and unread regardless of the toggle",
                listOf(20, 21),
                favorite.flags.map { it.topicId },
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching authenticated pseudo clears the private flags cache`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            awaitItem()

            auth.emit(AuthState.Authenticated("other"))

            assertEquals(2, flags.clearSessionCacheCallCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestRemoveFlag moves to Confirming and confirm runs through to Success`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)
        val flag = stubFlag(1, FlagType.CYAN)

        vm.removeFlagState.test {
            assertEquals(RemoveFlagState.Idle, awaitItem())

            vm.requestRemoveFlag(flag)
            assertEquals(RemoveFlagState.Confirming(flag), awaitItem())

            // Gate the repository so the Removing state is observable before it resolves.
            flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred()
            vm.confirmRemoveFlag()
            assertEquals(RemoveFlagState.Removing(flag), awaitItem())

            flags.removeFlagResult.complete(Result.success(Unit))
            assertEquals(RemoveFlagState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(flag), flags.removeFlagCalls)
        assertEquals(RemoveFlagEvent.Success(flag.title), vm.removeFlagEvent.value)
    }

    @Test
    fun `cancelRemoveFlag returns to Idle without calling the repository`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)

        vm.requestRemoveFlag(stubFlag(1, FlagType.CYAN))
        vm.cancelRemoveFlag()

        assertEquals(RemoveFlagState.Idle, vm.removeFlagState.value)
        assertTrue("cancel must not call removeFlag", flags.removeFlagCalls.isEmpty())
    }

    @Test
    fun `confirmRemoveFlag failure emits a Failure event`() = runTest {
        val flags = FakeFlagRepository()
        flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred(
            Result.failure(IllegalStateException("delflag refused")),
        )
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)
        val flag = stubFlag(2, FlagType.FAVORITE)

        vm.requestRemoveFlag(flag)
        vm.confirmRemoveFlag()

        assertEquals(RemoveFlagState.Idle, vm.removeFlagState.value)
        assertEquals(RemoveFlagEvent.Failure(flag.title), vm.removeFlagEvent.value)
    }

    @Test
    fun `requestRemoveFlag is ignored while a removal is in flight`() = runTest {
        val flags = FakeFlagRepository()
        flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)
        val firstFlag = stubFlag(1, FlagType.CYAN)

        vm.requestRemoveFlag(firstFlag)
        vm.confirmRemoveFlag() // -> Removing, suspended on the deferred

        // A second request while in flight must be a no-op (anti double-tap).
        vm.requestRemoveFlag(stubFlag(2, FlagType.CYAN))
        assertEquals(RemoveFlagState.Removing(firstFlag), vm.removeFlagState.value)

        flags.removeFlagResult.complete(Result.success(Unit))
    }

    @Test
    fun `consumeRemoveFlagEvent clears the one-shot event`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags)
        val flag = stubFlag(3, FlagType.RED)

        vm.requestRemoveFlag(flag)
        vm.confirmRemoveFlag()
        assertEquals(RemoveFlagEvent.Success(flag.title), vm.removeFlagEvent.value)

        vm.consumeRemoveFlagEvent()
        assertNull(vm.removeFlagEvent.value)
    }

    private fun stubFlag(
        topicId: Int,
        type: FlagType,
        hasUnread: Boolean = true,
    ): Flag = Flag(
        cat = 1,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        type = type,
        hasUnread = hasUnread,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private class FakeAuthRepository(
        initial: AuthState,
        private val flagRepository: FakeFlagRepository? = null,
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)
        var logoutCalled: Boolean = false
            private set

        /**
         * Snapshot of [FakeFlagRepository.clearSessionCacheCallCount] taken at the moment
         * this fake's [logout] runs. The contract pinned by `logout clears the private
         * flags cache before resetting auth state` is: the ViewModel must have called
         * `clearSessionCache()` *before* delegating to `AuthRepository.logout()`. If the
         * order is ever flipped, this number stays at its pre-logout baseline and the
         * test fails.
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
        var refreshCalls: List<FlagType> = emptyList()
            private set
        var clearSessionCacheCallCount: Int = 0
            private set
        var removeFlagCalls: List<Flag> = emptyList()
            private set

        /**
         * Result the next [removeFlag] call returns. A [CompletableDeferred] lets a test gate
         * the suspension so it can assert the intermediate [RemoveFlagState.Removing] before the
         * call resolves.
         */
        var removeFlagResult: kotlinx.coroutines.CompletableDeferred<Result<Unit>> =
            kotlinx.coroutines.CompletableDeferred(Result.success(Unit))

        override fun observe(type: FlagType): Flow<FlagsResult> =
            perType.getValue(type).asSharedFlow()

        override suspend fun refresh(type: FlagType) {
            refreshCalls = refreshCalls + type
        }

        override fun clearSessionCache() {
            clearSessionCacheCallCount += 1
        }

        override suspend fun removeFlag(flag: Flag): Result<Unit> {
            removeFlagCalls = removeFlagCalls + flag
            return removeFlagResult.await()
        }

        suspend fun emit(type: FlagType, result: FlagsResult) {
            perType.getValue(type).emit(result)
        }
    }
}
