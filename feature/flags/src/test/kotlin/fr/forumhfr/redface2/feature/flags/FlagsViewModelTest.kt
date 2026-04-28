package fr.forumhfr.redface2.feature.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
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
        val auth = FakeAuthRepository(AuthState.Anonymous)
        val flags = FakeFlagRepository()
        val vm = FlagsViewModel(auth, flags, FakeMessagesRepository())

        vm.flagsState.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flagsState mirrors the current tab when authenticated`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val flags = FakeFlagRepository()
        val vm = FlagsViewModel(auth, flags, FakeMessagesRepository())

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
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val flags = FakeFlagRepository()
        val vm = FlagsViewModel(auth, flags, FakeMessagesRepository())

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            val cyan = awaitItem() as FlagsResult.Success
            assertEquals(FlagType.CYAN, cyan.flags.single().type)

            vm.selectTab(FlagType.RED)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED))))
            val red = awaitItem() as FlagsResult.Success
            assertEquals(FlagType.RED, red.flags.single().type)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to the repository for the current tab`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val flags = FakeFlagRepository()
        val vm = FlagsViewModel(auth, flags, FakeMessagesRepository())

        vm.selectTab(FlagType.FAVORITE)
        vm.refresh()

        assertEquals(listOf(FlagType.FAVORITE), flags.refreshCalls)
    }

    @Test
    fun `logout forwards to the auth repository`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"))
        val flags = FakeFlagRepository()
        val vm = FlagsViewModel(auth, flags, FakeMessagesRepository())

        vm.logout()

        assertTrue(auth.logoutCalled)
    }

    private fun stubFlag(topicId: Int, type: FlagType): Flag = Flag(
        cat = 1,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        views = 0,
        type = type,
        hasUnread = true,
        lastReadPage = 1,
        firstUnreadPostId = 0L,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private class FakeAuthRepository(initial: AuthState) : AuthRepository {
        private val state = MutableStateFlow(initial)
        var logoutCalled: Boolean = false
            private set

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()
        override suspend fun login(pseudo: String, password: String) =
            Result.failure<AuthState.Authenticated>(LoginError.Unknown("not used"))

        override suspend fun logout() {
            logoutCalled = true
            state.value = AuthState.Anonymous
        }
    }

    private class FakeFlagRepository : FlagRepository {
        private val perType: Map<FlagType, MutableSharedFlow<FlagsResult>> = FlagType.entries
            .associateWith { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        var refreshCalls: List<FlagType> = emptyList()
            private set

        override fun observe(type: FlagType): Flow<FlagsResult> =
            perType.getValue(type).asSharedFlow()

        override suspend fun refresh(type: FlagType) {
            refreshCalls = refreshCalls + type
        }

        suspend fun emit(type: FlagType, result: FlagsResult) {
            perType.getValue(type).emit(result)
        }
    }

    private class FakeMessagesRepository : MessagesRepository {
        override fun observeUnreadMpCount(): Flow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
    }
}
